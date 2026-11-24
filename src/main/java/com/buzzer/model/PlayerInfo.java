package com.buzzer.model;

/** Returned once to the player who joined. The token is that player's secret. */
public record PlayerInfo(String playerId, String playerName, String playerToken) {
}
