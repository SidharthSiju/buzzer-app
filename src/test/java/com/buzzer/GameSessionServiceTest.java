package com.buzzer;

import com.buzzer.model.PlayerInfo;
import com.buzzer.model.StateMessage;
import com.buzzer.service.GameException;
import com.buzzer.service.GameSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GameSessionServiceTest {

    private GameSessionService svc;

    @BeforeEach
    void setUp() {
        svc = new GameSessionService(3);
    }

    @Test
    void joinNeedsCorrectCode() {
        assertThrows(GameException.class, () -> svc.registerPlayer("WRONG1", "Sara"));
        PlayerInfo p = svc.registerPlayer(svc.getCurrentJoinCode().toLowerCase(), "Sara");
        assertEquals("Sara", p.playerName());
        assertNotNull(svc.validateToken(p.playerToken()));
    }

    @Test
    void rejectsDuplicateNamesAndFullGame() {
        String code = svc.getCurrentJoinCode();
        svc.registerPlayer(code, "Sara");
        assertThrows(GameException.class, () -> svc.registerPlayer(code, "sara"));
        svc.registerPlayer(code, "A");
        svc.registerPlayer(code, "B");
        assertThrows(GameException.class, () -> svc.registerPlayer(code, "C"));
    }

    @Test
    void buzzRankingOnlyOncePerRoundAndOnlyWhenOpen() {
        String code = svc.getCurrentJoinCode();
        PlayerInfo a = svc.registerPlayer(code, "A");
        PlayerInfo b = svc.registerPlayer(code, "B");

        assertFalse(svc.buzz(a.playerToken()), "round not open yet");
        svc.newRound();
        assertTrue(svc.buzz(b.playerToken()));
        assertTrue(svc.buzz(a.playerToken()));
        assertFalse(svc.buzz(a.playerToken()), "second buzz ignored");

        StateMessage s = svc.snapshot();
        assertEquals("B", s.queue().get(0).playerName());
        assertEquals(1, s.queue().get(0).rank());
        assertEquals(0.0, s.queue().get(0).deltaMs());
        assertEquals("A", s.queue().get(1).playerName());

        svc.newRound();
        assertTrue(svc.snapshot().queue().isEmpty());
        assertTrue(svc.buzz(a.playerToken()), "can buzz again next round");
    }

    @Test
    void endGameLogsEveryoneOutAndKillsCode() {
        String code = svc.getCurrentJoinCode();
        PlayerInfo a = svc.registerPlayer(code, "A");
        svc.newRound();
        svc.endGame();

        assertNull(svc.validateToken(a.playerToken()));
        assertFalse(svc.buzz(a.playerToken()));
        assertNull(svc.getCurrentJoinCode());
        assertThrows(GameException.class, () -> svc.registerPlayer(code, "Late"));
        assertTrue(svc.snapshot().sessionEnded());

        String fresh = svc.newGame();
        assertNotEquals(code, fresh);
        assertFalse(svc.snapshot().sessionEnded());
    }

    @Test
    void kickRemovesPlayerAndTheirBuzz() {
        String code = svc.getCurrentJoinCode();
        PlayerInfo a = svc.registerPlayer(code, "A");
        svc.newRound();
        svc.buzz(a.playerToken());
        svc.kickPlayer(a.playerId());
        assertNull(svc.validateToken(a.playerToken()));
        assertTrue(svc.snapshot().queue().isEmpty());
        assertTrue(svc.snapshot().players().isEmpty());
    }
}
