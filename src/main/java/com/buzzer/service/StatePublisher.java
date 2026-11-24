package com.buzzer.service;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** Broadcasts the current game state to every connected client. */
@Component
public class StatePublisher {

    private final SimpMessagingTemplate messaging;
    private final GameSessionService sessionService;

    public StatePublisher(SimpMessagingTemplate messaging, GameSessionService sessionService) {
        this.messaging = messaging;
        this.sessionService = sessionService;
    }

    public void publish() {
        messaging.convertAndSend("/topic/state", sessionService.snapshot());
    }
}
