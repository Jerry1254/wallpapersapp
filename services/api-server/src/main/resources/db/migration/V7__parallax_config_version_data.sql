-- 4D configuration versions are client-owned data. The API validates only the stable
-- transport envelope and must not require a migration for future simulator formats.
ALTER TABLE parallax_source_package
    DROP CHECK ck_parallax_source_shape,
    ADD CONSTRAINT ck_parallax_source_shape CHECK (
        size_bytes BETWEEN 1 AND 104857600
        AND canvas_width BETWEEN 512 AND 4096
        AND canvas_height BETWEEN 512 AND 4096
        AND format_version BETWEEN 2 AND 2147483647
    );
