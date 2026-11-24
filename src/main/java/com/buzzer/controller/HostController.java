package com.buzzer.controller;

import com.buzzer.service.GameSessionService;
import com.buzzer.service.HostAuth;
import com.buzzer.service.StatePublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Host-only REST endpoints. Every call needs the X-Host-Key header.
 * The join code is deliberately NOT available anywhere else.
 */
@RestController
@RequestMapping("/api/host")
public class HostController {

    private final GameSessionService sessionService;
    private final HostAuth hostAuth;
    private final StatePublisher publisher;

    public HostController(GameSessionService sessionService, HostAuth hostAuth, StatePublisher publisher) {
        this.sessionService = sessionService;
        this.hostAuth = hostAuth;
        this.publisher = publisher;
    }

    /** Also used as the host "login" check. Returns null code after the game ended. */
    @GetMapping("/join-code")
    public ResponseEntity<?> joinCode(@RequestHeader(value = "X-Host-Key", required = false) String hostKey) {
        if (!hostAuth.matches(hostKey)) return unauthorized();
        String code = sessionService.getCurrentJoinCode();
        return ResponseEntity.ok(code == null ? Map.of() : Map.of("joinCode", code));
    }

    /** New code for latecomers; existing players stay in. */
    @PostMapping("/rotate-code")
    public ResponseEntity<?> rotate(@RequestHeader(value = "X-Host-Key", required = false) String hostKey) {
        if (!hostAuth.matches(hostKey)) return unauthorized();
        String code = sessionService.rotateJoinCode();
        publisher.publish();
        return ResponseEntity.ok(Map.of("joinCode", code));
    }

    /** Starts over after "End game": fresh code, empty lobby. */
    @PostMapping("/new-game")
    public ResponseEntity<?> newGame(@RequestHeader(value = "X-Host-Key", required = false) String hostKey) {
        if (!hostAuth.matches(hostKey)) return unauthorized();
        String code = sessionService.newGame();
        publisher.publish();
        return ResponseEntity.ok(Map.of("joinCode", code));
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Wrong host key."));
    }
}
