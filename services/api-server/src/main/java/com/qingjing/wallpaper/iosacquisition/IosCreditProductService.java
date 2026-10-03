package com.qingjing.wallpaper.iosacquisition;

import com.qingjing.wallpaper.iosacquisition.IosAcquisitionDtos.IosProductConfiguration;
import com.qingjing.wallpaper.iosacquisition.IosAcquisitionDtos.UpdateIosProductRequest;
import com.qingjing.wallpaper.iosacquisition.IosAcquisitionDtos.Product;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IosCreditProductService {
    private final JdbcTemplate jdbc;
    private final IosAcquisitionProperties acquisition;
    private final IosPricingProperties pricing;
    private final ApplePriceGateway apple;
    public IosCreditProductService(JdbcTemplate jdbc,IosAcquisitionProperties acquisition,IosPricingProperties pricing,ApplePriceGateway apple) {
        this.jdbc=jdbc;this.acquisition=acquisition;this.pricing=pricing;this.apple=apple;
    }
    public static int packFor(int credits) {
        if(credits>0) for(int pack=1;pack<=3;pack++) if(credits%pack==0 && credits/pack<=10)return pack;
        throw new ApiException(HttpStatus.BAD_REQUEST,"IOS_CREDIT_PRICE_UNSUPPORTED",
                "Use 1-10,12,14,15,16,18,20,21,24,27 or 30 yuan; the price must be exactly payable with one credit product");
    }
    public boolean configured(long wallpaper) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ios_wallpaper_credit_price WHERE wallpaper_id=? AND bundle_id=?",Long.class,wallpaper,acquisition.getBundleId())>0;
    }
    public IosProductConfiguration get(long wallpaper) {
        var rows=jdbc.query("SELECT credits,first_free_eligible,enabled,price_version FROM ios_wallpaper_credit_price WHERE wallpaper_id=? AND bundle_id=?",
                (rs,n)->new Configuration(rs.getObject(1,Integer.class),rs.getBoolean(2),rs.getBoolean(3),rs.getLong(4)),wallpaper,acquisition.getBundleId());
        if(rows.isEmpty())return null;
        var config=rows.get(0); var pack=config.credits()==null?null:pack(packFor(config.credits()));
        String status=pack==null?"UNSYNCED":priceStatus(pack);
        return new IosProductConfiguration(pack==null?"":pack.productId(),status.equals("READY")?new BigDecimal(config.credits()).setScale(2).toPlainString():null,
                "APP_STORE_CONNECT","CNY",status,pack==null?null:pack.syncedAt(),pack==null?null:pack.error(),
                config.free(),config.enabled(),false,null,"CREDITS",config.credits(),pack==null?null:pack.credits(),
                pack==null?null:config.credits()/pack.credits(),config.version());
    }
    public IosProductConfiguration empty() {
        return new IosProductConfiguration("",null,"APP_STORE_CONNECT","CNY","UNSYNCED",null,null,false,false,false,null,
                "CREDITS",null,null,null,1L);
    }
    @Transactional
    public IosProductConfiguration update(long wallpaper,UpdateIosProductRequest request) {
        if(!"CREDITS".equals(request.acquisitionMode()))throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED","Credit acquisition mode required");
        if(request.credits()==null && (request.enabled() || request.firstFreeEligible()))throw new ApiException(HttpStatus.BAD_REQUEST,"IOS_CREDIT_PRICE_REQUIRED","Set the wallpaper price before enabling iOS acquisition");
        if(request.credits()!=null)packFor(request.credits());
        var exists=jdbc.queryForObject("SELECT COUNT(*) FROM wallpaper WHERE id=?",Long.class,wallpaper);
        if(exists==0)throw new ApiException(HttpStatus.NOT_FOUND,"WALLPAPER_NOT_FOUND","The wallpaper was not found");
        jdbc.queryForObject("SELECT id FROM wallpaper WHERE id=? FOR UPDATE",Long.class,wallpaper);
        jdbc.update("""
                INSERT INTO ios_wallpaper_credit_price (wallpaper_id,bundle_id,credits,first_free_eligible,enabled)
                VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE
                    price_version=price_version+IF(NOT(credits<=>VALUES(credits)),1,0),
                    credits=VALUES(credits),first_free_eligible=VALUES(first_free_eligible),enabled=VALUES(enabled)
                """,wallpaper,acquisition.getBundleId(),request.credits(),request.firstFreeEligible(),request.enabled());
        return get(wallpaper);
    }
    public List<Product> catalogue() {
        var ids=jdbc.queryForList("""
                SELECT c.wallpaper_id FROM ios_wallpaper_credit_price c JOIN wallpaper w ON w.id=c.wallpaper_id
                WHERE c.bundle_id=? AND c.enabled=TRUE AND w.status='PUBLISHED' AND w.access_type='REDEEM'
                AND EXISTS (SELECT 1 FROM wallpaper_variant v JOIN resource_version rv ON rv.variant_id=v.id
                    WHERE v.wallpaper_id=w.id AND v.enabled=TRUE AND v.platform IN ('IOS','UNIVERSAL') AND rv.status='PUBLISHED')
                ORDER BY c.wallpaper_id
                """,Long.class,acquisition.getBundleId());
        return ids.stream().map(id->{var config=get(id);return new Product(Long.toString(id),config.productId(),config.chinaReferencePrice(),
                config.priceSource(),config.priceCurrency(),config.priceSyncStatus(),config.priceSyncedAt(),"CREDITS",config.credits(),
                config.packCredits(),config.purchaseQuantity(),config.firstFreeEligible());}).toList();
    }
    public IosProductConfiguration requireOffer(long wallpaper) {
        var offer=catalogue().stream().filter(p->p.wallpaperId().equals(Long.toString(wallpaper))).findFirst();
        if(offer.isEmpty())throw new ApiException(HttpStatus.CONFLICT,"IOS_CREDIT_WALLPAPER_UNAVAILABLE","The wallpaper is not available for credit purchase");
        var config=get(wallpaper);
        if(config.credits()==null || !"READY".equals(config.priceSyncStatus()))throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"IOS_CREDIT_PRICE_UNAVAILABLE","The credit pack price is not verified");
        return config;
    }
    @Scheduled(initialDelayString="${qingjing.ios-pricing.initial-delay:15000}",fixedDelayString="${qingjing.ios-pricing.sync-delay:300000}")
    public void synchronizeDuePacks() {
        if(!pricing.configured() || acquisition.getAppAppleId()==null)return;
        var packs=jdbc.queryForList("""
                SELECT credits FROM ios_credit_pack WHERE bundle_id=? AND enabled=TRUE
                AND (price_sync_next_at IS NULL OR price_sync_next_at<=UTC_TIMESTAMP(6))
                AND (price_sync_lease_until IS NULL OR price_sync_lease_until<UTC_TIMESTAMP(6)) ORDER BY credits
                """,Integer.class,acquisition.getBundleId());
        packs.forEach(this::synchronizePack);
    }
    public void synchronizePacks() {
        if(!pricing.configured() || acquisition.getAppAppleId()==null)throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"IOS_PRICE_SYNC_UNAVAILABLE","Configure the App Store Connect price API key");
        for(int pack=1;pack<=3;pack++)synchronizePack(pack);
    }
    public void synchronizePack(int credits) {
        Pack pack=pack(credits); if(pack==null)return;
        Instant now=Instant.now(); String lease=UUID.randomUUID().toString();
        if(jdbc.update("""
                UPDATE ios_credit_pack SET price_sync_lease_id=?,price_sync_lease_until=? WHERE bundle_id=? AND credits=?
                    AND (price_sync_lease_until IS NULL OR price_sync_lease_until<?)
                """,lease,Timestamp.from(now.plusSeconds(180)),acquisition.getBundleId(),credits,Timestamp.from(now))==0)return;
        try {
            var price=apple.currentChinaPrice(pack.productId(),"CONSUMABLE");
            if(price==null || price.customerPrice()==null || price.customerPrice().compareTo(BigDecimal.valueOf(credits))!=0)throw new ApplePriceGateway.PriceFailure("CREDIT_PRICE_MISMATCH");
            Instant done=Instant.now();
            jdbc.update("""
                    UPDATE ios_credit_pack SET china_price=?,price_sync_status='READY',price_synced_at=?,price_sync_error=NULL,
                        price_sync_next_at=?,price_sync_lease_id=NULL,price_sync_lease_until=NULL
                    WHERE bundle_id=? AND credits=? AND product_id=? AND price_sync_lease_id=?
                    """,price.customerPrice(),Timestamp.from(done),Timestamp.from(done.plusSeconds(300)),acquisition.getBundleId(),credits,pack.productId(),lease);
        } catch(RuntimeException failure) {
            String code=failure instanceof ApplePriceGateway.PriceFailure value?value.code():"SYNC_FAILED";
            jdbc.update("""
                    UPDATE ios_credit_pack SET price_sync_status='ERROR',price_sync_error=?,price_sync_next_at=?,
                        price_sync_lease_id=NULL,price_sync_lease_until=NULL WHERE bundle_id=? AND credits=? AND price_sync_lease_id=?
                    """,code,Timestamp.from(Instant.now().plusSeconds(code.equals("APPLE_RATE_LIMITED")?900:300)),acquisition.getBundleId(),credits,lease);
        }
    }
    private Pack pack(int credits) {
        var rows=jdbc.query("SELECT product_id,credits,china_price,price_sync_status,price_synced_at,price_sync_error,enabled FROM ios_credit_pack WHERE bundle_id=? AND credits=?",
                (rs,n)->new Pack(rs.getString(1),rs.getInt(2),rs.getBigDecimal(3),rs.getString(4),rs.getTimestamp(5)==null?null:rs.getTimestamp(5).toInstant(),rs.getString(6),rs.getBoolean(7)),acquisition.getBundleId(),credits);
        return rows.isEmpty()?null:rows.get(0);
    }
    private String priceStatus(Pack pack) {
        if(!pack.enabled())return "UNAVAILABLE";
        if(!pricing.configured())return "UNAVAILABLE";
        if("READY".equals(pack.status()) && (pack.syncedAt()==null || !pack.syncedAt().plus(pricing.getMaxAge()).isAfter(Instant.now())))return "STALE";
        return pack.status();
    }
    private record Configuration(Integer credits,boolean free,boolean enabled,long version) {}
    private record Pack(String productId,int credits,BigDecimal price,String status,Instant syncedAt,String error,boolean enabled) {}
}
