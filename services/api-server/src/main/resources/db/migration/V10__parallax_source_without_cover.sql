ALTER TABLE parallax_source_package
    DROP CHECK ck_parallax_source_status,
    ADD CONSTRAINT ck_parallax_source_status CHECK (
        status IN ('VALIDATING', 'READY')
        AND (status <> 'READY' OR config_asset_id IS NOT NULL)
    );
