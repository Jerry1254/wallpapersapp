CREATE TABLE parallax_source_package (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    storage_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cover_asset_id BIGINT NULL,
    config_asset_id BIGINT NULL,
    canvas_width INT NOT NULL,
    canvas_height INT NOT NULL,
    format_version INT NOT NULL DEFAULT 1,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by_admin_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_parallax_source_sha UNIQUE (sha256),
    CONSTRAINT fk_parallax_source_cover FOREIGN KEY (cover_asset_id) REFERENCES asset(id),
    CONSTRAINT fk_parallax_source_config FOREIGN KEY (config_asset_id) REFERENCES asset(id),
    CONSTRAINT fk_parallax_source_admin FOREIGN KEY (created_by_admin_id) REFERENCES admin_account(id),
    CONSTRAINT ck_parallax_source_shape CHECK (size_bytes BETWEEN 1 AND 104857600 AND canvas_width BETWEEN 512 AND 4096 AND canvas_height BETWEEN 512 AND 4096 AND format_version = 1),
    CONSTRAINT ck_parallax_source_status CHECK (status IN ('VALIDATING', 'READY') AND (status <> 'READY' OR (cover_asset_id IS NOT NULL AND config_asset_id IS NOT NULL)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE parallax_source_layer (
    source_package_id BIGINT NOT NULL,
    layer_index INT NOT NULL,
    asset_id BIGINT NOT NULL,
    original_filename VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ordinal INT NOT NULL,
    depth DOUBLE NOT NULL,
    scale DOUBLE NOT NULL,
    opacity DOUBLE NOT NULL,
    blend_mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (source_package_id, layer_index),
    CONSTRAINT uk_parallax_layer_role UNIQUE (source_package_id, role, ordinal),
    CONSTRAINT fk_parallax_layer_source FOREIGN KEY (source_package_id) REFERENCES parallax_source_package(id),
    CONSTRAINT fk_parallax_layer_asset FOREIGN KEY (asset_id) REFERENCES asset(id),
    CONSTRAINT ck_parallax_layer_values CHECK (layer_index BETWEEN 1 AND 12 AND role IN ('BACKGROUND', 'FOREGROUND') AND ordinal BETWEEN 0 AND 10 AND depth BETWEEN 0 AND 1 AND scale BETWEEN 1 AND 1.5 AND opacity BETWEEN 0 AND 1 AND blend_mode IN ('normal', 'screen', 'add'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE resource_version
    ADD COLUMN source_package_id BIGINT NULL,
    ADD CONSTRAINT fk_resource_version_source_package FOREIGN KEY (source_package_id) REFERENCES parallax_source_package(id);

-- Rollback cleanup failures remain recoverable without keeping business rows alive.
CREATE TABLE parallax_storage_cleanup (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    storage_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    storage_reference VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_parallax_cleanup_kind CHECK (storage_kind IN ('STAGED', 'OBJECT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
