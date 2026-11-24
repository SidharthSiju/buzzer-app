(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };
  var screens = ['noqr', 'join', 'game', 'ended'];

  function show(name) {
    screens.forEach(function (s) {
      $('screen-' + s).classList.toggle('hidden', s !== name);
    });
  }

  // sessionStorage can throw (private mode etc.), so wrap it
  function store(key, val) {
    try {
      if (val === null) sessionStorage.removeItem(key); else sessionStorage.setItem(key, val);
    } catch (e) { /* ignore */ }
  }
  function load(key) {
    try { return sessionStorage.getItem(key); } catch (e) { return null; }
  }

  var token = load('playerToken');
  var myId = load('playerId');
  var myName = load('playerName');
  var client = null;
  var pending = false;      // buzz sent, waiting for the server's answer
  var wakeLock = null;

  // ------------------------------------------------------------------ startup

  if (token) {
    startGame();
  } else {
    var code = new URLSearchParams(window.location.search).get('code');
    if (!code) {
      show('noqr');
    } else {
      show('join');
      setupJoin(code);
    }
  }

  // --------------------------------------------------------------------- join

  function setupJoin(code) {
    $('joinForm').addEventListener('submit', function (ev) {
      ev.preventDefault();
      var name = $('nameInput').value.trim();
      if (!name) return;
      $('joinBtn').disabled = true;
      $('joinError').textContent = '';

      fetch('/api/join', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ joinCode: code, playerName: name })
      }).then(function (res) {
        return res.json().catch(function () { return {}; }).then(function (body) {
          if (!res.ok) throw new Error(body.error || 'Could not join.');
          return body;
        });
      }).then(function (player) {
        token = player.playerToken;
        myId = player.playerId;
        myName = player.playerName;
        store('playerToken', token);
        store('playerId', myId);
        store('playerName', myName);
        // remove the code from the address bar
        window.history.replaceState({}, '', window.location.pathname);
        startGame();
      }).catch(function (err) {
        $('joinError').textContent = err.message === 'Failed to fetch'
          ? 'Cannot reach the server. Check your connection.' : err.message;
        $('joinBtn').disabled = false;
      });
    });
  }

  // --------------------------------------------------------------- game / ws

  function startGame() {
    show('game');
    $('meName').textContent = myName || '';
    requestWakeLock();
    document.addEventListener('visibilitychange', function () {
      if (document.visibilityState === 'visible' && token) requestWakeLock();
    });

    var proto = window.location.protocol === 'https:' ? 'wss://' : 'ws://';
    client = new StompJs.Client({
      brokerURL: proto + window.location.host + '/ws-buzzer',
      connectHeaders: { playerToken: token },
      reconnectDelay: 2000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000
    });

    client.onConnect = function () {
      setStatus('Connected', '');
      client.subscribe('/topic/state', onStateMessage);
      client.subscribe('/app/state', onStateMessage); // immediate snapshot for us
    };
    client.onWebSocketClose = function () {
      if (token) setStatus('Reconnecting…', '');
    };
    client.onStompError = function (frame) {
      var msg = (frame.headers && frame.headers.message) || '';
      if (/unauthorized/i.test(msg)) {
        // token no longer valid (server restarted or game ended while we were offline)
        logout('Session expired', 'Your session is no longer valid. Please scan the QR code again.');
      }
    };
    client.activate();

    var btn = $('buzzBtn');
    // pointerdown fires earlier than click on phones => lower latency
    btn.addEventListener('pointerdown', function (ev) { ev.preventDefault(); buzz(); });
    btn.addEventListener('keydown', function (ev) {
      if (ev.key === ' ' || ev.key === 'Enter') { ev.preventDefault(); buzz(); }
    });
  }

  function onStateMessage(msg) {
    render(JSON.parse(msg.body));
  }

  var lastState = null;

  function buzz() {
    if (!client || !client.connected || pending) return;
    if (!lastState || !lastState.roundActive) return;
    if (lastState.queue.some(function (q) { return q.playerId === myId; })) return;
    pending = true;
    client.publish({ destination: '/app/buzz' });
    if (navigator.vibrate) navigator.vibrate(40);
    $('buzzBtn').disabled = true;
    $('buzzBtn').textContent = '…';
    // safety net: if no answer comes back, restore the button from the last known state
    setTimeout(function () { if (pending && lastState) render(lastState); }, 3000);
  }

  function render(state) {
    lastState = state;
    pending = false;

    if (state.sessionEnded) {
      logout('Thanks for playing!', 'The session has ended. You have been logged out.');
      return;
    }
    var stillIn = state.players.some(function (p) { return p.playerId === myId; });
    if (!stillIn) {
      logout('You were removed', 'The host removed you from the game.');
      return;
    }

    var myIndex = -1;
    state.queue.forEach(function (q, i) { if (q.playerId === myId) myIndex = i; });
    var btn = $('buzzBtn');
    var rankText = $('rankText');
    btn.className = '';

    if (myIndex >= 0) {
      btn.disabled = true;
      if (myIndex === 0) {
        btn.textContent = 'FIRST!';
        btn.classList.add('first');
        rankText.textContent = 'You buzzed first! 🎉';
      } else {
        btn.textContent = '#' + (myIndex + 1);
        btn.classList.add('buzzed');
        rankText.textContent = 'You are number ' + (myIndex + 1);
      }
    } else if (state.roundActive) {
      btn.disabled = false;
      btn.textContent = 'BUZZ!';
      rankText.textContent = '';
    } else {
      btn.disabled = true;
      btn.textContent = 'WAIT';
      rankText.textContent = state.queue.length ? 'Buzzers are locked.' : 'Waiting for the host…';
    }

    setStatus(state.roundActive ? 'Buzzers open' : 'Locked', state.roundActive ? 'live' : 'locked');
    renderQueue(state.queue);
  }

  function renderQueue(queue) {
    var list = $('queueList');
    list.textContent = '';
    $('queueEmpty').classList.toggle('hidden', queue.length > 0);
    queue.forEach(function (q, i) {
      var li = document.createElement('li');
      if (i === 0) li.className = 'first';
      if (q.playerId === myId) li.classList.add('me');
      var rank = document.createElement('span'); rank.className = 'rank'; rank.textContent = q.rank;
      var name = document.createElement('span'); name.className = 'name'; name.textContent = q.playerName;
      var delta = document.createElement('span'); delta.className = 'delta';
      delta.textContent = i === 0 ? '' : '+' + q.deltaMs.toFixed(1) + ' ms';
      li.appendChild(rank); li.appendChild(name); li.appendChild(delta);
      list.appendChild(li);
    });
  }

  function setStatus(text, cls) {
    var el = $('roundStatus');
    el.textContent = text;
    el.className = 'status' + (cls ? ' ' + cls : '');
  }

  // ------------------------------------------------------------------- logout

  function logout(title, text) {
    store('playerToken', null);
    store('playerId', null);
    store('playerName', null);
    token = null;
    if (client) { client.deactivate(); client = null; }
    releaseWakeLock();
    $('endedTitle').textContent = title;
    $('endedText').textContent = text;
    show('ended');
  }

  // --------------------------------------------------------------- wake lock

  function requestWakeLock() {
    try {
      if ('wakeLock' in navigator && !wakeLock) {
        navigator.wakeLock.request('screen').then(function (lock) {
          wakeLock = lock;
          lock.addEventListener('release', function () { wakeLock = null; });
        }).catch(function () { /* not allowed: fine */ });
      }
    } catch (e) { /* ignore */ }
  }

  function releaseWakeLock() {
    try { if (wakeLock) { wakeLock.release(); wakeLock = null; } } catch (e) { /* ignore */ }
  }
})();
