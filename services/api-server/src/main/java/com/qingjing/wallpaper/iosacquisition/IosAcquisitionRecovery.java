package com.qingjing.wallpaper.iosacquisition;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Completes acknowledged Apple outcomes; uncertain provider writes are never blindly repeated. */
@Component
public class IosAcquisitionRecovery {
    private static final Logger log = LoggerFactory.getLogger(IosAcquisitionRecovery.class);
    private final JdbcTemplate jdbc;
    private final IosAcquisitionService acquisition;
    private final IosAcquisitionProperties properties;

    public IosAcquisitionRecovery(JdbcTemplate jdbc, IosAcquisitionService acquisition, IosAcquisitionProperties properties) {
        this.jdbc = jdbc; this.acquisition = acquisition; this.properties = properties;
    }

    @Scheduled(fixedDelayString="${qingjing.ios-acquisition.recovery-delay:30000}")
    public void recover() {
        if (!properties.isEnabled()) return;
        List<Operation> claims = jdbc.query("SELECT device_id,request_id FROM ios_free_claim WHERE status='APPLE_CONFIRMED' ORDER BY id LIMIT 20",
                (rs,n)->new Operation(rs.getLong(1),rs.getString(2)));
        for (Operation row : claims) {
            try { acquisition.finishClaim(row.device(), row.id()); }
            catch (RuntimeException failure) { log.warn("iOS claim compensation pending: operation={}", row.id()); }
        }
        List<Operation> resets = jdbc.query("SELECT device_id,id FROM ios_free_reset WHERE status='APPLE_RESET_CONFIRMED' ORDER BY created_at LIMIT 20",
                (rs,n)->new Operation(rs.getLong(1),rs.getString(2)));
        for (Operation row : resets) {
            try { acquisition.finishReset(row.device(),row.id()); }
            catch (RuntimeException failure) { log.warn("iOS reset compensation pending: operation={}", row.id()); }
        }
        // Only expire untouched waits. A write that might have reached Apple remains blocked.
        List<Operation> expired = jdbc.query("SELECT device_id,id FROM ios_free_reset WHERE status='WAITING_DEVICE' AND expires_at<UTC_TIMESTAMP(6) LIMIT 20",
                (rs,n)->new Operation(rs.getLong(1),rs.getString(2)));
        for (Operation row : expired) acquisition.expireReset(row.device(),row.id());
        List<Inbox> notifications = jdbc.query("""
                SELECT id,signed_payload FROM apple_notification_inbox
                WHERE status IN ('VERIFIED','RETRYABLE_FAILURE') AND (next_attempt_at IS NULL OR next_attempt_at<UTC_TIMESTAMP(6))
                ORDER BY received_at LIMIT 20
                """, (rs,n)->new Inbox(rs.getString(1),rs.getString(2)));
        for (Inbox row : notifications) {
            // A durable lease coordinates workers. Business updates also lock and deduplicate the inbox row.
            if (jdbc.update("UPDATE apple_notification_inbox SET next_attempt_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE) WHERE id=? AND (next_attempt_at IS NULL OR next_attempt_at<UTC_TIMESTAMP(6))", row.id()) != 1) continue;
            try { acquisition.processNotification(row.id(),row.payload()); }
            catch (RuntimeException failure) {
                jdbc.update("UPDATE apple_notification_inbox SET status='RETRYABLE_FAILURE',attempts=attempts+1,error_code='IOS_DEVICE_PROOF_UNAVAILABLE' WHERE id=? AND status<>'PROCESSED'", row.id());
                log.warn("Apple notification reconciliation pending: inbox={}", row.id());
            }
        }
    }
    private record Operation(long device,String id) {}
    private record Inbox(String id,String payload) {}
}
