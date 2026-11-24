package com.buzzer.model;

/** Public view of a player (no token) that is broadcast to everyone. */
public record PlayerView(String playerId, String playerName) {
}
