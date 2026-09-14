-- Additive Android secure delivery. Existing installations and entitlements remain unchanged.
CREATE TABLE device_encryption_key (
    credential_id BIGINT NOT NULL,
    public_key_pem TEXT NOT NULL,
    public_key_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (credential_id),
    CONSTRAINT fk_encryption_key_credential FOREIGN KEY (credential_id) REFERENCES device_credential (id) ON DELETE CASCADE,
    CONSTRAINT ck_encryption_key_sha256 CHECK (public_key_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE secure_resource_package (
    resource_version_id BIGINT NOT NULL,
    format_version SMALLINT NOT NULL DEFAULT 2,
    storage_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    size_bytes BIGINT NOT NULL,
    plaintext_size_bytes BIGINT NOT NULL,
    encrypted_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    plaintext_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    signing_key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_key_ciphertext TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (resource_version_id),
    UNIQUE KEY uk_secure_package_storage (storage_key),
    CONSTRAINT fk_secure_package_version FOREIGN KEY (resource_version_id) REFERENCES resource_version (id) ON DELETE RESTRICT,
    CONSTRAINT ck_secure_package_format CHECK (format_version = 2),
    CONSTRAINT ck_secure_package_size CHECK (size_bytes BETWEEN 37 AND 68157440 AND plaintext_size_bytes = size_bytes - 36),
    CONSTRAINT ck_secure_package_hashes CHECK (
        encrypted_sha256 REGEXP '^[a-f0-9]{64}$' AND plaintext_sha256 REGEXP '^[a-f0-9]{64}$' AND manifest_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_secure_package_signing_key CHECK (signing_key_id REGEXP '^[a-z0-9-]{1,64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
