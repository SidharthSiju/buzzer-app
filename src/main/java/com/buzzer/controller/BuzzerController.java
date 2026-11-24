package com.buzzer.controller;

import com.buzzer.config.StompPrincipal;
import com.buzzer.model.KickRequest;
import com.buzzer.model.StateMessage;
import com.buzzer.service.GameSessionService;
import com.buzzer.service.StatePublisher;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * STOMP message handlers. Authentication already happened in StompAuthInterceptor:
 * the principal name is the player's token, or "HOST" for the host.
 * Nothing the client sends (name, id, timestamp) is trusted - it all comes from the server.
 */
@Controller
public class BuzzerController {

    private final GameSessionService sessionService;
    private final StatePublisher publisher;

    public BuzzerController(GameSessionService sessionService, StatePublisher publisher) {
        this.sessionService = sessionService;
        this.publisher = publisher;
    }

    /** Client subscribes to /app/state and instantly gets the current state, only for itself. */
    @SubscribeMapping("/state")
    public StateMessage currentState() {
        return sessionService.snapshot();
    }

    // ------------------------------------------------------------------ players

    @MessageMapping("/buzz")
    public void buzz(Principal principal) {
        if (principal != null && sessionService.buzz(principal.getName())) {
            publisher.publish();
        }
    }

    // --------------------------------------------------------------------- host

    @MessageMapping("/host/new-round")
    public void newRound(Principal principal) {
        if (!isHost(principal)) return;
        sessionService.newRound();
        publisher.publish();
    }

    @MessageMapping("/host/lock")
    public void lock(Principal principal) {
        if (!isHost(principal)) return;
        sessionService.lockRound();
        publisher.publish();
    }

    @MessageMapping("/host/kick")
    public void kick(KickRequest request, Principal principal) {
        if (!isHost(principal) || request == null || request.playerId() == null) return;
        sessionService.kickPlayer(request.playerId());
        publisher.publish();
    }

    /** "End Game & Log Out Everyone": clears all tokens, kills the join code, tells every client. */
    @MessageMapping("/host/end-game")
    public void endGame(Principal principal) {
        if (!isHost(principal)) return;
        sessionService.endGame();
        publisher.publish(); // sessionEnded = true -> every browser clears its token
    }

    private static boolean isHost(Principal principal) {
        return principal != null && StompPrincipal.HOST.equals(principal.getName());
    }
}
