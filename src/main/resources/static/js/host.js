(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };

  function store(key, val) {
    try {
      if (val === null) sessionStorage.removeItem(key); else sessionStorage.setItem(key, val);
    } catch (e) { /* ignore */ }
  }
  function load(key) {
    try { return sessionStorage.getItem(key); } catch (e) { return null; }
  }

  var hostKey = load('hostKey');
  var client = null;
  var pingTimer = null;
  var wakeLock = null;
  var lastQueueLength = 0;
  var audioCtx = null;
  var currentCode = null;

  // ------------------------------------------------------------------- login

  $('loginForm').addEventListener('submit', function (ev) {
    ev.preventDefault();
    tryLogin($('hostKeyInput').value);
  });

  function tryLogin(key) {
    $('loginError').textContent = '';
    fetch('/api/host/join-code', { headers: { 'X-Host-Key': key } })
      .then(function (res) {
        if (res.status === 401) throw new Error('Wrong host key.');
        if (!res.ok) throw new Error('Server error (' + res.status + ').');
        return res.json();
      })
      .then(function (body) {
        hostKey = key;
        store('hostKey', key);
        startDashboard(body.joinCode || null);
      })
      .catch(function (err) {
        hostKey = null;
        store('hostKey', null);
        $('screen-login').classList.remove('hidden');
        $('screen-dash').classList.add('hidden');
        $('loginError').textContent = err.message === 'Failed to fetch'
          ? 'Cannot reach the server (it may still be waking up - try again in a moment).' : err.message;
      });
  }

  if (hostKey) tryLogin(hostKey); else $('screen-login').classList.remove('hidden');

  // --------------------------------------------------------------- dashboard

  function startDashboard(code) {
    $('screen-login').classList.add('hidden');
    $('screen-dash').classList.remove('hidden');
    showCode(code);
    connect();
    startKeepAlive();
    requestWakeLock();
    document.addEventListener('visibilitychange', function () {
      if (document.visibilityState === 'visible' && hostKey) requestWakeLock();
    });
  }

  function connect() {
    if (client) return;
    var proto = window.location.protocol === 'https:' ? 'wss://' : 'ws://';
    client = new StompJs.Client({
      brokerURL: proto + window.location.host + '/ws-buzzer',
      connectHeaders: { hostKey: hostKey },
      reconnectDelay: 2000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000
    });
    client.onConnect = function () {
      setConn('Connected', 'live');
      client.subscribe('/topic/state', function (m) { render(JSON.parse(m.body)); });
      client.subscribe('/app/state', function (m) { render(JSON.parse(m.body)); });
    };
    client.onWebSocketClose = function () { if (hostKey) setConn('Reconnecting…', 'locked'); };
    client.onStompError = function () { setConn('Connection refused', 'locked'); };
    client.activate();
  }

  function send(dest, body) {
    if (!client || !client.connected) return;
    client.publish(body ? { destination: dest, body: JSON.stringify(body) } : { destination: dest });
  }

  $('newRoundBtn').addEventListener('click', function () { initAudio(); send('/app/host/new-round'); });
  $('lockBtn').addEventListener('click', function () { send('/app/host/lock'); });
  $('endBtn').addEventListener('click', function () {
    if (confirm('End the game and log everyone out? The current QR code will stop working.')) {
      send('/app/host/end-game');
    }
  });

  $('rotateBtn').addEventListener('click', function () { hostPost('/api/host/rotate-code'); });
  $('newGameBtn').addEventListener('click', function () { hostPost('/api/host/new-game'); });

  function hostPost(url) {
    fetch(url, { method: 'POST', headers: { 'X-Host-Key': hostKey } })
      .then(function (r) { return r.ok ? r.json() : Promise.reject(new Error('failed')); })
      .then(function (b) { showCode(b.joinCode); })
      .catch(function () { alert('Request failed. Check your connection.'); });
  }

  $('logoutBtn').addEventListener('click', function () {
    hostKey = null;
    store('hostKey', null);
    if (client) { client.deactivate(); client = null; }
    stopKeepAlive();
    releaseWakeLock();
    $('screen-dash').classList.add('hidden');
    $('hostKeyInput').value = '';
    $('screen-login').classList.remove('hidden');
  });

  // ------------------------------------------------------------------ render

  function render(state) {
    $('roundStatus').textContent = state.sessionEnded ? 'Game ended' : (state.roundActive ? 'Buzzers open' : 'Buzzers locked');
    $('roundStatus').className = 'status ' + (state.sessionEnded ? '' : (state.roundActive ? 'live' : 'locked'));

    if (state.sessionEnded) currentCode = null;
    $('qrCard').classList.toggle('hidden', state.sessionEnded);
    $('endedCard').classList.toggle('hidden', !state.sessionEnded);
    $('newRoundBtn').disabled = state.sessionEnded;
    $('lockBtn').disabled = state.sessionEnded || !state.roundActive;
    $('endBtn').disabled = state.sessionEnded;

    // queue
    var list = $('queueList');
    list.textContent = '';
    $('queueEmpty').classList.toggle('hidden', state.queue.length > 0);
    state.queue.forEach(function (q, i) {
      var li = document.createElement('li');
      if (i === 0) li.className = 'first';
      var rank = document.createElement('span'); rank.className = 'rank'; rank.textContent = q.rank;
      var name = document.createElement('span'); name.className = 'name'; name.textContent = q.playerName;
      var delta = document.createElement('span'); delta.className = 'delta';
      delta.textContent = i === 0 ? 'first' : '+' + q.deltaMs.toFixed(1) + ' ms';
      li.appendChild(rank); li.appendChild(name); li.appendChild(delta);
      list.appendChild(li);
    });
    if (lastQueueLength === 0 && state.queue.length > 0) beep();
    lastQueueLength = state.queue.length;

    // players
    var pl = $('playerList');
    pl.textContent = '';
    $('playerCount').textContent = state.players.length;
    $('playerEmpty').classList.toggle('hidden', state.players.length > 0);
    state.players.forEach(function (p) {
      var chip = document.createElement('span'); chip.className = 'chip';
      var n = document.createElement('span'); n.textContent = p.playerName;
      var kick = document.createElement('button'); kick.textContent = '✕';
      kick.title = 'Remove ' + p.playerName;
      kick.setAttribute('aria-label', 'Remove ' + p.playerName);
      kick.addEventListener('click', function () {
        if (confirm('Remove ' + p.playerName + ' from the game?')) send('/app/host/kick', { playerId: p.playerId });
      });
      chip.appendChild(n); chip.appendChild(kick);
      pl.appendChild(chip);
    });

    // if the game was restarted from another tab, make sure we show the new code
    if (!state.sessionEnded && !currentCode) refreshCode();
  }

  function refreshCode() {
    fetch('/api/host/join-code', { headers: { 'X-Host-Key': hostKey } })
      .then(function (r) { return r.ok ? r.json() : {}; })
      .then(function (b) { if (b.joinCode) showCode(b.joinCode); })
      .catch(function () { /* ignore */ });
  }

  function showCode(code) {
    currentCode = code;
    var box = $('qrBox');
    box.textContent = '';
    if (!code) {
      $('codeText').textContent = '------';
      $('joinUrl').textContent = '';
      return;
    }
    var url = window.location.origin + '/?code=' + encodeURIComponent(code);
    var qr = qrcode(0, 'M');
    qr.addData(url);
    qr.make();
    box.innerHTML = qr.createSvgTag({ cellSize: 6, margin: 0, scalable: true }); // library-generated SVG only
    $('codeText').textContent = code;
    $('joinUrl').textContent = url;
  }

  function setConn(text, cls) {
    var el = $('connStatus');
    el.textContent = text;
    el.className = 'status' + (cls ? ' ' + cls : '');
  }

  // --------------------------------------------------------------- keep-alive

  function startKeepAlive() {
    stopKeepAlive();
    var ping = function () {
      fetch('/api/ping', { cache: 'no-store' })
        .then(function () { $('keepAlive').textContent = 'Keep-alive: on'; $('keepAlive').className = 'status live'; })
        .catch(function () { $('keepAlive').textContent = 'Keep-alive: failing'; $('keepAlive').className = 'status locked'; });
    };
    ping();
    pingTimer = setInterval(ping, 4 * 60 * 1000); // Render sleeps free instances after ~15 min idle
  }

  function stopKeepAlive() {
    if (pingTimer) clearInterval(pingTimer);
    pingTimer = null;
    $('keepAlive').textContent = 'Keep-alive: off';
    $('keepAlive').className = 'status';
  }

  function requestWakeLock() {
    try {
      if ('wakeLock' in navigator && !wakeLock) {
        navigator.wakeLock.request('screen').then(function (lock) {
          wakeLock = lock;
          lock.addEventListener('release', function () { wakeLock = null; });
        }).catch(function () { /* ignore */ });
      }
    } catch (e) { /* ignore */ }
  }

  function releaseWakeLock() {
    try { if (wakeLock) { wakeLock.release(); wakeLock = null; } } catch (e) { /* ignore */ }
  }

  // -------------------------------------------------------------------- sound

  function initAudio() {
    try {
      if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      if (audioCtx.state === 'suspended') audioCtx.resume();
    } catch (e) { /* ignore */ }
  }

  function beep() {
    if (!audioCtx) return;
    try {
      var osc = audioCtx.createOscillator();
      var gain = audioCtx.createGain();
      osc.type = 'square';
      osc.frequency.value = 880;
      gain.gain.setValueAtTime(0.15, audioCtx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.0001, audioCtx.currentTime + 0.3);
      osc.connect(gain); gain.connect(audioCtx.destination);
      osc.start(); osc.stop(audioCtx.currentTime + 0.3);
    } catch (e) { /* ignore */ }
  }
})();
