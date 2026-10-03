CREATE TABLE ios_credit_pack (
    bundle_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    credits INT NOT NULL,
    product_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    china_price DECIMAL(10,2) NULL,
    price_sync_status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNSYNCED',
    price_synced_at DATETIME(6) NULL,
    price_sync_error VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    price_sync_next_at DATETIME(6) NULL,
    price_sync_lease_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    price_sync_lease_until DATETIME(6) NULL,
    PRIMARY KEY (bundle_id,credits),
    UNIQUE KEY uk_ios_credit_product (bundle_id,product_id),
    CONSTRAINT ck_ios_credit_pack CHECK (credits IN (1,2,3)),
    CONSTRAINT ck_ios_credit_pack_price CHECK (china_price IS NULL OR china_price=credits)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO ios_credit_pack (bundle_id,credits,product_id) VALUES
    ('com.qingjing.bizhi',1,'com.qingjing.bizhi.credits.1'),
    ('com.qingjing.bizhi',2,'com.qingjing.bizhi.credits.2'),
    ('com.qingjing.bizhi',3,'com.qingjing.bizhi.credits.3');

CREATE TABLE ios_wallpaper_credit_price (
    wallpaper_id BIGINT NOT NULL,
    bundle_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    credits INT NULL,
    first_free_eligible BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    price_version BIGINT NOT NULL DEFAULT 1,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (bundle_id,wallpaper_id),
    CONSTRAINT fk_ios_credit_wallpaper FOREIGN KEY (wallpaper_id) REFERENCES wallpaper(id),
    CONSTRAINT ck_ios_wallpaper_credit_price CHECK (credits IS NULL OR credits IN (1,2,3,4,5,6,7,8,9,10,12,14,15,16,18,20,21,24,27,30)),
    CONSTRAINT ck_ios_credit_price_version CHECK (price_version>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Retain every legacy mapping and purchase for restoration. Only verified,
-- exactly representable existing CNY prices become a new credit price.
INSERT INTO ios_wallpaper_credit_price (wallpaper_id,bundle_id,credits,first_free_eligible,enabled)
SELECT wallpaper_id,bundle_id,
    CASE WHEN china_reference_price IN (1,2,3,4,5,6,7,8,9,10,12,14,15,16,18,20,21,24,27,30)
        THEN CAST(china_reference_price AS UNSIGNED) ELSE NULL END,
    first_free_eligible,enabled
FROM ios_product_mapping;

CREATE TABLE ios_credit_account (
    id BIGINT NOT NULL AUTO_INCREMENT,
    environment VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bundle_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    app_transaction_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ios_credit_account (environment,bundle_id,app_transaction_id),
    CONSTRAINT ck_ios_credit_account_environment CHECK (environment IN ('SANDBOX','PRODUCTION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ios_credit_order (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id BIGINT NOT NULL,
    device_id BIGINT NOT NULL,
    wallpaper_id BIGINT NOT NULL,
    product_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    pack_credits INT NOT NULL,
    quantity INT NOT NULL,
    credits INT NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    price_version BIGINT NOT NULL,
    app_account_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'OPEN',
    checkout_dispatched BOOLEAN NOT NULL DEFAULT FALSE,
    transaction_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    signed_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    fulfilled_at DATETIME(6) NULL,
    open_wallpaper_id BIGINT GENERATED ALWAYS AS (CASE WHEN status='OPEN' THEN wallpaper_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ios_credit_order_token (app_account_token),
    UNIQUE KEY uk_ios_credit_open_order (account_id,open_wallpaper_id),
    UNIQUE KEY uk_ios_credit_order_transaction (account_id,transaction_id),
    CONSTRAINT fk_ios_credit_order_account FOREIGN KEY (account_id) REFERENCES ios_credit_account(id),
    CONSTRAINT fk_ios_credit_order_device FOREIGN KEY (device_id) REFERENCES anonymous_device(id),
    CONSTRAINT fk_ios_credit_order_wallpaper FOREIGN KEY (wallpaper_id) REFERENCES wallpaper(id),
    CONSTRAINT ck_ios_credit_order_values CHECK (pack_credits IN (1,2,3) AND quantity BETWEEN 1 AND 10 AND credits=pack_credits*quantity AND amount=credits),
    CONSTRAINT ck_ios_credit_order_status CHECK (status IN ('OPEN','CANCELLED','FULFILLED','REFUNDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ios_credit_transaction (
    environment VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bundle_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transaction_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (environment,bundle_id,transaction_id),
    UNIQUE KEY uk_ios_credit_transaction_order (order_id),
    CONSTRAINT fk_ios_credit_transaction_order FOREIGN KEY (order_id) REFERENCES ios_credit_order(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ios_credit_ledger (
    id BIGINT NOT NULL AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    entry_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    credits_delta INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ios_credit_ledger_entry (order_id,entry_type),
    CONSTRAINT fk_ios_credit_ledger_account FOREIGN KEY (account_id) REFERENCES ios_credit_account(id),
    CONSTRAINT fk_ios_credit_ledger_order FOREIGN KEY (order_id) REFERENCES ios_credit_order(id),
    CONSTRAINT ck_ios_credit_ledger_type CHECK (entry_type IN ('PURCHASE','REDEEM','REFUND','REVOKE')),
    CONSTRAINT ck_ios_credit_ledger_delta CHECK ((entry_type IN ('PURCHASE','REVOKE') AND credits_delta>0) OR (entry_type IN ('REDEEM','REFUND') AND credits_delta<0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ios_credit_order_installation (
    order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    device_id BIGINT NOT NULL,
    source_reference CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (order_id,device_id),
    UNIQUE KEY uk_ios_credit_installation_source (source_reference),
    CONSTRAINT fk_ios_credit_installation_order FOREIGN KEY (order_id) REFERENCES ios_credit_order(id),
    CONSTRAINT fk_ios_credit_installation_device FOREIGN KEY (device_id) REFERENCES anonymous_device(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE ios_attestation_challenge DROP CHECK ck_ios_challenge_action;
ALTER TABLE ios_attestation_challenge ADD CONSTRAINT ck_ios_challenge_action CHECK
    (action IN ('ENROLL','STATUS','FREE_CLAIM','PURCHASE_SYNC','FREE_RESET','CREDIT_ORDER','CREDIT_RESTORE','CREDIT_CANCEL'));
