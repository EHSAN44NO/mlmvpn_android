// MLMVPN — MAE egress Worker (v1). Written from scratch for MAE; shares no code with other workers.
//
// One job: accept VLESS over WebSocket from this user's MLMVPN app and dial the requested TCP
// destination with connect(). It is a CANDIDATE foreign exit only: the app proves, per service,
// that the exit is abroad and that the service accepts it before routing anything here.
//
// Limits it does not hide:
//  - TCP only (no UDP, so QUIC falls back to TCP in the app);
//  - connect() cannot reach Cloudflare-hosted destinations, so such services will fail their
//    acceptance probe here and the app will use another exit;
//  - every connection costs a Worker request from the user's free quota.
//
// Anything that is not the WebSocket upgrade with the right UUID gets a plain 404.

import { connect } from 'cloudflare:sockets';

const VERSION = 1;

export default {
  async fetch(request, env) {
    const uuid = (env.MAE_UUID || '').toLowerCase();
    if (!uuid || request.headers.get('Upgrade') !== 'websocket') {
      return new Response('Not found', { status: 404 });
    }
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    server.accept();
    handle(server, uuid, request.headers.get('sec-websocket-protocol') || '').catch(() => safeClose(server));
    return new Response(null, { status: 101, webSocket: client, headers: { 'x-mae-version': String(VERSION) } });
  },
};

function hexOf(bytes) {
  let s = '';
  for (const b of bytes) s += b.toString(16).padStart(2, '0');
  return s;
}

function uuidOf(bytes) {
  const h = hexOf(bytes);
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

function toBytes(data) {
  if (data instanceof ArrayBuffer) return new Uint8Array(data);
  if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  if (typeof data === 'string') return new TextEncoder().encode(data);
  return new Uint8Array(0);
}

// Early data may arrive base64url-encoded in Sec-WebSocket-Protocol (0-RTT clients).
function earlyData(header) {
  if (!header) return null;
  try {
    const b64 = header.replace(/-/g, '+').replace(/_/g, '/');
    const bin = atob(b64);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  } catch (_) {
    return null;
  }
}

// VLESS request: ver(1) uuid(16) addonsLen(1) addons cmd(1) port(2) atyp(1) addr payload...
function parseHeader(buf, uuid) {
  if (buf.length < 24) throw new Error('short');
  if (uuidOf(buf.slice(1, 17)) !== uuid) throw new Error('auth');
  let i = 17;
  const addons = buf[i++];
  i += addons;
  const cmd = buf[i++];
  if (cmd !== 1) throw new Error('tcp only');
  const port = (buf[i] << 8) | buf[i + 1];
  i += 2;
  const atyp = buf[i++];
  let host;
  if (atyp === 1) {
    host = `${buf[i]}.${buf[i + 1]}.${buf[i + 2]}.${buf[i + 3]}`;
    i += 4;
  } else if (atyp === 2) {
    const n = buf[i++];
    host = new TextDecoder().decode(buf.slice(i, i + n));
    i += n;
  } else if (atyp === 3) {
    const parts = [];
    for (let k = 0; k < 8; k++) parts.push(((buf[i + 2 * k] << 8) | buf[i + 2 * k + 1]).toString(16));
    host = `[${parts.join(':')}]`;
    i += 16;
  } else {
    throw new Error('atyp');
  }
  return { version: buf[0], host, port, rest: buf.slice(i) };
}

async function handle(ws, uuid, protocolHeader) {
  let socket = null;
  let writer = null;
  let first = true;
  const queue = [];
  const early = earlyData(protocolHeader);

  const onChunk = async (bytes) => {
    if (first) {
      first = false;
      const req = parseHeader(bytes, uuid);
      if (req.port === 25) throw new Error('port');
      socket = connect({ hostname: req.host, port: req.port });
      writer = socket.writable.getWriter();
      if (req.rest.length) await writer.write(req.rest);
      for (const q of queue.splice(0)) await writer.write(q);
      pipeBack(socket, ws, req.version);
      return;
    }
    if (writer) await writer.write(bytes);
    else queue.push(bytes);
  };

  let chain = Promise.resolve();
  const enqueue = (bytes) => {
    chain = chain.then(() => onChunk(bytes)).catch(() => {
      safeClose(ws);
      try { socket && socket.close(); } catch (_) {}
    });
  };

  if (early && early.length) enqueue(early);
  ws.addEventListener('message', (e) => enqueue(toBytes(e.data)));
  ws.addEventListener('close', () => { try { socket && socket.close(); } catch (_) {} });
  ws.addEventListener('error', () => { try { socket && socket.close(); } catch (_) {} });
}

async function pipeBack(socket, ws, version) {
  let header = new Uint8Array([version, 0]);
  const reader = socket.readable.getReader();
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      if (ws.readyState !== 1) break;
      if (header) {
        const out = new Uint8Array(header.length + value.length);
        out.set(header, 0);
        out.set(value, header.length);
        ws.send(out);
        header = null;
      } else {
        ws.send(value);
      }
    }
  } catch (_) {
    // destination closed or refused (e.g. a Cloudflare IP): the app sees the stream end.
  } finally {
    safeClose(ws);
  }
}

function safeClose(ws) {
  try { if (ws.readyState === 1 || ws.readyState === 2) ws.close(1000); } catch (_) {}
}
