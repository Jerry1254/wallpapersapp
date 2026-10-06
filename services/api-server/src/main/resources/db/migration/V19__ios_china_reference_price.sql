ALTER TABLE ios_product_mapping
    ADD COLUMN china_reference_price DECIMAL(10,2) NULL,
    ADD CONSTRAINT ck_ios_china_reference_price
        CHECK (china_reference_price IS NULL OR china_reference_price > 0);
