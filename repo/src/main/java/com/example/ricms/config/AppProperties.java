package com.example.ricms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Jwt jwt = new Jwt();
    private RateLimit rateLimit = new RateLimit();
    private Encryption encryption = new Encryption();

    @Data
    public static class Jwt {
        private String secret = "a-very-long-secret-key-for-ricms-that-is-at-least-256-bits-long";
        private long expirationMs = 3600000L;
    }

    @Data
    public static class RateLimit {
        private int commentsPerHour = 30;
        private int reportsPerDay = 10;
    }

    @Data
    public static class Encryption {
        /** Base64-encoded 32-byte AES-256 key. Override via APP_ENCRYPTION_SECRET_KEY env var. */
        private String secretKey = "CHANGE_ME_32_BYTE_BASE64_KEY_PLACEHOLDER==";
    }
}
