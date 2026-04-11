package com.example.ricms.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * F) Startup guardrail: reject predictable default secrets in production.
 *
 * <p>If the active profile contains "prod" or "production", or if the env var
 * {@code RICMS_SECURE_MODE=true} is set, the application will refuse to start
 * when either the JWT secret or the encryption key is still at its default
 * placeholder value.  In other environments a WARN is logged instead.</p>
 *
 * <p>To deploy securely, set the following environment variables before starting:
 * <pre>
 *   APP_JWT_SECRET=&lt;at-least-32-char random string&gt;
 *   APP_ENCRYPTION_SECRET_KEY=&lt;base64-encoded 32-byte AES-256 key&gt;
 * </pre>
 * or supply them via Spring {@code application-prod.yml} / Kubernetes Secrets.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupSecurityValidator {

    private static final String DEFAULT_JWT_SECRET =
            "a-very-long-secret-key-for-ricms-that-is-at-least-256-bits-long";
    private static final String DEFAULT_ENCRYPTION_KEY =
            "CHANGE_ME_32_BYTE_BASE64_KEY_PLACEHOLDER==";

    private final AppProperties appProperties;
    private final Environment environment;

    @PostConstruct
    public void validate() {
        boolean isSecureMode = isProductionProfile() || isSecureModeEnvVar();

        boolean jwtDefault = DEFAULT_JWT_SECRET.equals(appProperties.getJwt().getSecret());
        boolean encDefault = DEFAULT_ENCRYPTION_KEY.equals(appProperties.getEncryption().getSecretKey());

        if (jwtDefault || encDefault) {
            String message = buildMessage(jwtDefault, encDefault);
            if (isSecureMode) {
                throw new IllegalStateException(message);
            } else {
                log.warn(message);
            }
        }
    }

    private boolean isProductionProfile() {
        String[] activeProfiles = environment.getActiveProfiles();
        return Arrays.stream(activeProfiles)
                .anyMatch(p -> "prod".equalsIgnoreCase(p) || "production".equalsIgnoreCase(p));
    }

    private boolean isSecureModeEnvVar() {
        String val = System.getenv("RICMS_SECURE_MODE");
        return "true".equalsIgnoreCase(val);
    }

    private String buildMessage(boolean jwtDefault, boolean encDefault) {
        StringBuilder sb = new StringBuilder(
                "[SECURITY] Application is using predictable default secret(s): ");
        if (jwtDefault) sb.append("JWT_SECRET ");
        if (encDefault) sb.append("ENCRYPTION_KEY ");
        sb.append("— override APP_JWT_SECRET and APP_ENCRYPTION_SECRET_KEY before deploying to production.");
        return sb.toString();
    }
}
