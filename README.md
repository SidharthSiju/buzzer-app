# Quiz Buzzer

QR-join quiz buzzer. Spring Boot 3 (Java 17) + STOMP WebSocket backend that also serves a plain HTML/JS frontend (no build step).

- Players: `/` (opened by scanning the host's QR code), enter a name, get a big buzz button.
- Host: `/host.html`, log in with the host key, show the QR, open/lock rounds, kick players, end the game.

## Run locally

```bash
HOST_SECRET=mysecret mvn spring-boot:run     # or run BuzzerApplication from Eclipse
```

Open http://localhost:8080/host.html, log in with `mysecret`. Open the join URL (shown under the QR) in other browser windows to test players. For phones on your Wi-Fi, open the host page via your PC's LAN IP (e.g. `http://192.168.1.20:8080/host.html`) so the QR points to an address phones can reach.

## Deploy on Render

1. Push this folder to a GitHub repo.
2. Render: New > Web Service > pick the repo > Runtime: **Docker** (uses the included Dockerfile).
3. Environment variable: `HOST_SECRET` = a long random string (required).
4. Deploy. Host page is `https://<your-app>.onrender.com/host.html`.

Notes:
- **Run a single instance.** All state is in memory, so do not scale out.
- On the free tier the server sleeps after ~15 min idle and a restart wipes the game. The host page pings `/api/ping` every 4 minutes while logged in, so open it a few minutes before the event and keep that tab open and the device awake.
- Optional env vars: `MAX_PLAYERS` (default 30), `ALLOWED_ORIGINS` (default `*`; set to your Render URL to lock down).

## How it works

| Step | What happens |
|---|---|
| Host opens dashboard | `GET /api/host/join-code` (needs `X-Host-Key`), QR encodes `https://<host>/?code=XXXXXX` |
| Player scans | `POST /api/join {joinCode, playerName}` returns a random `playerToken` (kept in `sessionStorage`) |
| WebSocket | `/ws-buzzer`; STOMP `CONNECT` must carry `playerToken` or `hostKey`, otherwise refused |
| Buzz | `/app/buzz`; identity comes from the server-side session, never from the message; token re-checked each time |
| End game | `/app/host/end-game` clears all tokens, kills the join code, broadcasts `sessionEnded`; every browser clears its token |

Buzz order is the order messages arrive at the server (single inbound thread), so it includes network latency differences between phones.

Host messages: `/app/host/new-round`, `/lock`, `/kick`, `/end-game` (host session only).
