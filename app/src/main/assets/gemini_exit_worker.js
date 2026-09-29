/**
 * The Gemini exit: Google's AI services, reached from North America.
 *
 * Gemini decides "not available in your country" by where Google believes a request comes from.
 * A Worker leaves the internet from the data centre that ran it, which is the one nearest the
 * phone, and from Iran that is a European one whose shared exit addresses Google places in
 * Russia. Measured 2026-09-25 through a live BPB worker: Google's homepage said "Russia" and
 * Gemini's page carried Moscow time, although Cloudflare's own geofeed and ipinfo both put the
 * exit (104.28.164.x) in Sweden. The same worker, reached from a US line, got the full Gemini
 * page. So a different worker does not help; running somewhere else does.
 *
 * A Durable Object can be told where to live (`locationHint`). This Worker hands every tunnel
 * connection to one created in Eastern North America, and the Object dials Google from there,
 * so Google sees a US address whichever data centre the phone reached.
 *
 * The tunnel is VLESS over WebSocket, which every Xray core already speaks: TCP only, ports 80
 * and 443 only. Names are resolved here, IPv4 only. connect() given a name leaves by
 * Cloudflare's IPv6 exit, which Google answers with its "unusual traffic" page.
 *
 * Bindings (set by the app's deployer):
 *   EXIT       Durable Object namespace, class GeminiExit
 *   EXIT_ID    the VLESS user id (secret)
 *   EXIT_PATH  the WebSocket path, without the leading slash
 *   EXIT_HINT  where the Object lives; "enam" unless set
 *
 * GET /<EXIT_PATH>/probe answers, from the Object, where it runs and whether Gemini would serve
 * it: {hint, colo, loc, gemini: {status, tzOffsetMin, blocked}}.
 */
import { connect } from 'cloudflare:sockets';

const DEFAULT_HINT = 'enam';
const PORTS = new Set([80, 443]);
const OPEN = 1; // WebSocket.OPEN
const UA =
  'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36';

export default {
  async fetch(request, env) {
    const { pathname } = new URL(request.url);
    const base = env.EXIT_PATH ? '/' + env.EXIT_PATH : null;
    if (!base || !env.EXIT || !validId(env.EXIT_ID) || (pathname !== base && pathname !== base + '/probe')) {
      return new Response('Not found', { status: 404 });
    }
    const hint = env.EXIT_HINT || DEFAULT_HINT;
    // One Object per hint. The hint only counts when the Object is first created, so the name
    // carries it: a later change of region makes a new Object instead of silently keeping the old.
    const stub = env.EXIT.get(env.EXIT.idFromName('exit-' + hint), { locationHint: hint });
    return stub.fetch(request);
  },
};

export class GeminiExit {
  constructor(state, env) {
    this.env = env;
  }

  async fetch(request) {
    if (new URL(request.url).pathname.endsWith('/probe')) return probe(this.env);
    if ((request.headers.get('Upgrade') || '').toLowerCase() !== 'websocket') {
      return new Response('Not found', { status: 404 });
    }
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.accept();
    serve(server, uuidBytes(this.env.EXIT_ID), earlyData(request.headers.get('Sec-WebSocket-Protocol')));
    return new Response(null, { status: 101, webSocket: client });
  }
}

/**
 * One tunnel connection: the first message names the destination, the rest is the stream.
 *
 * Messages are handled strictly in order on one promise chain. The first one has to wait for a DNS
 * answer and a connect before its payload can go anywhere, and anything the client sends in that
 * time must queue behind it rather than race it onto a socket that does not exist yet.
 */
function serve(ws, id, early, dial = connect) {
  let socket = null;
  let writer = null;
  let chain = Promise.resolve();
  let first = true;
  let closed = false;

  const shut = () => {
    if (closed) return;
    closed = true;
    try { ws.close(1000, 'done'); } catch (_) {}
    try { if (socket) socket.close(); } catch (_) {}
  };

  const take = (chunk) => {
    chain = chain
      .then(async () => {
        if (closed) return;
        if (first) {
          first = false;
          const req = parseRequest(chunk, id);
          const host = await ipv4Of(req);
          socket = dial({ hostname: host, port: req.port });
          writer = socket.writable.getWriter();
          pump(socket, ws, req.version).then(shut, shut);
          // A copy, never a view: a socket writer may send a view's whole underlying buffer, which
          // put the VLESS header in front of every first packet (Google and Apache both answered
          // "400 Bad Request"; a TLS ClientHello simply failed).
          if (req.payload.byteLength) await writer.write(req.payload.slice());
          return;
        }
        await writer.write(chunk.slice());
      })
      .catch(shut);
  };

  ws.addEventListener('message', (event) => take(bytes(event.data)));
  ws.addEventListener('close', shut);
  ws.addEventListener('error', shut);
  if (early && early.byteLength) take(early);
}

/** Destination to client. The first chunk carries VLESS's two-byte response header. */
async function pump(socket, ws, version) {
  const reader = socket.readable.getReader();
  let head = new Uint8Array([version, 0]);
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done || ws.readyState !== OPEN) return;
      if (head) {
        const out = new Uint8Array(head.length + value.byteLength);
        out.set(head, 0);
        out.set(value, head.length);
        ws.send(out);
        head = null;
      } else {
        ws.send(value);
      }
    }
  } finally {
    try { reader.releaseLock(); } catch (_) {}
  }
}

/**
 * The VLESS request header: version, user id, add-ons, command, port, address -- then payload.
 * Throws on anything this exit does not serve, which closes the connection.
 */
function parseRequest(b, id) {
  const need = (n) => {
    if (b.length < n) throw new Error('short VLESS header');
  };
  need(18);
  const version = b[0];
  for (let i = 0; i < 16; i++) if (b[1 + i] !== id[i]) throw new Error('unknown user');
  let p = 18 + b[17];
  need(p + 4);
  const command = b[p];
  if (command !== 1) throw new Error('TCP only');
  const port = (b[p + 1] << 8) | b[p + 2];
  if (!PORTS.has(port)) throw new Error('port ' + port + ' is not served');
  const type = b[p + 3];
  p += 4;
  let address;
  if (type === 1) {
    need(p + 4);
    address = `${b[p]}.${b[p + 1]}.${b[p + 2]}.${b[p + 3]}`;
    p += 4;
  } else if (type === 2) {
    need(p + 1);
    const len = b[p];
    need(p + 1 + len);
    address = new TextDecoder().decode(b.subarray(p + 1, p + 1 + len));
    p += 1 + len;
  } else if (type === 3) {
    need(p + 16);
    const parts = [];
    for (let i = 0; i < 16; i += 2) parts.push(((b[p + i] << 8) | b[p + i + 1]).toString(16));
    address = parts.join(':');
    p += 16;
  } else {
    throw new Error('address type ' + type);
  }
  return { version, command, port, type, address, payload: b.subarray(p) };
}

/**
 * An IPv4 address for the destination. Asked of Cloudflare's resolver from HERE, so a name gets
 * the Google front end nearest this Object rather than one near the phone.
 */
async function ipv4Of(req, lookup = fetch) {
  if (req.type === 1) return req.address;
  if (req.type !== 2) throw new Error('IPv6 is not served: Google refuses this exit over IPv6');
  const res = await lookup(
    'https://cloudflare-dns.com/dns-query?type=A&name=' + encodeURIComponent(req.address),
    { headers: { accept: 'application/dns-json' } },
  );
  const answer = ((await res.json()).Answer || []).find((a) => a.type === 1);
  if (!answer) throw new Error('no IPv4 address for ' + req.address);
  return answer.data;
}

/** Where this Object runs, and whether Gemini would serve it from here. */
async function probe(env) {
  const out = { hint: env.EXIT_HINT || DEFAULT_HINT };
  try {
    const trace = await (await fetch('https://www.cloudflare.com/cdn-cgi/trace')).text();
    for (const line of trace.split('\n')) {
      const [key, value] = line.split('=');
      if (key === 'colo' || key === 'loc') out[key] = value;
    }
  } catch (e) {
    out.traceError = String((e && e.message) || e);
  }
  try {
    out.gemini = await geminiFromHere();
  } catch (e) {
    out.geminiError = String((e && e.message) || e);
  }
  return new Response(JSON.stringify(out), {
    headers: { 'content-type': 'application/json', 'cache-control': 'no-store' },
  });
}

/**
 * Gemini's own page, fetched the way the tunnel reaches it: IPv4 address, real server name. The
 * page carries Google's verdict as settings -- "rtQCxc" is the time-zone offset of the place it
 * believes the visitor is in, and "FL1an" / "MuJWjd" are set where Gemini is refused.
 */
async function geminiFromHere() {
  const host = 'gemini.google.com';
  const ip = await ipv4Of({ type: 2, address: host });
  const socket = connect({ hostname: ip, port: 443 }, { secureTransport: 'starttls' });
  const tls = socket.startTls({ expectedServerHostname: host });
  const writer = tls.writable.getWriter();
  await writer.write(
    new TextEncoder().encode(
      `GET /app HTTP/1.1\r\nHost: ${host}\r\nUser-Agent: ${UA}\r\n` +
        'Accept-Language: en-US,en;q=0.9\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n',
    ),
  );
  const reader = tls.readable.getReader();
  const decoder = new TextDecoder();
  let text = '';
  const until = Date.now() + 8000;
  while (Date.now() < until && text.length < 3000000) {
    const { value, done } = await reader.read();
    if (done) break;
    text += decoder.decode(value, { stream: true });
    if (text.includes('"rtQCxc":') && text.includes('"FL1an":') && text.includes('"MuJWjd":')) break;
  }
  try { tls.close(); } catch (_) {}
  const flag = (key) => (text.match(new RegExp('"' + key + '":(true|false|-?\\d+)')) || [])[1];
  const tz = flag('rtQCxc');
  return {
    ip,
    status: Number((text.match(/^HTTP\/1\.1 (\d{3})/) || [])[1] || 0),
    tzOffsetMin: tz === undefined ? null : Number(tz),
    blocked: flag('FL1an') === 'true' || flag('MuJWjd') === 'true',
  };
}

function validId(id) {
  return /^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}$/.test(String(id || ''));
}

function uuidBytes(id) {
  const hex = String(id).replace(/-/g, '');
  const out = new Uint8Array(16);
  for (let i = 0; i < 16; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
  return out;
}

/** Xray's early data: the first bytes, base64url, in Sec-WebSocket-Protocol. */
function earlyData(header) {
  if (!header) return null;
  try {
    const bin = atob(header.replace(/-/g, '+').replace(/_/g, '/'));
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  } catch (_) {
    return null;
  }
}

function bytes(data) {
  if (data instanceof ArrayBuffer) return new Uint8Array(data);
  if (typeof data === 'string') return new TextEncoder().encode(data);
  return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
}
