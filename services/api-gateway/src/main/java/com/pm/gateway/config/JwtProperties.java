package com.pm.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT validation contract the gateway enforces at the edge (Phase 2). Values are
 * frozen in docs/ADR-0002:
 * <ul>
 *   <li>{@code publicKey} — auth-service's RSA <b>public</b> key (base64 DER, PEM armor
 *       optional). The gateway can only verify tokens, never mint them — only
 *       auth-service holds the private key.</li>
 *   <li>{@code issuer} — enforced {@code iss}; default {@code finsight-auth}.</li>
 *   <li>{@code audience} — required member of the token {@code aud} set; default
 *       {@code finsight-api}.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "jwt")
@Getter
@Setter
public class JwtProperties {

    /**
     * auth-service's RSA public key; no default — the gateway must be given one (fail fast).
     * With {@code jwksUri} set this is the bootstrap key only: enough to verify tokens before
     * the first JWK Set fetch succeeds, after which the fetched set takes over entirely.
     */
    private String publicKey;

    /**
     * auth-service's JWK Set endpoint. Optional: unset means the gateway is pinned to
     * {@code publicKey} and a key rotation needs a restart. Set, it lets the gateway pick up
     * a rotated key on its own. Not a per-request call — the result is cached.
     */
    private String jwksUri;

    /** Enforced token issuer. */
    private String issuer = "finsight-auth";

    /** Required audience member. */
    private String audience = "finsight-api";
}
