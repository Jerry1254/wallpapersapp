ALTER TABLE asset DROP CHECK ck_asset_purpose,
    ADD CONSTRAINT ck_asset_purpose CHECK (purpose IS NULL OR purpose IN (
        'CATEGORY_ICON', 'WALLPAPER_COVER', 'BACKGROUND', 'FOREGROUND', 'PARALLAX_CONFIG',
        'VIDEO', 'TUTORIAL_VIDEO', 'LIVE_PHOTO_IMAGE', 'LIVE_PHOTO_VIDEO', 'LIVE_PHOTO_SOURCE',
        'STATIC_IMAGE', 'THEME_PACKAGE', 'MOVING_PHOTO_SOURCE', 'SUPPORT_IMAGE', 'SUPPORT_VIDEO'
    ));

CREATE TABLE support_conversation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    device_id BIGINT NOT NULL,
    hidden BOOLEAN NOT NULL DEFAULT TRUE,
    admin_read_id BIGINT NOT NULL DEFAULT 0,
    customer_read_id BIGINT NOT NULL DEFAULT 0,
    last_message_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_support_device UNIQUE (device_id),
    CONSTRAINT fk_support_device FOREIGN KEY (device_id) REFERENCES anonymous_device(id),
    CONSTRAINT ck_support_read CHECK (admin_read_id >= 0 AND customer_read_id >= 0),
    INDEX ix_support_list (hidden, last_message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE support_attachment (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NULL,
    created_by_admin_id BIGINT NULL,
    kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    storage_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    filename VARCHAR(255) NOT NULL,
    mime_type VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    width_px INT NULL,
    height_px INT NULL,
    duration_ms BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_support_attachment_conversation FOREIGN KEY (conversation_id) REFERENCES support_conversation(id),
    CONSTRAINT fk_support_attachment_admin FOREIGN KEY (created_by_admin_id) REFERENCES admin_account(id),
    CONSTRAINT uk_support_storage UNIQUE (storage_key),
    CONSTRAINT ck_support_attachment_kind CHECK (kind IN ('IMAGE', 'VIDEO')),
    CONSTRAINT ck_support_attachment_owner CHECK ((conversation_id IS NOT NULL) <> (created_by_admin_id IS NOT NULL)),
    CONSTRAINT ck_support_attachment_size CHECK (size_bytes > 0),
    INDEX ix_support_attachment_conversation (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE support_message (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    sender VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    client_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    text VARCHAR(2000) NULL,
    attachment_id BIGINT NULL,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_support_message_conversation FOREIGN KEY (conversation_id) REFERENCES support_conversation(id),
    CONSTRAINT fk_support_message_attachment FOREIGN KEY (attachment_id) REFERENCES support_attachment(id),
    CONSTRAINT uk_support_message_client UNIQUE (conversation_id, sender, client_id),
    CONSTRAINT ck_support_message_sender CHECK (sender IN ('CUSTOMER', 'ADMIN')),
    CONSTRAINT ck_support_message_kind CHECK (kind IN ('TEXT', 'IMAGE', 'VIDEO')),
    CONSTRAINT ck_support_message_content CHECK (
        (kind = 'TEXT' AND text IS NOT NULL AND attachment_id IS NULL)
        OR (kind IN ('IMAGE', 'VIDEO') AND text IS NULL AND attachment_id IS NOT NULL)
    ),
    INDEX ix_support_message_cursor (conversation_id, id),
    INDEX ix_support_message_unread (conversation_id, sender, id),
    INDEX ix_support_message_attachment (attachment_id, conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE support_library_item (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(60) NOT NULL,
    note VARCHAR(200) NOT NULL DEFAULT '',
    text VARCHAR(2000) NULL,
    attachment_id BIGINT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_support_library_attachment FOREIGN KEY (attachment_id) REFERENCES support_attachment(id),
    CONSTRAINT ck_support_library_kind CHECK (kind IN ('IMAGE', 'VIDEO', 'PHRASE')),
    CONSTRAINT ck_support_library_content CHECK (
        (kind = 'PHRASE' AND text IS NOT NULL AND attachment_id IS NULL)
        OR (kind IN ('IMAGE', 'VIDEO') AND text IS NULL AND attachment_id IS NOT NULL)
    ),
    CONSTRAINT ck_support_library_version CHECK (lock_version >= 0),
    INDEX ix_support_library_list (kind, deleted_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
