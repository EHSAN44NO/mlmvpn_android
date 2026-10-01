#!/usr/bin/env node
/**
 * Runs the crash collector (worker-src/crash/worker.js) for real, offline.
 *
 *   node scripts/test-crash-worker.mjs
 *
 * The Worker module is imported unchanged; KV is a Map and GitHub is a small in-memory issue
 * tracker behind a mocked `fetch`, so the cases that decide whether a report reaches a person can
 * be staged on purpose: a signed report, a forged one, a repeat, a regression on a newer build,
 * an expired token, missing secrets, KV out of writes, and the ceilings that stop a flood.
 *
 * Reports are signed here independently with node:crypto, exactly as CrashClient.kt signs them.
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { pathToFileURL } from 'node:url';

const ROOT = path.resolve(import.meta.dirname, '..');
const WORKER = path.join(ROOT, 'worker-src/crash/worker.js');
const SOURCE = fs.readFileSync(WORKER, 'utf8');
const APP_KEY = (SOURCE.match(/const APP_KEY = '([^']+)'/) || [])[1];
// The app must sign with the same key, or every report it sends is refused.
const CLIENT = fs.readFileSync(path.join(ROOT, 'app/src/main/java/com/mlmvpn/scanner/crash/CrashClient.kt'), 'utf8');
const CLIENT_KEY = (CLIENT.match(/const val APP_KEY = "([^"]+)"/) || [])[1];
const REPO = 'mlmvpn/crashes';
const HOUR = 60 * 60 * 1000;

const clock = { now: Date.parse('2026-10-01T08:00:00Z') };
Date.now = () => clock.now;

// ------------------------------------------------------------------------------------ GitHub

const github = { status: 200, issues: [], reset() { this.status = 200; this.issues = []; } };

globalThis.fetch = async (input, init = {}) => {
  const u = new URL(typeof input === 'string' ? input : input.url);
  if (u.origin !== 'https://api.github.com') throw new Error('unexpected network call: ' + u);
  const method = (init.method || 'GET').toUpperCase();
  const json = (obj, status = 200) => new Response(JSON.stringify(obj), { status, headers: { 'Content-Type': 'application/json' } });
  if (github.status !== 200) return json({ message: 'Bad credentials' }, github.status);
  if (!String((init.headers || {}).Authorization || '').startsWith('Bearer ')) return json({ message: 'Requires authentication' }, 401);
  const base = `/repos/${REPO}/issues`;
  const body = init.body ? JSON.parse(init.body) : {};
  if (u.pathname === base && method === 'GET') {
    const label = u.searchParams.get('labels');
    return json(github.issues.filter((i) => i.labels.includes(label)).reverse().slice(0, 1));
  }
  if (u.pathname === base && method === 'POST') {
    const issue = { number: github.issues.length + 1, title: body.title, body: body.body, labels: body.labels || [], state: 'open', comments: [] };
    github.issues.push(issue);
    return json(issue, 201);
  }
  const one = u.pathname.match(new RegExp(`^${base}/(\\d+)(/comments)?$`));
  if (one) {
    const issue = github.issues.find((i) => i.number === Number(one[1]));
    if (!issue) return json({ message: 'Not Found' }, 404);
    if (one[2] && method === 'POST') { issue.comments.push(body.body); return json({ id: 1 }, 201); }
    if (!one[2] && method === 'GET') return json(issue);
    if (!one[2] && method === 'PATCH') { Object.assign(issue, body); return json(issue); }
  }
  return json({ message: 'Not Found' }, 404);
};

// ------------------------------------------------------------------------------------ harness

function makeKV(state) {
  const map = new Map();
  return {
    map,
    async get(key, type) { const v = map.get(key); return v === undefined ? null : type === 'json' ? JSON.parse(v) : v; },
    async put(key, value) { if (state.kvWritesFail) throw new Error('KV put() limit exceeded for the day.'); map.set(key, String(value)); },
  };
}

let loads = 0;
async function coldIsolate() {
  const tmp = path.join(os.tmpdir(), `crash-worker-${process.pid}-${loads++}.mjs`);
  fs.copyFileSync(WORKER, tmp);
  return (await import(pathToFileURL(tmp).href)).default;
}

function setup(extra = {}) {
  const state = { kvWritesFail: false };
  const env = { STATE: makeKV(state), GH_TOKEN: 'test-token', GH_REPO: REPO, ADMIN_TOKEN: 'admin', ...extra };
  return { state, env };
}

const sign = (ts, text, key = APP_KEY) => crypto.createHmac('sha256', key).update(`${ts}.${text}`).digest('hex');

function payload(message, { app = '1.2.39 (2000071)', id = 'a1b2c3d4e5f60718', kind = 'jvm' } = {}) {
  return JSON.stringify({
    id,
    kind,
    summary: `java.lang.IllegalStateException: ${message}  |  at com.mlmvpn.scanner.ui.mae.MaeScreen.render(MaeScreen.kt:42)`,
    body: [
      '=== MLMVPN crash ===',
      `java.lang.IllegalStateException: ${message}`,
      '\tat com.mlmvpn.scanner.ui.mae.MaeScreen.render(MaeScreen.kt:42)',
      '--- breadcrumbs ---', 'home -> mae',
    ].join('\n'),
    app,
    device: 'samsung SM-A245F',
    android: '14',
  });
}

async function post(worker, env, text, { ts = Date.now(), key, sig, ip = '198.51.100.7' } = {}) {
  const headers = { 'X-Ts': String(ts), 'X-Sig': sig || sign(ts, text, key), 'CF-Connecting-IP': ip, 'Content-Type': 'application/json' };
  const res = await worker.fetch(new Request('https://crash.example/v1/crash', { method: 'POST', headers, body: text }), env);
  const t = await res.text();
  let json = null;
  try { json = JSON.parse(t); } catch (e) { }
  return { status: res.status, json };
}

async function healthOf(worker, env, k = 'admin') {
  const res = await worker.fetch(new Request(`https://crash.example/health?k=${k}`), env);
  return { status: res.status, json: await res.json().catch(() => null) };
}

let failures = 0;
function check(cond, what, detail) {
  if (cond) console.log('  PASS  ' + what);
  else { failures++; console.log('  FAIL  ' + what + (detail !== undefined ? '\n        ' + JSON.stringify(detail) : '')); }
}

// ------------------------------------------------------------------------------------ scenarios

async function basics() {
  console.log('a signed report becomes an issue a person can read');
  github.reset();
  const { env } = setup();
  const w = await coldIsolate();
  const r = await post(w, env, payload('boom'));
  check(r.status === 200 && r.json.ok && r.json.action === 'filed' && r.json.issue === 1, 'filed, and the answer says so', r);
  const issue = github.issues[0];
  check(issue && issue.labels.includes('crash') && issue.labels.some((l) => l.startsWith('sig:')), 'labelled crash + sig:', issue && issue.labels);
  check(issue && issue.body.includes('MaeScreen.kt:42') && issue.body.includes('home -> mae'), 'the whole report is in the issue');
  check(issue && issue.title.startsWith('java.lang.IllegalStateException: boom'), 'titled by the exception', issue && issue.title);

  const again = await post(w, env, payload('boom'));
  check(again.status === 200 && again.json.action === 'counted' && github.issues.length === 1 && issue.comments.length === 0,
    'a repeat within the hour is counted, not filed or commented', again.json);
  clock.now += HOUR + 1;
  const later = await post(w, env, payload('boom', { id: 'ffffeeee00001111' }));
  check(later.json.action === 'commented' && issue.comments.length === 1, 'an hour later it comments once', later.json);
  check(/3 reports from 2 installs/.test(issue.comments[0] || '') && issue.comments[0].includes('home -> mae'),
    'with the counts and that report in full', issue.comments[0]);

  const native = await post(w, env, payload('native one', { kind: 'native' }));
  check(native.status === 200 && github.issues[1] && github.issues[1].labels.includes('native'), 'a native crash is labelled native', github.issues[1] && github.issues[1].labels);
}

async function refusals() {
  console.log('what is refused');
  github.reset();
  const { env } = setup();
  const w = await coldIsolate();
  const text = payload('x');
  check((await post(w, env, text, { key: 'not-the-key' })).status === 401, 'a report signed with another key');
  check((await post(w, env, text, { ts: Date.now() - 4 * 24 * HOUR })).status === 401, 'a signature from days outside the window');
  check((await post(w, env, text + ' ')).status === 200, 'but a report that waited a while on a phone is fine');
  check((await post(w, env, JSON.stringify({ summary: 'x', body: '   ' }))).status === 400, 'an empty report');
  check((await post(w, env, 'x'.repeat(60 * 1024))).status === 413, 'an oversized one');
  check(github.issues.length === 1, 'none of those filed anything', github.issues.length);
}

async function regression() {
  console.log('a fixed bug coming back');
  github.reset();
  const { env } = setup();
  const w = await coldIsolate();
  await post(w, env, payload('regress', { app: '1.2.39 (2000071)' }));
  github.issues[0].state = 'closed';
  clock.now += HOUR + 1;
  const old = await post(w, env, payload('regress', { app: '1.2.39 (2000071)' }));
  check(old.status === 200 && github.issues[0].state === 'closed', 'the build it was seen on keeps it closed', old.json);
  clock.now += HOUR + 1;
  const newer = await post(w, env, payload('regress', { app: '1.2.40 (2000072)' }));
  check(newer.json.action === 'reopened' && github.issues[0].state === 'open', 'a newer build reopens it', newer.json);
  check(github.issues[0].comments.some((c) => c.startsWith('Reopened') && c.includes('1.2.40')), 'and says which build');
}

async function githubFailing() {
  console.log('GitHub refusing (an expired token), and no secrets at all');
  github.reset();
  const { env } = setup();
  const w = await coldIsolate();
  github.status = 401;
  const r = await post(w, env, payload('lost?'));
  check(r.status === 502 && r.json.github_status === 401, 'not "ok": the app keeps the report and sends it again', r);
  const h = await healthOf(w, env);
  check(h.json.github.configured && h.json.github.last_error.status === 401 && /Bad credentials/.test(h.json.github.last_error.message),
    '/health names the failure', h.json);
  github.status = 200;
  clock.now += 1;
  const retry = await post(w, env, payload('lost?'));
  check(retry.status === 200 && github.issues.length === 1, 'the retry files it once the token works', retry);
  const h2 = await healthOf(w, env);
  check(h2.json.github.last_ok_at > h2.json.github.last_error.at, 'and /health shows the recovery', h2.json.github);
  check((await healthOf(w, env, 'wrong')).status === 401, '/health needs the admin token');

  const bare = setup({ GH_TOKEN: undefined }).env;
  const none = await post(w, bare, payload('nowhere'));
  check(none.status === 503, 'no secrets: 503, so the app tries its fallback', none);
}

async function kvFull() {
  console.log('KV out of writes, and no KV at all');
  github.reset();
  const { env, state } = setup();
  const w = await coldIsolate();
  state.kvWritesFail = true;
  for (let i = 0; i < 5; i++) await post(w, env, payload('kv full'));
  check(github.issues.length === 1 && github.issues[0].comments.length === 0, 'one issue, no comment flood', github.issues[0]);

  const nokv = { GH_TOKEN: 't', GH_REPO: REPO, ADMIN_TOKEN: 'admin' };
  const w2 = await coldIsolate();
  const a = await post(w2, nokv, payload('no kv'));
  const w3 = await coldIsolate();
  const b = await post(w3, nokv, payload('no kv'));
  check(a.status === 200 && b.status === 200 && github.issues.filter((i) => i.title.includes('no kv')).length === 1,
    'a cold isolate without KV finds the issue by its label instead of filing it twice', [a.json, b.json]);
}

async function ceilings() {
  console.log('what stops a flood');
  github.reset();
  const { env } = setup();
  const w = await coldIsolate();
  let last;
  for (let i = 0; i < 45; i++) last = await post(w, env, payload('flood ' + (i % 3), { id: 'abcdefabcdef0001' }));
  check(last.status === 429, 'one install: 40 reports a day', last.status);
  const w2 = await coldIsolate();
  let made = 0;
  for (let i = 0; i < 160; i++) {
    const r = await post(w2, env, payload('distinct ' + i, { id: (1000000000 + i).toString(16) + 'aa' }), { ip: '203.0.113.' + (i % 200) });
    if (r.status === 200) made++;
  }
  check(made === 150 - 3 && github.issues.length === 150, 'at most 150 new issues a day, across isolates', { made, issues: github.issues.length });
}

console.log('crash collector:', path.relative(ROOT, WORKER));
if (!APP_KEY) throw new Error('APP_KEY not found in the worker');
check(APP_KEY === CLIENT_KEY, 'the app signs with the key the worker checks', { worker: APP_KEY, app: CLIENT_KEY });
for (const scenario of [basics, refusals, regression, githubFailing, kvFull, ceilings]) {
  try {
    await scenario();
  } catch (e) {
    failures++;
    console.log('  FAIL  ' + scenario.name + ' threw: ' + (e.stack || e));
  }
}
console.log(failures === 0 ? 'ALL PASSED' : `${failures} FAILED`);
process.exit(failures === 0 ? 0 : 1);
