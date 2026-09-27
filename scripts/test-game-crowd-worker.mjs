#!/usr/bin/env node
/**
 * Runs the Game Booster crowd worker for real, against real SQL.
 *
 *   node scripts/test-game-crowd-worker.mjs
 *
 * Same approach as test-studio-worker.mjs: Node's own SQLite stands in for D1 (the schema runs as
 * real DDL, the upserts and RETURNING clauses run as real SQL), the worker module is imported
 * unchanged, and requests are real Request objects with a `cf` object attached. The Ed25519
 * signature on the snapshot is checked with an independent implementation (node:crypto), so a
 * signing bug cannot certify itself.
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { DatabaseSync } from 'node:sqlite';

const ROOT = path.resolve(import.meta.dirname, '..');
const WORKER = path.join(ROOT, 'worker-src/game-crowd/worker.js');
const SCHEMA = path.join(ROOT, 'worker-src/game-crowd/schema.sql');

function makeD1(db) {
  const statement = (sql, params = []) => ({
    bind: (...args) => statement(sql, args),
    async first() {
      const row = db.prepare(sql).get(...params);
      return row === undefined ? null : row;
    },
    async run() {
      return { success: true, meta: db.prepare(sql).run(...params) };
    },
    async all() {
      return { success: true, results: db.prepare(sql).all(...params) };
    },
    _exec() {
      const st = db.prepare(sql);
      try {
        return { success: true, results: st.all(...params) };
      } catch (e) {
        return { success: true, results: [], meta: st.run(...params) };
      }
    },
  });
  return {
    prepare: (sql) => statement(sql),
    async batch(statements) {
      db.exec('BEGIN');
      try {
        const out = statements.map((s) => s._exec());
        db.exec('COMMIT');
        return out;
      } catch (e) {
        db.exec('ROLLBACK');
        throw e;
      }
    },
  };
}

let loads = 0;
async function loadWorker() {
  const tmp = path.join(os.tmpdir(), `game-crowd-${process.pid}-${loads++}.mjs`);
  fs.copyFileSync(WORKER, tmp);
  return (await import(pathToFileURL(tmp).href)).default;
}

function setup() {
  const db = new DatabaseSync(':memory:');
  db.exec(fs.readFileSync(SCHEMA, 'utf8'));
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ed25519');
  const env = {
    DB: makeD1(db),
    APP_KEY: 'test-app-key',
    ADMIN_TOKEN: 'admin-secret-token',
    SALT: 'salt',
    SIGN_JWK: JSON.stringify(privateKey.export({ format: 'jwk' })),
  };
  return { db, env, publicKey };
}

function ctx() {
  const pending = [];
  return { waitUntil: (p) => pending.push(p), pending };
}

function req(method, p, { body, headers = {}, asn = 44244, ip = '5.1.2.3' } = {}) {
  const r = new Request('https://gb.example.workers.dev' + p, {
    method, body, headers: { 'cf-connecting-ip': ip, ...headers },
  });
  Object.defineProperty(r, 'cf', { value: { asn } });
  return r;
}

function signedReport(env, id, rows, { ts = Date.now(), key = env.APP_KEY } = {}) {
  const body = JSON.stringify({ v: 1, rows });
  const sig = crypto.createHmac('sha256', key).update(`${id}.${ts}.${body}`).digest('hex');
  return { body, headers: { 'x-install': id, 'x-ts': String(ts), 'x-sig': sig, 'content-type': 'application/json' } };
}

const install = () => crypto.randomBytes(16).toString('hex');

let passed = 0;
let failed = 0;
async function test(name, fn) {
  try {
    await fn();
    passed++;
    console.log('  ok  ' + name);
  } catch (e) {
    failed++;
    console.log('  FAIL ' + name + '\n       ' + (e && e.stack || e));
  }
}
function assert(cond, msg) { if (!cond) throw new Error(msg || 'assertion failed'); }
function eq(a, b, msg) { if (JSON.stringify(a) !== JSON.stringify(b)) throw new Error(`${msg || 'not equal'}: ${JSON.stringify(a)} != ${JSON.stringify(b)}`); }

function verify(publicKey, slice) {
  return crypto.verify(null, Buffer.from(slice.b, 'utf8'), publicKey, Buffer.from(slice.s, 'base64'));
}

const fc = (extra = {}) => ({ g: 'fifamobile', r: 'EU', p: 'DD', o: 1, wm: 1, ww: 0, p50: 96, k: 2, s: { shecan: 1, '403': 0, radar: -1 }, ...extra });

console.log('game crowd worker');

await test('a signed report is stored as counters, under the caller\'s ASN', async () => {
  const { db, env } = setup();
  const w = await loadWorker();
  const r = signedReport(env, install(), [fc(), fc({ p: 'D', o: -1, s: {} })]);
  const res = await w.fetch(req('POST', '/r', r), env, ctx());
  const j = await res.json();
  eq(res.status, 200, 'status');
  eq(j.stored, 2, 'stored');
  eq(j.asn, 44244, 'asn echoed');
  const rows = db.prepare('SELECT asn, game, plan, n, ok, bad, wm, k2 FROM agg ORDER BY plan').all();
  eq(rows.map((x) => [x.asn, x.game, x.plan, x.n, x.ok, x.bad, x.wm, x.k2]),
    [[44244, 'fifamobile', 'D', 1, 0, 1, 1, 1], [44244, 'fifamobile', 'DD', 1, 1, 0, 1, 1]], 'agg rows');
  const prov = db.prepare('SELECT prov, ok, bad FROM prov ORDER BY prov').all();
  eq(prov.map((x) => [x.prov, x.ok, x.bad]), [['403', 0, 1], ['radar', 0, 1], ['shecan', 1, 0]], 'provider rows');
});

await test('bad signatures, bad headers and a wrong clock are refused', async () => {
  const { env } = setup();
  const w = await loadWorker();
  const id = install();
  const forged = signedReport(env, id, [fc()], { key: 'not-the-key' });
  eq((await w.fetch(req('POST', '/r', forged), env, ctx())).status, 403, 'forged');
  const old = signedReport(env, id, [fc()], { ts: Date.now() - 60 * 60 * 1000 });
  eq((await w.fetch(req('POST', '/r', old), env, ctx())).status, 400, 'clock');
  const noId = signedReport(env, 'nope', [fc()]);
  eq((await w.fetch(req('POST', '/r', noId), env, ctx())).status, 400, 'headers');
});

await test('junk rows are dropped, not trusted', async () => {
  const { db, env } = setup();
  const w = await loadWorker();
  const r = signedReport(env, install(), [
    { g: 'x; DROP TABLE agg', r: 'EU', p: 'D' }, { g: 'codm', r: 'eu', p: 'D' }, { g: 'codm', r: 'EU', p: 'VPN' },
    fc({ p50: 999999, k: 99 }),
  ]);
  const j = await (await w.fetch(req('POST', '/r', r), env, ctx())).json();
  eq(j.stored, 1, 'only the valid row');
  const row = db.prepare('SELECT p50s, k5 FROM agg').get();
  eq([row.p50s, row.k5], [1500, 1], 'clipped');
});

await test('one install writes at most four times a day', async () => {
  const { env } = setup();
  const w = await loadWorker();
  const id = install();
  const codes = [];
  for (let i = 0; i < 5; i++) codes.push((await w.fetch(req('POST', '/r', signedReport(env, id, [fc()])), env, ctx())).status);
  eq(codes, [200, 200, 200, 200, 429], 'statuses');
});

await test('a feedback row is a vote, not another session', async () => {
  const { db, env } = setup();
  const w = await loadWorker();
  await w.fetch(req('POST', '/r', signedReport(env, install(), [fc({ o: 0 }), fc({ f: 1, o: -1, s: {} })])), env, ctx());
  const row = db.prepare('SELECT n, ok, bad, wm FROM agg').get();
  eq([row.n, row.ok, row.bad, row.wm], [1, 0, 3, 1], 'feedback weighs 3 and adds no session');
});

await test('the snapshot is built on first read, per ASN, and signed', async () => {
  const { env, publicKey } = setup();
  const w = await loadWorker();
  for (let i = 0; i < 4; i++) await w.fetch(req('POST', '/r', signedReport(env, install(), [fc()])), env, ctx());
  await w.fetch(req('POST', '/r', { ...signedReport(env, install(), [fc({ p: 'W3', ww: 1 })]), asn: 58224 }), env, ctx());

  const c1 = ctx();
  const first = await (await w.fetch(req('GET', '/s'), env, c1)).json();
  eq(first.all, null, 'nothing before the first build');
  eq(c1.pending.length, 1, 'a rebuild was started');
  await Promise.all(c1.pending);

  const c2 = ctx();
  const snap = await (await w.fetch(req('GET', '/s'), env, c2)).json();
  eq(c2.pending.length, 0, 'fresh: no second rebuild');
  assert(snap.all && snap.mine, 'both slices');
  assert(verify(publicKey, snap.all) && verify(publicKey, snap.mine), 'signatures verify');
  const mine = JSON.parse(snap.mine.b);
  eq(mine.asn, 44244, 'slice ASN');
  const g = mine.g.fifamobile;
  eq(g.n, 4, 'sessions');
  eq(g.p.DD, [4, 4, 0], 'plan counts');
  eq(g.s.shecan, [4, 0], 'shecan opened it every time');
  eq(g.r.EU, [4, 96], 'region n and typical ping');
  const all = JSON.parse(snap.all.b);
  eq(all.g.fifamobile.n, 5, 'global counts both ASNs');
  eq(all.rate, 0.3, 'default report rate');
  // A tampered body must not verify.
  assert(!verify(publicKey, { b: snap.mine.b.replace('"n":4', '"n":9'), s: snap.mine.s }), 'tamper detected');
});

await test('an ASN with too few sessions gets no slice of its own', async () => {
  const { env } = setup();
  const w = await loadWorker();
  await w.fetch(req('POST', '/r', { ...signedReport(env, install(), [fc({ s: {} })]), asn: 197207 }), env, ctx());
  const c = ctx();
  await w.fetch(req('GET', '/s', { asn: 197207 }), env, c);
  await Promise.all(c.pending);
  const snap = await (await w.fetch(req('GET', '/s', { asn: 197207 }), env, ctx())).json();
  eq(snap.mine, null, 'one session is not a crowd');
});

await test('the owner\'s switches need the token and reach the snapshot at once', async () => {
  const { env, publicKey } = setup();
  const w = await loadWorker();
  const cfg = JSON.stringify({ rate: 0.1, flags: { sdns: true, warp: false }, kill: { sdns: ['radar'] } });
  const denied = await w.fetch(req('POST', '/admin/cfg', { body: cfg, headers: { authorization: 'Bearer wrong' } }), env, ctx());
  eq(denied.status, 404, 'wrong token looks like nothing is there');
  const ok = await w.fetch(req('POST', '/admin/cfg', { body: cfg, headers: { authorization: 'Bearer ' + env.ADMIN_TOKEN } }), env, ctx());
  eq(ok.status, 200, 'accepted');
  const snap = await (await w.fetch(req('GET', '/s'), env, ctx())).json();
  assert(verify(publicKey, snap.all), 'signed');
  const all = JSON.parse(snap.all.b);
  eq(all.rate, 0.1, 'rate');
  eq(all.flags.warp, false, 'flag');
  eq(all.kill.sdns, ['radar'], 'kill switch');
  const posted = await (await w.fetch(req('POST', '/r', signedReport(env, install(), [fc()])), env, ctx())).json();
  eq(posted.rate, 0.1, 'reports are told the new rate');
});

await test('old counters are pruned on rebuild', async () => {
  const { db, env } = setup();
  const w = await loadWorker();
  const today = Math.floor(Date.now() / 86400000);
  db.prepare("INSERT INTO agg (day, asn, game, region, plan, n) VALUES (?, 1, 'codm', 'EU', 'D', 5)").run(today - 40);
  await w.fetch(req('POST', '/admin/rebuild', { headers: { authorization: 'Bearer ' + env.ADMIN_TOKEN } }), env, ctx());
  eq(db.prepare('SELECT COUNT(*) c FROM agg').get().c, 0, 'pruned');
});

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
