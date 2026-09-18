const http = require("http");
const crypto = require("crypto");
const WebSocket = require("ws");

const pairsById = new Map();
const pairByDevice = new Map();
const rand = () => crypto.randomBytes(32).toString("base64url");

function send(ws, data) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(data));
}
function broadcast(pair, data) {
  for (const ws of pair.clients.values()) send(ws, data);
}
function partnerRole(role) { return role === "A" ? "B" : "A"; }
function getWs(pair, role) {
  for (const [token, ws] of pair.clients.entries()) if (pair.roles.get(token) === role) return ws;
  return null;
}
function publicAlarm(pair) {
  if (!pair.alarm) return null;
  return {
    targetAt: pair.alarm.targetAt,
    active: pair.alarm.active,
    stoppedA: pair.alarm.stoppedA,
    stoppedB: pair.alarm.stoppedB
  };
}
function publicState(pair) {
  return {
    status: pair.status,
    alarm: publicAlarm(pair),
    goals: pair.goals,
    wakeResponses: pair.wakeResponses,
    wakeCheckDeadlineAt: pair.wakeCheckDeadlineAt,
    connected: pair.clients.size === 2,
    pendingManualWake: pair.pendingManualWake
  };
}
function authed(ws) {
  if (!ws.pairId || !ws.clientToken) return null;
  const pair = pairsById.get(ws.pairId);
  return pair && pair.roles.has(ws.clientToken) ? pair : null;
}
function clearTimer(pair) {
  if (pair.timer) clearTimeout(pair.timer);
  pair.timer = null;
}
function startWakeCheck(pair) {
  clearTimer(pair);
  pair.status = "WAKE_CHECK_ACTIVE";
  pair.wakeResponses = { A: false, B: false };
  pair.wakeCheckDeadlineAt = Date.now() + 30000;
  broadcast(pair, { type: "wakeCheck", deadlineAt: pair.wakeCheckDeadlineAt, state: publicState(pair) });
  pair.timer = setTimeout(() => wakeCheckTimeout(pair), 30000);
}
function scheduleWakeCheck(pair) {
  clearTimer(pair);
  pair.status = "WAITING_5_MIN";
  pair.wakeCheckDeadlineAt = Date.now() + 300000;
  broadcast(pair, { type: "state", state: publicState(pair) });
  pair.timer = setTimeout(() => startWakeCheck(pair), 300000);
}
function wakeCheckTimeout(pair) {
  if (pair.status !== "WAKE_CHECK_ACTIVE") return;
  startWakeCheck(pair); // server-authoritative repeat every 30 seconds until both respond
}
function completeIfReady(pair) {
  if (pair.wakeResponses.A && pair.wakeResponses.B) {
    clearTimer(pair);
    pair.status = "COMPLETED";
    pair.wakeCheckDeadlineAt = 0;
    broadcast(pair, { type: "sessionComplete", state: publicState(pair) });
    return true;
  }
  return false;
}

const server = http.createServer((req, res) => {
  res.writeHead(200, { "Content-Type": "application/json" });
  res.end(JSON.stringify({ ok: true, service: "AlarmPair v3", pairs: pairsById.size }));
});
const wss = new WebSocket.Server({ server });

wss.on("connection", ws => {
  ws.clientToken = null; ws.pairId = null; ws.role = null;

  ws.on("message", raw => {
    let m; try { m = JSON.parse(raw.toString()); } catch { return; }

    if (m.type === "createPair") {
      if (ws.pairId) return;
      const pairId = rand(), deviceToken = rand(), invite = rand();
      const pair = {
        invite, clients: new Map([[deviceToken, ws]]), roles: new Map([[deviceToken, "A"]]),
        alarm: null, status: "PAIRED", goals: { A: null, B: null },
        wakeResponses: { A: false, B: false }, wakeCheckDeadlineAt: 0,
        pendingManualWake: { A: null, B: null }, timer: null
      };
      pairsById.set(pairId, pair); pairByDevice.set(deviceToken, pairId);
      ws.clientToken = deviceToken; ws.pairId = pairId; ws.role = "A";
      send(ws, { type: "paired", role: "A", deviceToken, invite, state: publicState(pair) });
      return;
    }

    if (m.type === "joinPair") {
      if (ws.pairId) return;
      let pairId = null, pair = null;
      for (const [id, p] of pairsById) {
        if (p.invite && p.invite === m.invite && p.clients.size === 1) { pairId = id; pair = p; break; }
      }
      if (!pair) { send(ws, { type: "error", message: "Invite invalid or already used." }); return; }
      const deviceToken = rand();
      pair.clients.set(deviceToken, ws); pair.roles.set(deviceToken, "B"); pair.invite = null;
      pairByDevice.set(deviceToken, pairId);
      ws.clientToken = deviceToken; ws.pairId = pairId; ws.role = "B";
      send(ws, { type: "paired", role: "B", deviceToken, state: publicState(pair) });
      broadcast(pair, { type: "pairStatus", connected: true, state: publicState(pair) });
      return;
    }

    if (m.type === "resume") {
      const token = String(m.deviceToken || "");
      const pairId = pairByDevice.get(token), pair = pairId && pairsById.get(pairId);
      if (!pair || !pair.roles.has(token)) { send(ws, { type: "error", message: "Saved pairing not recognized. Please create or join a new pair." }); return; }
      ws.clientToken = token; ws.pairId = pairId; ws.role = pair.roles.get(token); pair.clients.set(token, ws);
      const pending = pair.pendingManualWake[ws.role];
      if (pending) pair.pendingManualWake[ws.role] = null;
      send(ws, { type: "resumed", role: ws.role, state: publicState(pair) });
      if (pending) send(ws, { type: "manualWake", triggeredBy: pending.triggeredBy, eventId: pending.eventId });
      broadcast(pair, { type: "pairStatus", connected: pair.clients.size === 2, state: publicState(pair) });
      return;
    }

    const pair = authed(ws);
    if (!pair) { send(ws, { type: "error", message: "Authenticate first." }); return; }

    if (m.type === "sync") {
      send(ws, { type: "sync", serverNow: Date.now(), state: publicState(pair) });
      return;
    }

    if (m.type === "setAlarm") {
      const targetAt = Number(m.targetAt);
      if (!Number.isFinite(targetAt) || targetAt <= Date.now() + 1000) { send(ws, { type: "error", message: "Invalid alarm time." }); return; }
      clearTimer(pair);
      pair.alarm = { targetAt, active: true, stoppedA: false, stoppedB: false };
      pair.status = "SCHEDULED";
      pair.goals = { A: null, B: null };
      pair.wakeResponses = { A: false, B: false };
      pair.wakeCheckDeadlineAt = 0;
      pair.pendingManualWake = { A: null, B: null };
      broadcast(pair, { type: "alarm", alarm: publicAlarm(pair), state: publicState(pair) });
      pair.timer = setTimeout(() => {
        if (!pair.alarm || pair.alarm.targetAt !== targetAt) return;
        pair.status = "RINGING";
        broadcast(pair, { type: "alarmRinging", alarm: publicAlarm(pair), state: publicState(pair) });
      }, Math.max(0, targetAt - Date.now()));
      return;
    }

    if (m.type === "stop") {
      if (!pair.alarm || !pair.alarm.active || pair.status === "COMPLETED") return;
      if (ws.role === "A") pair.alarm.stoppedA = true; else pair.alarm.stoppedB = true;
      if (pair.alarm.stoppedA && pair.alarm.stoppedB) {
        pair.alarm.active = false; pair.status = "AWAITING_GOALS";
        clearTimer(pair); pair.wakeCheckDeadlineAt = 0;
      }
      broadcast(pair, { type: "alarm", alarm: publicAlarm(pair), state: publicState(pair) });
      return;
    }

    if (m.type === "submitGoals") {
      if (pair.status !== "AWAITING_GOALS" && pair.status !== "GOALS") return;
      const text = String(m.text || "").trim().slice(0, 2000);
      if (!text) { send(ws, { type: "error", message: "Goals cannot be empty." }); return; }
      pair.goals[ws.role] = text; pair.status = "GOALS";
      broadcast(pair, { type: "goals", text, from: ws.role, state: publicState(pair) });
      if (pair.goals.A && pair.goals.B) scheduleWakeCheck(pair);
      return;
    }

    if (m.type === "confirmHere") {
      if (pair.status !== "WAKE_CHECK_ACTIVE") return;
      pair.wakeResponses[ws.role] = true;
      broadcast(pair, { type: "hereConfirmed", from: ws.role, state: publicState(pair) });
      completeIfReady(pair);
      return;
    }

    if (m.type === "manualWake") {
      const other = partnerRole(ws.role);
      const event = { triggeredBy: `Device ${ws.role}`, eventId: rand() };
      const partner = getWs(pair, other);
      if (partner) send(partner, { type: "manualWake", ...event });
      else pair.pendingManualWake[other] = event;
      send(ws, { type: "manualWakeSent", to: other, queued: !partner });
      return;
    }
  });

  ws.on("close", () => {
    const pair = ws.pairId && pairsById.get(ws.pairId);
    if (!pair || !ws.clientToken) return;
    if (pair.clients.get(ws.clientToken) === ws) pair.clients.delete(ws.clientToken);
    broadcast(pair, { type: "pairStatus", connected: pair.clients.size === 2, state: publicState(pair) });
  });
});

const PORT = process.env.PORT || 8080;
server.listen(PORT, "0.0.0.0", () => console.log(`AlarmPair v3 server listening on ${PORT}`));
