package com.buzzer.service;

import com.buzzer.model.BuzzEntry;
import com.buzzer.model.PlayerInfo;
import com.buzzer.model.PlayerView;
import com.buzzer.model.StateMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Holds the entire game in memory: join code, players, buzz queue, round state.
 *
 * Every public method is synchronized. With a handful of players this is more than
 * fast enough and it keeps the buzz ordering simple and obviously correct.
 *
 * NOTE: because state lives in memory, run exactly ONE instance of this app.
 */
@Service
public class GameSessionService {

    // No 0/O/1/I so codes are easy to read aloud
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;
    private static final int MAX_NAME_LENGTH = 20;

    private record RawBuzz(String playerId, String playerName, long nanos) {
    }

    private final SecureRandom random = new SecureRandom();
    private final int maxPlayers;

    private String currentJoinCode;
    private final Map<String, PlayerInfo> playersByToken = new LinkedHashMap<>();
    private final List<RawBuzz> queue = new ArrayList<>();
    private final Set<String> buzzedPlayerIds = new HashSet<>();
    private boolean roundActive = false;
    private boolean sessionEnded = false;

    public GameSessionService(@Value("${game.max-players:30}") int maxPlayers) {
        this.maxPlayers = maxPlayers;
        this.currentJoinCode = newCode();
    }

    // ---------------------------------------------------------------- join code

    public synchronized String getCurrentJoinCode() {
        return currentJoinCode;
    }

    /** New code for latecomers; existing players stay logged in. */
    public synchronized String rotateJoinCode() {
        currentJoinCode = newCode();
        sessionEnded = false;
        return currentJoinCode;
    }

    // ------------------------------------------------------------------ players

    public synchronized PlayerInfo registerPlayer(String joinCode, String rawName) {
        if (currentJoinCode == null || joinCode == null
                || !constantTimeEquals(currentJoinCode, joinCode.trim().toUpperCase(Locale.ROOT))) {
            throw new GameException(HttpStatus.UNAUTHORIZED, "Invalid or expired QR code.");
        }
        String name = cleanName(rawName);
        if (playersByToken.size() >= maxPlayers) {
            throw new GameException(HttpStatus.CONFLICT, "This game is full.");
        }
        for (PlayerInfo p : playersByToken.values()) {
            if (p.playerName().equalsIgnoreCase(name)) {
                throw new GameException(HttpStatus.CONFLICT, "That name is already taken. Pick another one.");
            }
        }
        String token = UUID.randomUUID().toString();
        String playerId = "P-" + UUID.randomUUID().toString().substring(0, 6);
        PlayerInfo player = new PlayerInfo(playerId, name, token);
        playersByToken.put(token, player);
        return player;
    }

    public synchronized PlayerInfo validateToken(String token) {
        return token == null ? null : playersByToken.get(token);
    }

    public synchronized void kickPlayer(String playerId) {
        playersByToken.values().removeIf(p -> p.playerId().equals(playerId));
        queue.removeIf(b -> b.playerId().equals(playerId));
        buzzedPlayerIds.remove(playerId);
    }

    // ------------------------------------------------------------------- rounds

    /** Clears the queue and opens the buzzers for a new question. */
    public synchronized void newRound() {
        if (sessionEnded) return;
        queue.clear();
        buzzedPlayerIds.clear();
        roundActive = true;
    }

    public synchronized void lockRound() {
        roundActive = false;
    }

    /** @return true if the buzz was accepted and added to the queue. */
    public synchronized boolean buzz(String token) {
        long arrival = System.nanoTime();
        PlayerInfo player = validateToken(token);
        if (player == null || !roundActive || !buzzedPlayerIds.add(player.playerId())) {
            return false;
        }
        queue.add(new RawBuzz(player.playerId(), player.playerName(), arrival));
        return true;
    }

    // ----------------------------------------------------------------- end game

    /** Logs everyone out and invalidates the QR code. */
    public synchronized void endGame() {
        playersByToken.clear();
        queue.clear();
        buzzedPlayerIds.clear();
        roundActive = false;
        currentJoinCode = null;
        sessionEnded = true;
    }

    /** Fresh game: new code, nobody joined, buzzers locked. */
    public synchronized String newGame() {
        playersByToken.clear();
        queue.clear();
        buzzedPlayerIds.clear();
        roundActive = false;
        sessionEnded = false;
        currentJoinCode = newCode();
        return currentJoinCode;
    }

    // -------------------------------------------------------------------- state

    public synchronized StateMessage snapshot() {
        List<PlayerView> players = playersByToken.values().stream()
                .map(p -> new PlayerView(p.playerId(), p.playerName()))
                .toList();
        List<BuzzEntry> ranking = new ArrayList<>();
        long first = queue.isEmpty() ? 0 : queue.get(0).nanos();
        for (int i = 0; i < queue.size(); i++) {
            RawBuzz b = queue.get(i);
            double deltaMs = Math.round((b.nanos() - first) / 100_000.0) / 10.0; // 0.1 ms steps
            ranking.add(new BuzzEntry(i + 1, b.playerId(), b.playerName(), deltaMs));
        }
        return new StateMessage(sessionEnded, roundActive, players, ranking);
    }

    // ------------------------------------------------------------------ helpers

    private String newCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String cleanName(String raw) {
        String name = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw new GameException(HttpStatus.BAD_REQUEST, "Please enter your name.");
        }
        if (name.codePointCount(0, name.length()) > MAX_NAME_LENGTH) {
            throw new GameException(HttpStatus.BAD_REQUEST, "Name is too long (max " + MAX_NAME_LENGTH + " characters).");
        }
        if (name.codePoints().anyMatch(Character::isISOControl)) {
            throw new GameException(HttpStatus.BAD_REQUEST, "Name contains invalid characters.");
        }
        return name;
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
