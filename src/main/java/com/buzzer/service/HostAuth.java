package com.buzzer.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Checks the host secret (set via the HOST_SECRET environment variable). */
@Component
public class HostAuth {

    private static final Logger log = LoggerFactory.getLogger(HostAuth.class);

    private final String secret;

    public HostAuth(@Value("${host.secret}") String secret) {
        this.secret = secret;
    }

    @PostConstruct
    void warnIfDefault() {
        if ("changeme".equals(secret)) {
            log.warn("HOST_SECRET is not set - using the insecure default 'changeme'. Set HOST_SECRET before going public!");
        }
    }

    public boolean matches(String candidate) {
        if (candidate == null) return false;
        return MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }
}
