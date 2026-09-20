const test = require("node:test");
const assert = require("node:assert/strict");
const WebSocket = require("ws");

const originalEnv = process.env.PORT;
process.env.PORT = "0";
process.env.NODE_ENV = "test";
delete require.cache[require.resolve("./server.js")];
const { server, startServer } = require("./server.js");

async function connect(port) {
  const socket = new WebSocket(`ws://127.0.0.1:${port}`);
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error("connect timeout")), 5000);
    socket.once("open", () => { clearTimeout(timeout); resolve(); });
    socket.once("error", (err) => { clearTimeout(timeout); reject(err); });
  });
  return socket;
}

async function waitForMessage(socket, condition, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("message timeout")), timeoutMs);
    const onMessage = (raw) => {
      try {
        const msg = JSON.parse(raw.toString());
        if (condition(msg)) {
          clearTimeout(timer);
          socket.off("message", onMessage);
          resolve(msg);
        }
      } catch (_) {}
    };
    socket.on("message", onMessage);
  });
}

async function closeSocket(socket) {
  if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) {
    await new Promise((resolve) => {
      socket.once("close", resolve);
      socket.close();
    });
  }
}

test.before(async () => {
  await startServer(0);
});

test.after(async () => {
  await new Promise((resolve, reject) => {
    server.close((err) => (err ? reject(err) : resolve()));
  });
  if (originalEnv === undefined) delete process.env.PORT; else process.env.PORT = originalEnv;
  delete process.env.NODE_ENV;
});

test("createPair creates a valid event and returns an invite", async () => {
  const port = server.address().port;
  const socket = await connect(port);

  socket.send(JSON.stringify({ type: "createPair" }));
  const message = await waitForMessage(socket, (msg) => msg.type === "paired");

  assert.equal(message.role, "A");
  assert.ok(message.pairId);
  assert.ok(message.deviceToken);
  assert.ok(message.invite || message.inviteCode);

  await closeSocket(socket);
});

test("joinPair can join a valid invite and the pair is marked connected", async () => {
  const port = server.address().port;
  const creator = await connect(port);
  creator.send(JSON.stringify({ type: "createPair" }));
  const created = await waitForMessage(creator, (msg) => msg.type === "paired");
  const invite = created.invite || created.inviteCode;

  const joiner = await connect(port);
  joiner.send(JSON.stringify({ type: "joinPair", invite }));
  const joined = await waitForMessage(joiner, (msg) => msg.type === "paired");
  assert.equal(joined.role, "B");

  const creatorStatus = await waitForMessage(creator, (msg) => msg.type === "pairStatus");
  assert.equal(creatorStatus.connected, true);

  await closeSocket(creator);
  await closeSocket(joiner);
});

test("resume rejects stale device tokens and unauthenticated commands are rejected", async () => {
  const port = server.address().port;
  const socket = await connect(port);
  socket.send(JSON.stringify({ type: "resume", deviceToken: "nope" }));
  const rejected = await waitForMessage(socket, (msg) => msg.type === "error");
  assert.match(rejected.message, /Saved pairing|Authenticate|recogniz/);

  const unauth = await connect(port);
  unauth.send(JSON.stringify({ type: "sync" }));
  const authError = await waitForMessage(unauth, (msg) => msg.type === "error");
  assert.match(authError.message, /Authenticate/i);

  await closeSocket(socket);
  await closeSocket(unauth);
});
