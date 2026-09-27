#!/usr/bin/env node
/**
 * Runs the Gemini exit worker (app/src/main/assets/gemini_exit_worker.js) for real, in Node.
 *
 *   node scripts/test-gemini-exit-worker.mjs
 *
 * The worker is imported as it ships, with two changes made to a temporary copy: the
 * `cloudflare:sockets` import is swapped for a fake `connect` (Node cannot resolve it), and the
 * private helpers are exported so they can be called. Everything else -- the VLESS parsing, the
 * ordering of messages behind the first one's DNS lookup, the response header on the first chunk
 * back, the routing to the Durable Object -- runs unchanged.
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';

const ROOT = path.resolve(import.meta.dirname, '..');
const WORKER = path.join(ROOT, 'app/src/main/assets/gemini_exit_worker.js');

const IMPORT = "import { connect } from 'cloudflare:sockets';";
const source = fs.readFileSync(WORKER, 'utf8');
assert.ok(source.includes(IMPORT), 'the worker still imports connect from cloudflare:sockets');
const patched =
  source.replace(IMPORT, 'const connect = (...a) => globalThis.__connect(...a);') +
  '\nexport { serve, parseRequest, ipv4Of, uuidBytes, earlyData };\n';
const tmp = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'gx-')), 'worker.mjs');
fs.writeFileSync(tmp, patched);
const mod = await import(pathToFileURL(tmp).href);
const { serve, parseRequest, ipv4Of, uuidBytes, earlyData } = mod;

const ID = '1b671a64-40d5-491e-99b0-da01ff1f3341';
const idBytes = uuidBytes(ID);

/** A VLESS request header for [host]:[port], address type chosen from the shape of host. */
function vless(host, port, payload = new Uint8Array(), { command = 1, id = idBytes } = {}) {
  const parts = [0, ...id, 0, command, port >> 8, port & 255];
  if (/^\d+\.\d+\.\d+\.\d+$/.test(host)) parts.push(1, ...host.split('.').map(Number));
  else if (host.includes(':')) {
    parts.push(3);
    for (const h of host.split(':')) parts.push(parseInt(h, 16) >> 8, parseInt(h, 16) & 255);
  } else {
    const name = new TextEncoder().encode(host);
    parts.push(2, name.length, ...name);
  }
  const head = Uint8Array.from(parts);
  const out = new Uint8Array(head.length + payload.length);
  out.set(head);
  out.set(payload, head.length);
  return out;
}

const text = (s) => new TextEncoder().encode(s);
const tick = () => new Promise((r) => setTimeout(r, 5));

let passed = 0;
async function test(name, fn) {
  try {
    await fn();
    passed++;
    console.log('ok   ' + name);
  } catch (e) {
    console.log('FAIL ' + name + '\n     ' + (e && e.stack ? e.stack.split('\n').slice(0, 3).join('\n     ') : e));
    process.exitCode = 1;
  }
}

/** A fake server-side WebSocket: records what the worker sends and lets a test deliver messages. */
function fakeSocket() {
  const listeners = {};
  const ws = {
    readyState: 1,
    sent: [],
    closed: false,
    addEventListener: (type, fn) => (listeners[type] = listeners[type] || []).push(fn),
    send: (data) => ws.sent.push(Uint8Array.from(data)),
    close: () => {
      ws.closed = true;
      ws.readyState = 3;
    },
    deliver: (data) => (listeners.message || []).forEach((fn) => fn({ data })),
    hangUp: () => (listeners.close || []).forEach((fn) => fn({})),
  };
  return ws;
}

/** A fake TCP socket: collects what is written, and lets a test push data back or end it. */
function fakeTcp() {
  const written = [];
  let push;
  let end;
  const tcp = {
    written,
    closed: false,
    writable: new WritableStream({ write: (chunk) => void written.push(Uint8Array.from(chunk)) }),
    readable: new ReadableStream({
      start(c) {
        push = (d) => c.enqueue(d);
        end = () => c.close();
      },
    }),
    close: () => {
      tcp.closed = true;
    },
    push: (d) => push(d),
    end: () => end(),
  };
  return tcp;
}

/** DNS answers for the resolver the worker asks, by name. */
function withDns(answers, fn) {
  const real = globalThis.fetch;
  globalThis.fetch = async (url) => {
    const name = new URL(url).searchParams.get('name');
    const ip = answers[name];
    return new Response(JSON.stringify({ Answer: ip ? [{ type: 5, data: 'cname.' }, { type: 1, data: ip }] : [] }));
  };
  return Promise.resolve(fn()).finally(() => {
    globalThis.fetch = real;
  });
}

await test('parses a domain request and keeps the payload', () => {
  const r = parseRequest(vless('gemini.google.com', 443, text('hello')), idBytes);
  assert.equal(r.type, 2);
  assert.equal(r.address, 'gemini.google.com');
  assert.equal(r.port, 443);
  assert.equal(new TextDecoder().decode(r.payload), 'hello');
});

await test('parses an IPv4 request', () => {
  const r = parseRequest(vless('142.250.1.2', 80), idBytes);
  assert.equal(r.type, 1);
  assert.equal(r.address, '142.250.1.2');
  assert.equal(r.port, 80);
});

await test('skips add-on bytes', () => {
  const b = vless('142.250.1.2', 443);
  const withAddons = new Uint8Array(b.length + 3);
  withAddons.set(b.subarray(0, 17));
  withAddons[17] = 3; // three add-on bytes follow
  withAddons.set([9, 9, 9], 18);
  withAddons.set(b.subarray(18), 21);
  assert.equal(parseRequest(withAddons, idBytes).address, '142.250.1.2');
});

await test('refuses a stranger, UDP, and ports other than 80/443', () => {
  const other = uuidBytes('00000000-0000-0000-0000-000000000000');
  assert.throws(() => parseRequest(vless('a.com', 443, new Uint8Array(), { id: other }), idBytes), /unknown user/);
  assert.throws(() => parseRequest(vless('a.com', 443, new Uint8Array(), { command: 2 }), idBytes), /TCP only/);
  assert.throws(() => parseRequest(vless('a.com', 22), idBytes), /port 22/);
  assert.throws(() => parseRequest(new Uint8Array(10), idBytes), /short/);
});

await test('never dials IPv6: Google refuses the IPv6 exit', async () => {
  const r = parseRequest(vless('2001:4860:4860:0:0:0:0:8888', 443), idBytes);
  await assert.rejects(ipv4Of(r), /IPv6/);
});

await test('resolves a name to its A record, skipping a CNAME', async () => {
  await withDns({ 'gemini.google.com': '142.251.1.1' }, async () => {
    assert.equal(await ipv4Of({ type: 2, address: 'gemini.google.com' }), '142.251.1.1');
    await assert.rejects(ipv4Of({ type: 2, address: 'nothing.example' }), /no IPv4/);
  });
});

await test('decodes early data from Sec-WebSocket-Protocol (base64url, no padding)', () => {
  const raw = vless('gemini.google.com', 443, text('hi'));
  const header = Buffer.from(raw).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  assert.deepEqual(earlyData(header), raw);
  assert.equal(earlyData(null), null);
});

await test('a whole connection: dial by IPv4, order kept, header on the first reply', async () => {
  await withDns({ 'gemini.google.com': '142.251.1.1' }, async () => {
    const ws = fakeSocket();
    const tcp = fakeTcp();
    let dialled;
    serve(ws, idBytes, null, (to) => ((dialled = to), tcp));
    ws.deliver(vless('gemini.google.com', 443, text('first')).buffer);
    ws.deliver(text('second')); // arrives while the first is still waiting for DNS
    await tick();
    assert.deepEqual(dialled, { hostname: '142.251.1.1', port: 443 });
    assert.deepEqual(tcp.written.map((c) => new TextDecoder().decode(c)), ['first', 'second']);

    tcp.push(text('AB'));
    tcp.push(text('C'));
    await tick();
    assert.deepEqual([...ws.sent[0]], [0, 0, 65, 66], 'VLESS response header, then data');
    assert.deepEqual([...ws.sent[1]], [67]);

    tcp.end();
    await tick();
    assert.ok(ws.closed, 'the client is let go when the destination closes');
  });
});

await test('early data is the first message', async () => {
  await withDns({}, async () => {
    const ws = fakeSocket();
    const tcp = fakeTcp();
    let dialled;
    serve(ws, idBytes, vless('142.250.9.9', 443, text('early')), (to) => ((dialled = to), tcp));
    await tick();
    assert.deepEqual(dialled, { hostname: '142.250.9.9', port: 443 });
    assert.equal(new TextDecoder().decode(tcp.written[0]), 'early');
  });
});

await test('a refused first message closes the connection and dials nothing', async () => {
  const ws = fakeSocket();
  let dialled = false;
  serve(ws, idBytes, null, () => ((dialled = true), fakeTcp()));
  ws.deliver(vless('a.com', 25));
  await tick();
  assert.equal(dialled, false);
  assert.ok(ws.closed);
});

await test('the client hanging up closes the destination', async () => {
  const ws = fakeSocket();
  const tcp = fakeTcp();
  serve(ws, idBytes, vless('142.250.9.9', 443), () => tcp);
  await tick();
  ws.hangUp();
  assert.ok(tcp.closed);
});

await test('the Worker hands only its own path to the Durable Object, created in enam', async () => {
  const calls = [];
  const env = {
    EXIT_ID: ID,
    EXIT_PATH: 'x7Qp',
    EXIT: {
      idFromName: (n) => 'id:' + n,
      get: (id, opts) => ({
        fetch: async (req) => (calls.push({ id, opts, url: req.url }), new Response('to-object')),
      }),
    },
  };
  const handler = mod.default;
  assert.equal((await handler.fetch(new Request('https://w.example/'), env)).status, 404);
  assert.equal((await handler.fetch(new Request('https://w.example/other'), env)).status, 404);
  assert.equal(await (await handler.fetch(new Request('https://w.example/x7Qp'), env)).text(), 'to-object');
  assert.equal(await (await handler.fetch(new Request('https://w.example/x7Qp/probe'), env)).text(), 'to-object');
  assert.deepEqual(calls[0].opts, { locationHint: 'enam' });
  assert.equal(calls[0].id, 'id:exit-enam');
  // A misconfigured install answers nothing rather than proxying for anyone.
  assert.equal((await handler.fetch(new Request('https://w.example/x7Qp'), { ...env, EXIT_ID: 'nope' })).status, 404);
  assert.equal((await handler.fetch(new Request('https://w.example/x7Qp'), { ...env, EXIT_HINT: 'wnam' })).status, 200);
  assert.deepEqual(calls[2].opts, { locationHint: 'wnam' });
});

console.log(`\n${passed} passed${process.exitCode ? ', some FAILED' : ''}`);
