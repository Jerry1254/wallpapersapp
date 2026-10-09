ALTER TABLE audit_event
    DROP CHECK ck_audit_event_aggregate_type,
    ADD CONSTRAINT ck_audit_event_aggregate_type CHECK (aggregate_type IN (
        'ADMIN_ACCOUNT','ASSET','CATEGORY','WALLPAPER','WALLPAPER_VARIANT','RESOURCE_VERSION',
        'WALLPAPER_TUTORIAL','CODE_BATCH','REDEMPTION_CODE','ANONYMOUS_DEVICE','DEVICE_ENTITLEMENT',
        'DOWNLOAD_TICKET','SYSTEM','SECURITY_POLICY'));

CREATE TABLE security_policy (
    id TINYINT NOT NULL PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    lock_version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CHECK (id = 1)
) ENGINE=InnoDB;
INSERT INTO security_policy(id) VALUES (1);

CREATE TABLE security_rule (
    rule_key VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    threshold_value INT NOT NULL,
    window_seconds INT NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CHECK (threshold_value BETWEEN 1 AND 100000),
    CHECK (window_seconds BETWEEN 10 AND 86400)
) ENGINE=InnoDB;
INSERT INTO security_rule(rule_key,enabled,threshold_value,window_seconds) VALUES
 ('DEVELOPER_MODE',FALSE,1,60), ('USB_DEBUGGING',FALSE,1,60),
 ('ROOT_JAILBREAK',FALSE,1,60), ('EMULATOR',FALSE,1,60),
 ('REQUEST_FLOOD',TRUE,600,60), ('BULK_DOWNLOAD',TRUE,120,86400),
 ('INVALID_SIGNATURE',TRUE,10,60), ('RESOURCE_SCAN',TRUE,100,60);

CREATE TABLE security_ban (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    group_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subject_type VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subject_value VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    origin_device_id BIGINT NULL,
    origin_ip VARCHAR(45) CHARACTER SET ascii COLLATE ascii_bin NULL,
    rule_key VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(300) NOT NULL,
    banned_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    banned_by BIGINT NULL,
    released_at DATETIME(6) NULL,
    released_by BIGINT NULL,
    release_reason VARCHAR(300) NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    active_subject VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin
      GENERATED ALWAYS AS (IF(released_at IS NULL, CONCAT(subject_type, ':', subject_value), NULL)) STORED,
    UNIQUE KEY uk_security_ban_active(active_subject),
    INDEX ix_security_ban_time(banned_at,id),
    INDEX ix_security_ban_group(group_id,released_at),
    FOREIGN KEY (origin_device_id) REFERENCES anonymous_device(id),
    FOREIGN KEY (banned_by) REFERENCES admin_account(id),
    FOREIGN KEY (released_by) REFERENCES admin_account(id),
    CHECK (subject_type IN ('DEVICE','IP')),
    CHECK ((released_at IS NULL AND released_by IS NULL AND release_reason IS NULL)
       OR (released_at IS NOT NULL AND released_by IS NOT NULL AND release_reason IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE security_whitelist (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    subject_type VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subject_value VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    note VARCHAR(300) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by BIGINT NOT NULL,
    removed_at DATETIME(6) NULL,
    removed_by BIGINT NULL,
    active_subject VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin
      GENERATED ALWAYS AS (IF(removed_at IS NULL, CONCAT(subject_type, ':', subject_value), NULL)) STORED,
    UNIQUE KEY uk_security_whitelist_active(active_subject),
    FOREIGN KEY (created_by) REFERENCES admin_account(id),
    FOREIGN KEY (removed_by) REFERENCES admin_account(id),
    CHECK (subject_type IN ('DEVICE','IP'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
