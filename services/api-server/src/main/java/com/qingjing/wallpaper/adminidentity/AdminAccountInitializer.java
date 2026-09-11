package com.qingjing.wallpaper.adminidentity;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminAccountInitializer implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminAccountInitializer.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AdminIdentityProperties properties;

    public AdminAccountInitializer(
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            AdminIdentityProperties properties) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String username = properties.getBootstrap().getUsername().strip().toLowerCase(Locale.ROOT);
        String password = properties.getBootstrap().getPassword();
        if (username.isEmpty() && password.isEmpty()) {
            return;
        }
        if (!username.matches("[a-z0-9][a-z0-9._-]{3,63}") || password.length() < 12 || password.length() > 128) {
            throw new IllegalStateException("Admin bootstrap credentials do not satisfy the configured contract");
        }

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM admin_account", Integer.class);
        if (count != null && count > 0) {
            LOGGER.info("Admin bootstrap skipped because the singleton account already exists");
            return;
        }
        jdbc.update(
                """
                INSERT INTO admin_account
                    (singleton_key, username, password_hash, password_changed_at)
                VALUES (1, ?, ?, UTC_TIMESTAMP(6))
                """,
                username,
                passwordEncoder.encode(password));
        LOGGER.info("Singleton admin account initialized for username={}", username);
    }
}
