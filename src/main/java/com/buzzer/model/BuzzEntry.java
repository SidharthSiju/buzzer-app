package com.buzzer.model;

/** One row of the buzz ranking. deltaMs is the gap to the first buzzer, in milliseconds. */
public record BuzzEntry(int rank, String playerId, String playerName, double deltaMs) {
}
