package com.pm.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT validation contract the gateway enforces at the edge (Phase 2). Values are
 * frozen in docs/ADR-0002:
 * <ul>
 *   <li>{@code secret} — the shared HMAC secret (same {@code JWT_SECRET} auth-service
 *       signs with). Must be ≥ 512 bits so HS512 verification holds.</li>
 *   <li>{@code issuer} — enforced {@code iss}; default {@code finsight-auth}.</li>
 *   <li>{@code audience} — required member of the token {@code aud} set; default
 *       {@code finsight-api}.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "jwt")
@Getter
@Setter
public class JwtProperties {

    /** Shared HMAC secret; no default — the gateway must be given one (fail fast). */
    private String secret;

    /** Enforced token issuer. */
    private String issuer = "finsight-auth";

    /** Required audience member. */
    private String audience = "finsight-api";
}
