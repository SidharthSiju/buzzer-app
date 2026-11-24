package com.buzzer.config;

import com.buzzer.service.GameSessionService;
import com.buzzer.service.HostAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Set;

/**
 * Gatekeeper for every STOMP frame sent by a client.
 *
 *  CONNECT   - must carry a valid playerToken OR the host key, otherwise the connection is refused.
 *  SUBSCRIBE - only the public state topic is allowed.
 *  SEND      - /app/host/** is host-only; /app/buzz re-checks that the token is still active,
 *              so once the host ends the game no stale connection can buzz.
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthInterceptor.class);
    private static final Set<String> ALLOWED_SUBSCRIPTIONS = Set.of("/topic/state", "/app/state");

    private final GameSessionService sessionService;
    private final HostAuth hostAuth;

    public StompAuthInterceptor(GameSessionService sessionService, HostAuth hostAuth) {
        this.sessionService = sessionService;
        this.hostAuth = hostAuth;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();

        if (StompCommand.CONNECT.equals(command)) {
            String hostKey = accessor.getFirstNativeHeader("hostKey");
            String token = accessor.getFirstNativeHeader("playerToken");
            if (hostAuth.matches(hostKey)) {
                accessor.setUser(new StompPrincipal(StompPrincipal.HOST));
            } else if (sessionService.validateToken(token) != null) {
                accessor.setUser(new StompPrincipal(token));
            } else {
                throw new MessagingException("Unauthorized: scan the QR code to join.");
            }
            return message;
        }

        if (StompCommand.SUBSCRIBE.equals(command)) {
            String destination = accessor.getDestination();
            if (accessor.getUser() == null || destination == null || !ALLOWED_SUBSCRIPTIONS.contains(destination)) {
                log.debug("Dropped SUBSCRIBE to {}", destination);
                return null;
            }
            return message;
        }

        if (StompCommand.SEND.equals(command)) {
            Principal user = accessor.getUser();
            String destination = accessor.getDestination();
            if (user == null || destination == null) {
                return null;
            }
            boolean isHost = StompPrincipal.HOST.equals(user.getName());
            if (destination.startsWith("/app/host/")) {
                if (!isHost) {
                    log.warn("Non-host tried to send to {}", destination);
                    return null;
                }
            } else if (!isHost && sessionService.validateToken(user.getName()) == null) {
                return null; // token was revoked (game ended / kicked): silently ignore
            }
            return message;
        }

        return message;
    }
}
