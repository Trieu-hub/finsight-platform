package com.pm.authservice.controller;

import com.pm.authservice.security.jwt.JwtKeyRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * Publishes the RSA public keys that verify FinSight's access tokens, as a JWK Set
 * (RFC 7517) at the standard {@code /.well-known/jwks.json} location.
 *
 * <p>This is what lets a key be rotated without a coordinated restart of all seven
 * validators: they discover the new key here, keyed by the {@code kid} in the token header,
 * instead of each holding one key frozen at startup.
 *
 * <p><b>Public by design.</b> A public key is not a secret — its whole purpose is to be
 * handed to anyone verifying a signature, and publishing it grants no ability to mint tokens
 * (only the private key, which never leaves this service, can do that). The response is
 * built exclusively from {@code PublicKey} instances, so no private material can reach it.
 */
@RestController
@Tag(name = "JWKS", description = "Public keys for verifying FinSight access tokens")
public class JwksController {

    private final JwtKeyRegistry keyRegistry;

    public JwksController(JwtKeyRegistry keyRegistry) {
        this.keyRegistry = keyRegistry;
    }

    @Operation(summary = "JSON Web Key Set",
            description = "The RSA public keys currently accepted for access-token verification. "
                    + "During a key rotation this returns both the new and the outgoing key.")
    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> jwks() {
        // Pre-rendered by jjwt's own serializer (see JwtKeyRegistry): the keys are fixed at
        // startup, and a generic JSON mapper cannot render a JWK correctly anyway.
        return ResponseEntity.ok()
                // Validators cache this themselves; the header keeps any proxy in between from
                // pinning it for longer than a rotation's overlap window, which would strand a
                // validator on a key set that no longer contains the current signing key.
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .body(keyRegistry.jwksJson());
    }
}
