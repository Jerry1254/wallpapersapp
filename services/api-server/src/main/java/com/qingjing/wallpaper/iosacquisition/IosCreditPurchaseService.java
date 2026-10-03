package com.qingjing.wallpaper.iosacquisition;

import com.qingjing.wallpaper.entitlement.EntitlementGrantService;
import com.qingjing.wallpaper.entitlement.EntitlementGrantService.SourceType;
import com.qingjing.wallpaper.iosacquisition.IosAppleGateway.VerifiedAppIdentity;
import com.qingjing.wallpaper.iosacquisition.IosAppleGateway.VerifiedTransaction;
import com.qingjing.wallpaper.iosacquisition.IosCreditDtos.Order;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Direct credit checkout: the server order fixes the wallpaper and purchased quantity. */
@Service
public class IosCreditPurchaseService {
    private final JdbcTemplate jdbc;
    private final IosAcquisitionProperties properties;
    private final IosCreditProductService products;
    private final IosAppleGateway apple;
    private final EntitlementGrantService grants;
    private final TransactionTemplate transactions;
    public IosCreditPurchaseService(JdbcTemplate jdbc,IosAcquisitionProperties properties,IosCreditProductService products,
            IosAppleGateway apple,EntitlementGrantService grants,TransactionTemplate transactions) {
        this.jdbc=jdbc;this.properties=properties;this.products=products;this.apple=apple;this.grants=grants;this.transactions=transactions;
    }
    public Order create(long device,long wallpaper,String appJws,String verificationId) {
        var identity=apple.verifyAppTransaction(appJws,verificationId);requireIdentity(identity);
        long knownAccount=transactions.execute(tx->account(identity));
        for(Row owned:orders("o.account_id=? AND o.wallpaper_id=? AND o.status='FULFILLED'",knownAccount,wallpaper))
            notification(apple.latestTransaction(owned.environment(),owned.transactionId()));
        return transactions.execute(tx->{
            long account=account(identity);lockAccount(account);
            var owned=orders("o.account_id=? AND o.wallpaper_id=? AND o.status='FULFILLED'",account,wallpaper);
            if(!owned.isEmpty()){attach(owned.get(0),device);return owned.get(0).view(false);}
            var open=orders("o.account_id=? AND o.wallpaper_id=? AND o.status='OPEN'",account,wallpaper);
            if(!open.isEmpty()) {
                Row previous=open.get(0);
                boolean dispatched=jdbc.queryForObject("SELECT checkout_dispatched FROM ios_credit_order WHERE id=?",Boolean.class,previous.id());
                if(!dispatched)jdbc.update("UPDATE ios_credit_order SET checkout_dispatched=TRUE WHERE id=?",previous.id());
                return previous.view(!dispatched);
            }
            var offer=products.requireOffer(wallpaper);
            String id=UUID.randomUUID().toString(),token=UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO ios_credit_order (id,account_id,device_id,wallpaper_id,product_id,pack_credits,quantity,
                        credits,amount,price_version,app_account_token,checkout_dispatched) VALUES (?,?,?,?,?,?,?,?,?,?,?,TRUE)
                    """,id,account,device,wallpaper,offer.productId(),offer.packCredits(),offer.purchaseQuantity(),
                    offer.credits(),offer.chinaReferencePrice(),offer.priceVersion(),token);
            return orders("o.id=?",id).get(0).view(true);
        });
    }
    public void cancel(String orderId,String appJws,String verificationId) {
        var identity=apple.verifyAppTransaction(appJws,verificationId);requireIdentity(identity);
        transactions.executeWithoutResult(tx->{long account=account(identity);lockAccount(account);
            var order=orders("o.id=? AND o.account_id=?",orderId,account);if(order.isEmpty())throw invalid();
            jdbc.update("UPDATE ios_credit_order SET status='CANCELLED' WHERE id=? AND status='OPEN'",orderId);
        });
    }
    public void purchase(long device,VerifiedTransaction value) {
        requireCredit(value);
        transactions.executeWithoutResult(tx->{
            Row order=orderForTransaction(value);lockAccount(order.account());
            order=orders("o.id=?",order.id()).get(0);verifyOrder(order,value,true);bindTransaction(order,value);apply(order,value);
            Row saved=orders("o.id=?",order.id()).get(0);
            if("FULFILLED".equals(saved.status()))attach(saved,device);
        });
    }
    public void notification(VerifiedTransaction value) {
        requireCredit(value);
        transactions.executeWithoutResult(tx->{
            var known=orders("o.app_account_token=?",value.appAccountToken());if(known.isEmpty())return;
            Row order=known.get(0);lockAccount(order.account());order=orders("o.id=?",order.id()).get(0);
            verifyOrder(order,value,false);bindTransaction(order,value);apply(order,value);
        });
    }
    public void restore(long device,String appJws,String verificationId) {
        var identity=apple.verifyAppTransaction(appJws,verificationId);requireIdentity(identity);
        Long account=transactions.execute(tx->account(identity));
        // No MySQL locks while contacting Apple; fresh refund facts precede restored grants.
        for(Row order:orders("o.account_id=? AND o.status='FULFILLED'",account))
            notification(apple.latestTransaction(order.environment(),order.transactionId()));
        transactions.executeWithoutResult(tx->{lockAccount(account);
            for(Row order:orders("o.account_id=? AND o.status='FULFILLED'",account))attach(order,device);
        });
    }
    private void apply(Row order,VerifiedTransaction value) {
        if(order.signedAt()!=null && value.signedAt().isBefore(order.signedAt()))return;
        if("REFUNDED".equals(order.status()))return; // Refunds are terminal, including after a stale replay.
        if(value.revokedAt()!=null) {
            if("FULFILLED".equals(order.status())){ledger(order,"REVOKE",order.credits());ledger(order,"REFUND",-order.credits());}
            jdbc.update("UPDATE ios_credit_order SET status='REFUNDED',transaction_id=?,signed_at=?,revoked_at=? WHERE id=?",
                    value.transactionId(),Timestamp.from(value.signedAt()),Timestamp.from(value.revokedAt()),order.id());
            jdbc.queryForList("SELECT source_reference FROM ios_credit_order_installation WHERE order_id=? ORDER BY device_id",String.class,order.id())
                    .forEach(source->grants.revoke(SourceType.IOS_IAP,source,"Apple credit purchase was refunded"));
        } else if("OPEN".equals(order.status()) || "CANCELLED".equals(order.status())) {
            ledger(order,"PURCHASE",order.credits());ledger(order,"REDEEM",-order.credits());
            jdbc.update("UPDATE ios_credit_order SET status='FULFILLED',transaction_id=?,signed_at=?,fulfilled_at=UTC_TIMESTAMP(6) WHERE id=? AND status IN ('OPEN','CANCELLED')",
                    value.transactionId(),Timestamp.from(value.signedAt()),order.id());
        } else jdbc.update("UPDATE ios_credit_order SET signed_at=? WHERE id=?",Timestamp.from(value.signedAt()),order.id());
    }
    private void bindTransaction(Row order,VerifiedTransaction value) {
        var bound=jdbc.queryForList("SELECT order_id FROM ios_credit_transaction WHERE environment=? AND bundle_id=? AND transaction_id=?",String.class,
                value.environment(),value.bundleId(),value.transactionId());
        if((!bound.isEmpty() && !order.id().equals(bound.get(0))) || (order.transactionId()!=null && !order.transactionId().equals(value.transactionId())))throw invalid();
        jdbc.update("INSERT IGNORE INTO ios_credit_transaction (environment,bundle_id,transaction_id,order_id) VALUES (?,?,?,?)",
                value.environment(),value.bundleId(),value.transactionId(),order.id());
        var ids=jdbc.queryForList("SELECT transaction_id FROM ios_credit_transaction WHERE order_id=?",String.class,order.id());
        if(ids.size()!=1 || !value.transactionId().equals(ids.get(0)))throw invalid();
    }
    private void verifyOrder(Row order,VerifiedTransaction value,boolean verifyAccount) {
        if(!order.environment().equals(value.environment()) || !order.bundleId().equals(value.bundleId())
                || !order.product().equals(value.productId()) || order.quantity()!=value.quantity()
                || !order.token().equals(value.appAccountToken()) || value.priceMilliunits()==null
                || value.priceMilliunits()!=order.credits()*1000L || !"CNY".equals(value.currency()) || !"CHN".equals(value.storefront())
                || (verifyAccount && !order.appIdentity().equals(value.appTransactionId()))
                || value.purchasedAt().isBefore(order.createdAt().minusSeconds(60)))throw invalid();
    }
    private void requireCredit(VerifiedTransaction value) {
        if(!"CONSUMABLE".equals(value.productType()) || !properties.getBundleId().equals(value.bundleId())
                || !properties.storeEnvironments().contains(value.environment()) || value.quantity()<1 || value.quantity()>10
                || value.appAccountToken()==null || value.transactionId()==null || value.purchasedAt()==null || value.signedAt()==null)throw invalid();
    }
    private void requireIdentity(VerifiedAppIdentity value) {
        if(value==null || !properties.getBundleId().equals(value.bundleId()) || !properties.storeEnvironments().contains(value.environment())
                || value.appTransactionId()==null || value.appTransactionId().isBlank())throw invalid();
    }
    private Row orderForTransaction(VerifiedTransaction value) {
        var found=orders("o.app_account_token=?",value.appAccountToken());if(found.isEmpty())throw invalid();return found.get(0);
    }
    private long account(VerifiedAppIdentity identity) {
        jdbc.update("INSERT IGNORE INTO ios_credit_account (environment,bundle_id,app_transaction_id) VALUES (?,?,?)",identity.environment(),identity.bundleId(),identity.appTransactionId());
        return jdbc.queryForObject("SELECT id FROM ios_credit_account WHERE environment=? AND bundle_id=? AND app_transaction_id=?",Long.class,identity.environment(),identity.bundleId(),identity.appTransactionId());
    }
    private void lockAccount(long account){jdbc.queryForObject("SELECT id FROM ios_credit_account WHERE id=? FOR UPDATE",Long.class,account);}
    private void ledger(Row order,String type,int delta){jdbc.update("INSERT INTO ios_credit_ledger (account_id,order_id,entry_type,credits_delta) VALUES (?,?,?,?)",order.account(),order.id(),type,delta);}
    private void attach(Row order,long device) {
        String source;
        try{source=java.util.HexFormat.of().formatHex(AppleCrypto.sha256(("credit\n"+order.id()+"\n"+device).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception failure){throw new IllegalStateException("Cannot create credit entitlement source");}
        jdbc.update("INSERT IGNORE INTO ios_credit_order_installation (order_id,device_id,source_reference) VALUES (?,?,?)",order.id(),device,source);
        grants.grant(device,order.wallpaper(),SourceType.IOS_IAP,source,null,null);
        jdbc.update("UPDATE entitlement_grant SET apple_environment=? WHERE source_type='IOS_IAP' AND source_reference=?",order.environment(),source);
    }
    private List<Row> orders(String where,Object... args) {
        return jdbc.query("SELECT o.*,a.environment,a.bundle_id,a.app_transaction_id FROM ios_credit_order o JOIN ios_credit_account a ON a.id=o.account_id WHERE "+where+" ORDER BY o.created_at DESC",
                (rs,n)->new Row(rs.getString("id"),rs.getLong("account_id"),rs.getLong("wallpaper_id"),rs.getString("product_id"),rs.getInt("pack_credits"),rs.getInt("quantity"),rs.getInt("credits"),
                        rs.getBigDecimal("amount").toPlainString(),rs.getString("app_account_token"),rs.getLong("price_version"),rs.getString("status"),rs.getString("environment"),rs.getString("bundle_id"),
                        rs.getString("app_transaction_id"),rs.getString("transaction_id"),instant(rs.getTimestamp("signed_at")),instant(rs.getTimestamp("created_at"))),args);
    }
    private Instant instant(Timestamp value){return value==null?null:value.toInstant();}
    private ApiException invalid(){return new ApiException(HttpStatus.FORBIDDEN,"IOS_CREDIT_PURCHASE_INVALID","The signed credit purchase does not match its order");}
    private record Row(String id,long account,long wallpaper,String product,int pack,int quantity,int credits,String amount,String token,long version,
            String status,String environment,String bundleId,String appIdentity,String transactionId,Instant signedAt,Instant createdAt) {
        Order view(boolean paymentAllowed){return new Order(id,Long.toString(wallpaper),product,pack,quantity,credits,amount,token,version,status,paymentAllowed);}
    }
}
