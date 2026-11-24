package com.buzzer.config;

import java.security.Principal;

/** Identity attached to a STOMP session: the player's token, or {@link #HOST}. */
public class StompPrincipal implements Principal {

    public static final String HOST = "HOST";

    private final String name;

    public StompPrincipal(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }
}
