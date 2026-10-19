"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const net = require("node:net");
const { once } = require("node:events");
const { WebSocket } = require("ws");

async function availablePort() {
  const server = net.createServer();
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const port = server.address().port;
  await new Promise((resolve) => server.close(resolve));
  return port;
}

async function eventuallyReady(url, child) {
  for (let i = 0; i < 40; i++) {
    if (child.exitCode !== null) throw new Error("Server exited before starting");
    try {
      const r = await fetch(url);
      if (r.ok) return;
    } catch (_) { /* server is still starting */ }
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error("Health endpoint did not become ready");
}

async function connect(url, origin) {
  const ws = new WebSocket(url, { headers: { Origin: origin } });
  await Promise.race([
    once(ws, "open"),
    new Promise((_, reject) => setTimeout(() => reject(new Error("WebSocket timeout")), 3000))
  ]);
  return ws;
}

function nextJson(ws, matcher) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { cleanup(); reject(new Error("No matching message received")); }, 3000);
    function cleanup() { clearTimeout(timer); ws.off("message", onMessage); ws.off("close", onClose); }
    function onClose() { cleanup(); reject(new Error("Socket closed")); }
    function onMessage(raw, binary) {
      if (binary) return;
      let msg;
      try { msg = JSON.parse(raw.toString()); } catch (_) { return; }
      if (matcher(msg)) { cleanup(); resolve(msg); }
    }
    ws.on("message", onMessage); ws.on("close", onClose);
  });
}

test("ABK deployment: health, HTML, pairing, signaling, relay and origin protection", async (t) => {
  const port = await availablePort();
  const child = spawn(process.execPath, ["server.js"], {
    cwd: require("node:path").resolve(__dirname, ".."),
    env: { ...process.env, PORT: String(port), RENDER_SERVICE_ID: "" },
    stdio: ["ignore", "pipe", "pipe"]
  });
  let errorOutput = "";
  child.stderr.on("data", (d) => { errorOutput += d.toString(); });
  t.after(() => { if (child.exitCode === null) child.kill("SIGTERM"); });

  const base = `http://127.0.0.1:${port}`;
  await eventuallyReady(base + "/healthz", child);
  assert.equal(await (await fetch(base + "/healthz")).text(), "ok");
  const html = await fetch(base + "/");
  assert.equal(html.status, 200, errorOutput);
  assert.match(await html.text(), /ABK Design/);
  assert.equal(html.headers.get("x-content-type-options"), "nosniff");

  const wsUrl = `ws://127.0.0.1:${port}/ws`;
  await new Promise((resolve, reject) => {
    const wrongOrigin = new WebSocket(wsUrl, { headers: { Origin: "https://invalid.example" } });
    wrongOrigin.on("unexpected-response", (_req, response) => {
      assert.equal(response.statusCode, 403);
      response.resume();
      resolve();
    });
    wrongOrigin.on("error", reject);
  });

  const host = await connect(wsUrl, base);
  const guest = await connect(wsUrl, base);
  t.after(() => { host.terminate(); guest.terminate(); });

  const hosted = nextJson(host, (m) => m.type === "hosted");
  host.send(JSON.stringify({ type: "host", code: "528" }));
  await hosted;
  const joined = nextJson(guest, (m) => m.type === "joined");
  const peerJoined = nextJson(host, (m) => m.type === "peer-joined");
  guest.send(JSON.stringify({ type: "join", code: "528" }));
  await Promise.all([joined, peerJoined]);

  const relay = nextJson(guest, (m) => m.type === "relay");
  host.send(JSON.stringify({ type: "relay", data: JSON.stringify({ t: "chat", text: "hello" }) }));
  assert.equal(JSON.parse((await relay).data).text, "hello");

  const signaling = nextJson(host, (m) => m.type === "signal");
  guest.send(JSON.stringify({ type: "signal", data: { relay: 1 } }));
  assert.equal((await signaling).data.relay, 1);

  const heartbeat = nextJson(host, (m) => m.type === "heartbeat-ack");
  host.send(JSON.stringify({ type: "heartbeat" }));
  await heartbeat;

  const binary = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("No binary chunk")), 3000);
    guest.on("message", function onMessage(data, isBinary) {
      if (!isBinary) return;
      guest.off("message", onMessage);
      clearTimeout(timer);
      resolve(Buffer.from(data));
    });
  });
  host.send(Buffer.from([1, 2, 3]), { binary: true });
  assert.deepEqual(await binary, Buffer.from([1, 2, 3]));
});