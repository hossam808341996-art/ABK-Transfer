"use strict";
const path = require("path");
const http = require("http");
const express = require("express");
const { WebSocketServer } = require("ws");

const PORT = Number(process.env.PORT) || 3000;
const app = express();
app.disable("x-powered-by");

// Basic response hardening without changing the page's inline styles/scripts.
app.use((_req, res, next) => {
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("X-Frame-Options", "DENY");
  res.setHeader("Referrer-Policy", "no-referrer");
  res.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
  next();
});
app.get("/healthz", (_req, res) => res.type("text").send("ok"));
app.use(express.static(path.join(__dirname, "public"), { extensions: ["html"] }));

const server = http.createServer(app);
const wss = new WebSocketServer({ noServer: true, maxPayload: 1024 * 1024 });

// A page on another domain must not attach to a visitor's ABK session.
// A missing Origin is allowed for command-line health/integration clients.
server.on("upgrade", (req, socket, head) => {
  let permitted = false;
  try {
    const pathname = new URL(req.url, "http://localhost").pathname;
    const origin = req.headers.origin;
    permitted = pathname === "/ws" && !!req.headers.host &&
      (!origin || new URL(origin).host === req.headers.host);
  } catch (_err) { /* invalid URL */ }

  if (!permitted) {
    socket.write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n");
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => wss.emit("connection", ws, req));
});

/* rooms: code -> { host, guest }; rooms are intentionally ephemeral */
const rooms = new Map();
const attempts = new Map(); // ip -> { n, t }

function tooMany(ip) {
  const now = Date.now();
  const a = attempts.get(ip);
  if (!a || now - a.t > 60000) { attempts.set(ip, { n: 1, t: now }); return false; }
  a.n += 1;
  return a.n > 20;
}
setInterval(() => {
  const now = Date.now();
  for (const [ip, a] of attempts) if (now - a.t > 60000) attempts.delete(ip);
}, 60000).unref();

const sendJson = (ws, obj) => { if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj)); };
const peerOf = (ws) => {
  const r = ws.room && rooms.get(ws.room);
  if (!r) return null;
  return ws.role === "host" ? r.guest : r.host;
};

function leave(ws) {
  if (!ws.room) return;
  const code = ws.room;
  const r = rooms.get(code);
  ws.room = null;
  if (!r) return;
  if (ws.role === "host") {
    const g = r.guest;
    rooms.delete(code);
    if (g) { g.room = null; sendJson(g, { type: "peer-left" }); }
  } else {
    r.guest = null;
    sendJson(r.host, { type: "peer-left" });
  }
}

wss.on("connection", (ws, req) => {
  // Render sets the first X-Forwarded-For entry to the real client IP.
  // On other hosts, ignore client-supplied forwarding headers.
  const renderIp = process.env.RENDER_SERVICE_ID &&
    String(req.headers["x-forwarded-for"] || "").split(",")[0].trim();
  const ip = renderIp || req.socket.remoteAddress || "unknown";
  ws.isAlive = true;
  ws.on("pong", () => { ws.isAlive = true; });

  ws.on("message", (data, isBinary) => {
    if (isBinary) { // file chunk in relay mode
      const p = peerOf(ws);
      if (p && p.readyState === 1) p.send(data, { binary: true });
      return;
    }
    let msg;
    try { msg = JSON.parse(data.toString()); } catch { return; }
    if (!msg || typeof msg.type !== "string") return;

    if (msg.type === "heartbeat") return sendJson(ws, { type: "heartbeat-ack" });

    if (msg.type === "host") {
      const code = String(msg.code || "");
      if (!/^\d{3}$/.test(code)) return sendJson(ws, { type: "error", reason: "bad" });
      const r = rooms.get(code);
      if (r && r.host && r.host.readyState === 1) return sendJson(ws, { type: "error", reason: "taken" });
      leave(ws);
      rooms.set(code, { host: ws, guest: null });
      ws.room = code; ws.role = "host";
      return sendJson(ws, { type: "hosted" });
    }

    if (msg.type === "join") {
      if (tooMany(ip)) return sendJson(ws, { type: "error", reason: "rate" });
      const code = String(msg.code || "");
      const r = rooms.get(code);
      if (!/^\d{3}$/.test(code) || !r || !r.host || r.host.readyState !== 1) return sendJson(ws, { type: "error", reason: "nocode" });
      if (r.guest && r.guest.readyState === 1) return sendJson(ws, { type: "error", reason: "busy" });
      leave(ws);
      r.guest = ws; ws.room = code; ws.role = "guest";
      sendJson(ws, { type: "joined" });
      return sendJson(r.host, { type: "peer-joined" });
    }

    if (msg.type === "signal" || msg.type === "relay") {
      const p = peerOf(ws);
      if (p && p.readyState === 1) {
        sendJson(p, msg.type === "signal" ? { type: "signal", data: msg.data } : { type: "relay", data: msg.data });
      }
    }
  });

  ws.on("close", () => leave(ws));
  ws.on("error", () => leave(ws));
});

setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) { ws.terminate(); continue; }
    ws.isAlive = false;
    ws.ping();
  }
}, 30000).unref();

server.listen(PORT, "0.0.0.0", () => console.log("ABK Design listening on " + PORT));