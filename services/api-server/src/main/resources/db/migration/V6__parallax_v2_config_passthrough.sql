-- Product has not launched. Remove v1 source-package test data instead of migrating or
-- repackaging it, then make the v2 simulator config the only source of truth.
CREATE TEMPORARY TABLE v6_parallax_source_ids (
    id BIGINT NOT NULL PRIMARY KEY
);

CREATE TEMPORARY TABLE v6_parallax_version_ids (
    id BIGINT NOT NULL PRIMARY KEY
);

CREATE TEMPORARY TABLE v6_parallax_asset_ids (
    id BIGINT NOT NULL PRIMARY KEY
);

INSERT INTO v6_parallax_source_ids(id)
SELECT id FROM parallax_source_package;

INSERT INTO v6_parallax_version_ids(id)
SELECT id FROM resource_version WHERE source_package_id IS NOT NULL;

INSERT IGNORE INTO v6_parallax_asset_ids(id)
SELECT cover_asset_id FROM parallax_source_package WHERE cover_asset_id IS NOT NULL
UNION SELECT config_asset_id FROM parallax_source_package WHERE config_asset_id IS NOT NULL
UNION SELECT asset_id FROM parallax_source_layer;

-- Runtime objects are removed asynchronously after their business references disappear.
INSERT INTO parallax_storage_cleanup(storage_kind,storage_reference,size_bytes,sha256)
SELECT 'OBJECT',storage_key,size_bytes,sha256 FROM parallax_source_package;

INSERT INTO parallax_storage_cleanup(storage_kind,storage_reference,size_bytes,sha256)
SELECT 'OBJECT',p.storage_key,p.size_bytes,p.encrypted_sha256
FROM secure_resource_package p JOIN v6_parallax_version_ids v ON v.id=p.resource_version_id;

INSERT INTO parallax_storage_cleanup(storage_kind,storage_reference,size_bytes,sha256)
SELECT 'OBJECT',p.storage_key,p.size_bytes,p.encrypted_sha256
FROM preview_resource_package p JOIN v6_parallax_version_ids v ON v.id=p.resource_version_id;

INSERT INTO parallax_storage_cleanup(storage_kind,storage_reference,size_bytes,sha256)
SELECT 'OBJECT',a.storage_key,a.size_bytes,a.sha256
FROM asset a JOIN v6_parallax_asset_ids legacy ON legacy.id=a.id
WHERE NOT EXISTS (SELECT 1 FROM category c WHERE c.icon_asset_id=a.id)
  AND NOT EXISTS (SELECT 1 FROM wallpaper w WHERE w.cover_asset_id=a.id)
  AND NOT EXISTS (SELECT 1 FROM wallpaper_setting_tutorial t WHERE t.video_asset_id=a.id)
  AND NOT EXISTS (
      SELECT 1 FROM resource_binding b
      LEFT JOIN v6_parallax_version_ids legacy_version ON legacy_version.id=b.resource_version_id
      WHERE b.asset_id=a.id AND legacy_version.id IS NULL
  );

DELETE p FROM secure_resource_package p JOIN v6_parallax_version_ids v ON v.id=p.resource_version_id;
DELETE p FROM preview_resource_package p JOIN v6_parallax_version_ids v ON v.id=p.resource_version_id;
DELETE b FROM resource_binding b JOIN v6_parallax_version_ids v ON v.id=b.resource_version_id;

UPDATE resource_version r JOIN v6_parallax_version_ids v ON v.id=r.id
SET r.source_package_id=NULL,
    r.status='REJECTED',
    r.manifest_sha256=NULL,
    r.retired_at=NULL,
    r.lock_version=r.lock_version+1;

DELETE l FROM parallax_source_layer l JOIN v6_parallax_source_ids s ON s.id=l.source_package_id;
DELETE p FROM parallax_source_package p JOIN v6_parallax_source_ids s ON s.id=p.id;

DELETE a FROM asset a JOIN v6_parallax_asset_ids legacy ON legacy.id=a.id
WHERE NOT EXISTS (SELECT 1 FROM category c WHERE c.icon_asset_id=a.id)
  AND NOT EXISTS (SELECT 1 FROM wallpaper w WHERE w.cover_asset_id=a.id)
  AND NOT EXISTS (SELECT 1 FROM wallpaper_setting_tutorial t WHERE t.video_asset_id=a.id)
  AND NOT EXISTS (SELECT 1 FROM resource_binding b WHERE b.asset_id=a.id);

ALTER TABLE parallax_source_package
    DROP CHECK ck_parallax_source_shape,
    ALTER COLUMN format_version SET DEFAULT 2,
    ADD CONSTRAINT ck_parallax_source_shape CHECK (
        size_bytes BETWEEN 1 AND 104857600
        AND canvas_width BETWEEN 512 AND 4096
        AND canvas_height BETWEEN 512 AND 4096
        AND format_version = 2
    );

ALTER TABLE parallax_source_layer
    DROP CHECK ck_parallax_layer_values,
    DROP COLUMN depth,
    DROP COLUMN scale,
    DROP COLUMN opacity,
    DROP COLUMN blend_mode,
    ADD CONSTRAINT ck_parallax_layer_values CHECK (
        layer_index BETWEEN 1 AND 12
        AND role IN ('BACKGROUND', 'FOREGROUND')
        AND ordinal BETWEEN 0 AND 10
    );

DROP TEMPORARY TABLE v6_parallax_asset_ids;
DROP TEMPORARY TABLE v6_parallax_version_ids;
DROP TEMPORARY TABLE v6_parallax_source_ids;
