package com.qingjing.wallpaper.operations;

import com.qingjing.wallpaper.shared.web.ApiException;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationsService {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    // Formal installation scopes only; test credentials and iOS test installations are excluded.
    private static final String USERS = " FROM anonymous_device d LEFT JOIN ios_installation_acquisition ia ON ia.device_id=d.id ";
    public static final String CHANNEL_SQL = """
        CASE WHEN d.platform='ANDROID' AND d.app_install_scope='com.jiyi.wallpaper' THEN 'ANDROID_OFFLINE'
             WHEN d.platform='ANDROID' AND d.app_install_scope='com.qingjing.bizhi' THEN 'ANDROID_ONLINE'
             WHEN d.platform='IOS' AND d.app_install_scope='com.qingjing.bizhi' THEN 'IOS'
             WHEN d.platform='HARMONYOS' AND d.app_install_scope='com.qingjing.bizhi' THEN 'HARMONYOS'
             ELSE 'OTHER' END
        """;
    private static final String ELIGIBLE = "d.platform<>'H5_TEST' AND COALESCE(ia.is_test_device,FALSE)=FALSE AND ("+CHANNEL_SQL+")<>'OTHER'";
    private final JdbcTemplate jdbc;
    private final Clock clock;
    @Autowired public OperationsService(JdbcTemplate jdbc) { this(jdbc, Clock.systemUTC()); }
    OperationsService(JdbcTemplate jdbc, Clock clock) { this.jdbc=jdbc; this.clock=clock; }

    public record Activity(@Size(max=64) String manufacturer, @Size(max=96) String model,
            @Size(max=96) String osVersion) {}
    public record NoteUpdate(@NotNull @Size(max=500) String note, @Min(0) long version) {}
    public record Note(String note, long version) {}
    public record Daily(LocalDate date, long newUsers, Long activeUsers) {}
    public record Channel(String channel, long totalUsers, long newUsersToday, long activeUsersToday) {}
    public record Overview(String timeZone, LocalDate today, Instant trackedSince, Instant generatedAt,
            long totalUsers, long newUsersToday, long activeUsersToday, long returningUsersToday,
            long activeUsers7Days, long activeUsers30Days, long bannedUsers,
            long redemptionsToday, long downloadRequestsToday, List<Daily> trend, List<Channel> channels) {}
    public record Purchase(String id, String kind, String wallpaperId, String wallpaperTitle,
            String productId, String transactionId, String environment, String status,
            BigDecimal amount, String currency, Instant createdAt, Instant fulfilledAt, Instant revokedAt,
            boolean restored) {}
    public record PurchasePage(List<Purchase> items, com.qingjing.wallpaper.catalog.PublicCatalogDtos.PageMetadata page) {}

    @Transactional
    public void recordActivity(long deviceId, Activity activity, String versionName, String versionCode) {
        Instant now=clock.instant();
        Timestamp at=Timestamp.from(now);
        jdbc.update("""
            INSERT INTO device_activity_daily(activity_date,device_id,first_active_at,last_active_at)
            VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE
              first_active_at=LEAST(first_active_at,VALUES(first_active_at)),
              last_active_at=GREATEST(last_active_at,VALUES(last_active_at))
            """,now.atZone(ZONE).toLocalDate(),deviceId,at,at);
        // Do not change the administrative optimistic version when recording usage.
        jdbc.update("""
            UPDATE anonymous_device SET last_active_at=?,
              app_version_name=COALESCE(?,app_version_name), app_version_code=COALESCE(?,app_version_code),
              device_manufacturer=COALESCE(?,device_manufacturer), device_model=COALESCE(?,device_model),
              device_os_version=COALESCE(?,device_os_version)
            WHERE id=? AND (last_active_at IS NULL OR last_active_at<=?)
            """,at,clean(versionName,64),clean(versionCode,32),clean(activity.manufacturer(),64),
                clean(activity.model(),96),clean(activity.osVersion(),96),deviceId,at);
    }

    private static String clean(String value,int max) {
        if(value==null || value.isBlank() || value.length()>max) return null;
        return value.strip();
    }

    public void recordDownload(long deviceId,long wallpaperId,String ticket) {
        String hash;
        try { hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        jdbc.update("INSERT INTO device_download_request_event(ticket_hash,device_id,wallpaper_id,created_at) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE ticket_hash=ticket_hash",hash,deviceId,wallpaperId,Timestamp.from(clock.instant()));
    }

    @Transactional(readOnly=true)
    public Overview overview(int days, String channel) {
        if(days!=7 && days!=30) throw invalid("days must be 7 or 30");
        requireChannel(channel);
        Instant now=clock.instant();
        LocalDate today=now.atZone(ZONE).toLocalDate();
        Timestamp start=Timestamp.from(today.atStartOfDay(ZONE).toInstant());
        Timestamp end=Timestamp.from(today.plusDays(1).atStartOfDay(ZONE).toInstant());
        Instant tracked=jdbc.queryForObject("SELECT started_at FROM operations_tracking WHERE id=1",Timestamp.class).toInstant();
        String filter=ELIGIBLE+(channel==null || channel.isBlank()?"":" AND ("+CHANNEL_SQL+")='"+channel+"'");
        long total=count("SELECT COUNT(*)"+USERS+" WHERE "+filter);
        long added=count("SELECT COUNT(*)"+USERS+" WHERE "+filter+" AND d.created_at>=? AND d.created_at<?",start,end);
        String dailyFrom=" FROM device_activity_daily a JOIN anonymous_device d ON d.id=a.device_id LEFT JOIN ios_installation_acquisition ia ON ia.device_id=d.id WHERE "+filter;
        long active=count("SELECT COUNT(*)"+dailyFrom+" AND a.activity_date=?",today);
        long returning=count("SELECT COUNT(*)"+dailyFrom+" AND a.activity_date=? AND d.created_at<?",today,start);
        long week=count("SELECT COUNT(DISTINCT a.device_id)"+dailyFrom+" AND a.activity_date BETWEEN ? AND ?",today.minusDays(6),today);
        long month=count("SELECT COUNT(DISTINCT a.device_id)"+dailyFrom+" AND a.activity_date BETWEEN ? AND ?",today.minusDays(29),today);
        long banned=count("SELECT COUNT(*)"+USERS+" WHERE "+filter+" AND EXISTS(SELECT 1 FROM security_ban b WHERE b.subject_type='DEVICE' AND b.subject_value=CAST(d.id AS CHAR) AND b.released_at IS NULL)");
        long redemptions=count("SELECT COUNT(*) FROM redemption_event e JOIN anonymous_device d ON d.id=e.device_id LEFT JOIN ios_installation_acquisition ia ON ia.device_id=d.id WHERE "+filter+" AND e.result='GRANTED' AND e.created_at>=? AND e.created_at<?",start,end);
        long downloads=count("SELECT COUNT(*) FROM device_download_request_event e JOIN anonymous_device d ON d.id=e.device_id LEFT JOIN ios_installation_acquisition ia ON ia.device_id=d.id WHERE "+filter+" AND e.created_at>=? AND e.created_at<?",start,end);
        LocalDate first=today.minusDays(days-1);
        Map<LocalDate,Long> newCounts=new HashMap<>(), activeCounts=new HashMap<>();
        jdbc.query("SELECT DATE(CONVERT_TZ(d.created_at,'+00:00','+08:00')) AS day,COUNT(*) AS n"+USERS+" WHERE "+filter+" AND d.created_at>=? AND d.created_at<? GROUP BY day",rs->{newCounts.put(rs.getDate("day").toLocalDate(),rs.getLong("n"));},Timestamp.from(first.atStartOfDay(ZONE).toInstant()),end);
        jdbc.query("SELECT a.activity_date,COUNT(*) AS n"+dailyFrom+" AND a.activity_date BETWEEN ? AND ? GROUP BY a.activity_date",rs->{activeCounts.put(rs.getDate("activity_date").toLocalDate(),rs.getLong("n"));},first,today);
        List<Daily> trend=new ArrayList<>();
        for(LocalDate day=first;!day.isAfter(today);day=day.plusDays(1)) trend.add(new Daily(day,newCounts.getOrDefault(day,0L),day.isBefore(tracked.atZone(ZONE).toLocalDate())?null:activeCounts.getOrDefault(day,0L)));
        List<Channel> channels=jdbc.query("SELECT "+CHANNEL_SQL+" AS channel,COUNT(*) AS total_users,SUM(d.created_at>=? AND d.created_at<?) AS new_users,SUM(EXISTS(SELECT 1 FROM device_activity_daily a WHERE a.device_id=d.id AND a.activity_date=?)) AS active_users"+USERS+" WHERE "+filter+" GROUP BY channel",(rs,n)->new Channel(rs.getString("channel"),rs.getLong("total_users"),rs.getLong("new_users"),rs.getLong("active_users")),start,end,today);
        return new Overview(ZONE.getId(),today,tracked,now,total,added,active,returning,week,month,banned,redemptions,downloads,trend,channels);
    }

    @Transactional
    public Note updateNote(long id,NoteUpdate body) {
        if(jdbc.update("UPDATE anonymous_device SET operator_note=?,operator_note_version=operator_note_version+1 WHERE id=? AND operator_note_version=?",body.note().strip(),id,body.version())!=1) {
            requireDevice(id);
            throw new ApiException(HttpStatus.CONFLICT,"VERSION_CONFLICT","The user note changed; refresh before editing");
        }
        return new Note(body.note().strip(),body.version()+1);
    }

    @Transactional(readOnly=true)
    public PurchasePage purchases(long deviceId,int page,int pageSize) {
        requireDevice(deviceId);
        if(page<1 || pageSize<1 || pageSize>100) throw invalid("Invalid pagination");
        // Installation associations include restored purchases, without counting a restore as a new sale.
        String union="""
            SELECT o.id,'CREDIT_ORDER' AS kind,CAST(o.wallpaper_id AS CHAR) AS wallpaper_id,w.title AS wallpaper_title,
              o.product_id,o.transaction_id,a.environment,o.status,o.amount,'CNY' AS currency,
              o.created_at,o.fulfilled_at,o.revoked_at,(o.device_id<>?) AS restored
            FROM ios_credit_order o JOIN ios_credit_account a ON a.id=o.account_id JOIN wallpaper w ON w.id=o.wallpaper_id
            WHERE o.device_id=? OR EXISTS(SELECT 1 FROM ios_credit_order_installation i WHERE i.order_id=o.id AND i.device_id=?)
            UNION ALL
            SELECT CAST(t.id AS CHAR),'LEGACY_PURCHASE',CAST(t.wallpaper_id AS CHAR),w.title,t.product_id,t.transaction_id,
              t.environment,IF(t.revoked_at IS NULL,'FULFILLED','REFUNDED'),NULL,NULL,t.purchased_at,t.verified_at,t.revoked_at,
              (t.purchased_at<d.created_at)
            FROM ios_store_transaction t JOIN ios_purchase_installation i ON i.environment=t.environment AND i.bundle_id=t.bundle_id AND i.original_transaction_id=t.original_transaction_id
              JOIN wallpaper w ON w.id=t.wallpaper_id JOIN anonymous_device d ON d.id=i.device_id
            WHERE i.device_id=?
            """;
        long total=count("SELECT COUNT(*) FROM ("+union+") p",deviceId,deviceId,deviceId,deviceId);
        List<Purchase> items=jdbc.query("SELECT * FROM ("+union+") p ORDER BY created_at DESC,kind,id LIMIT ? OFFSET ?",(rs,n)->new Purchase(rs.getString("id"),rs.getString("kind"),rs.getString("wallpaper_id"),rs.getString("wallpaper_title"),rs.getString("product_id"),rs.getString("transaction_id"),rs.getString("environment"),rs.getString("status"),rs.getBigDecimal("amount"),rs.getString("currency"),rs.getTimestamp("created_at").toInstant(),instant(rs.getTimestamp("fulfilled_at")),instant(rs.getTimestamp("revoked_at")),rs.getBoolean("restored")),deviceId,deviceId,deviceId,deviceId,pageSize,(long)(page-1)*pageSize);
        return new PurchasePage(items,new com.qingjing.wallpaper.catalog.PublicCatalogDtos.PageMetadata(page,pageSize,total,(int)((total+pageSize-1)/pageSize)));
    }
    private static Instant instant(Timestamp at) { return at==null?null:at.toInstant(); }
    private void requireDevice(long id) { if(count("SELECT COUNT(*) FROM anonymous_device WHERE id=?",id)==0) throw new ApiException(HttpStatus.NOT_FOUND,"DEVICE_NOT_FOUND","The device was not found"); }
    private long count(String sql,Object... args) { Long n=jdbc.queryForObject(sql,Long.class,args);return n==null?0:n; }
    public static void requireChannel(String channel) { if(channel!=null && !channel.isBlank() && !Set.of("ANDROID_ONLINE","ANDROID_OFFLINE","IOS","HARMONYOS","OTHER").contains(channel)) throw invalid("Invalid app channel"); }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED",message); }
}
