package com.buzzer.controller;

import com.buzzer.model.JoinRequest;
import com.buzzer.model.PlayerInfo;
import com.buzzer.service.GameException;
import com.buzzer.service.GameSessionService;
import com.buzzer.service.StatePublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class JoinController {

    private final GameSessionService sessionService;
    private final StatePublisher publisher;

    public JoinController(GameSessionService sessionService, StatePublisher publisher) {
        this.sessionService = sessionService;
        this.publisher = publisher;
    }

    /** Player scanned the QR code and typed a name. */
    @PostMapping("/join")
    public PlayerInfo join(@RequestBody JoinRequest request) {
        PlayerInfo player = sessionService.registerPlayer(request.joinCode(), request.playerName());
        publisher.publish(); // everyone sees the new player
        return player;
    }

    /** Hit by the host dashboard every few minutes so a free Render instance never goes to sleep. */
    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("status", "ok");
    }

    @ExceptionHandler(GameException.class)
    ResponseEntity<Map<String, String>> handle(GameException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
    }
}
