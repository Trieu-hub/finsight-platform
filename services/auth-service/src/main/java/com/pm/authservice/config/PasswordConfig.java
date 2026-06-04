package com.pm.authservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordConfig {

    /**
     * Delegating encoder for crypto-agility: new hashes are written with an algorithm
     * prefix (e.g. {@code {bcrypt}...}), so the storage format can evolve later without
     * a data migration.
     *
     * <p>Hashes written before this change are raw bcrypt with <em>no</em> prefix; a
     * plain delegating encoder would reject them ("no PasswordEncoder mapped for id
     * null") and lock every existing user out. {@code setDefaultPasswordEncoderForMatches}
     * makes prefix-less hashes fall back to bcrypt verification, so legacy and new users
     * both authenticate.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        DelegatingPasswordEncoder encoder =
                (DelegatingPasswordEncoder) PasswordEncoderFactories.createDelegatingPasswordEncoder();
        encoder.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder());
        return encoder;
    }
}
