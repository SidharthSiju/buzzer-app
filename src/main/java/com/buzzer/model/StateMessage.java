package com.buzzer.model;

import java.util.List;

/**
 * The whole game state, broadcast on /topic/state after every change.
 * Never contains the join code or any tokens.
 */
public record StateMessage(boolean sessionEnded,
                           boolean roundActive,
                           List<PlayerView> players,
                           List<BuzzEntry> queue) {
}
