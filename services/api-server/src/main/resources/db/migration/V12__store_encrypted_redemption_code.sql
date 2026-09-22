ALTER TABLE redemption_code
    ADD COLUMN code_ciphertext VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER code_key_version;
