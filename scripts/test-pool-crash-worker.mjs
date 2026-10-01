#!/usr/bin/env node
/**
 * Runs the pool Worker's crash path for real, against real SQL, offline.
 *
 *   node scripts/test-pool-crash-worker.mjs
 *
 * Same approach as test-game-crowd-worker.mjs: Node's own SQLite stands in for D1, the Worker
 * module is imported unchanged, and requests are real Request objects. KV is a Map and GitHub is
 * a small in-memory issue tracker behind a mocked `fetch`, so every way the pipeline used to lose
 * reports can be staged on purpose:
 *
 *   - a user who has spent the day's Quick Connect budget, then crashes;
 *   - D1 refusing every query (the free plan's daily read ceiling) in a warm and a cold isolate;
 *   - an expired GitHub token, which used to fail without a trace anywhere;
 *   - a fixed bug coming back after its issue was closed;
 *   - KV refusing writes (the free plan's daily write ceiling), which used to turn every report
 *     into a fresh comment on the same issue.
 *
 * scripts/test-crash-report.js is the other half: it talks to the deployed Worker and files a real
 * issue. This one needs no network and changes nothing anywhere.
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { DatabaseSync } from 'node:sqlite';

const ROOT = path.resolve(import.meta.dirname, '..');
const WORKER = path.join(ROOT, 'app/src/main/assets/mlmvpn_pool_worker.js');
const SOURCE = fs.readFileSync(WORKER, 'utf8');
// The dashboard password is a literal in the Worker; read it rather than repeat it here.
const PASSWORD = (SOURCE.match(/const STATS_PASSWORD = '([^']+)'/) || [])[1];
const REPO = 'mlmvpn/crashes';
const HOUR = 60 * 60 * 1000;

// ------------------------------------------------------------------------------------- the clock

const clock = { now: Date.parse('2026-10-01T08:00:00Z') };
Date.now = () => clock.now;

// ---------------------------------------------------------------------------------------- D1

const D1_LIMIT = "D1_ERROR: Your account has exceeded D1's free tier daily row read limit";

function makeD1(db, state) {
  const guard = () => { if (state.failing) throw new Error(D1_LIMIT); };
  const statement = (sql, params = []) => ({
    bind: (...args) => statement(sql, args),
    async first() {
      guard();
      const row = db.prepare(sql).get(...params);
      return row === undefined ? null : row;
    },
    async run() {
      guard();
      return { success: true, meta: db.prepare(sql).run(...params) };
    },
    async all() {
      guard();
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
      guard();
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

// ---------------------------------------------------------------------------------------- KV

function makeKV(state) {
  const map = new Map();
  return {
    map,
    async get(key, type) {
      const v = map.get(key);
      if (v === undefined) return null;
      return type === 'json' ? JSON.parse(v) : v;
    },
    async put(key, value) {
      if (state.kvWritesFail) throw new Error('KV put() limit exceeded for the day.');
      map.set(key, String(value));
    },
  };
}

// ------------------------------------------------------------------------------------ GitHub

const github = {
  status: 200,          // what every call answers with; 401 stages an expired token
  issues: [],
  calls: [],
  reset() { this.status = 200; this.issues = []; this.calls = []; },
};

async function githubFetch(url, init = {}) {
  const u = new URL(url);
  const method = (init.method || 'GET').toUpperCase();
  github.calls.push(`${method} ${u.pathname}`);
  const json = (obj, status = 200) => new Response(JSON.stringify(obj), {
    status, headers: { 'Content-Type': 'application/json' },
  });
  if (github.status !== 200) return json({ message: 'Bad credentials' }, github.status);
  const auth = (init.headers && init.headers.Authorization) || '';
  if (!auth.startsWith('Bearer ')) return json({ message: 'Requires authentication' }, 401);

  const base = `/repos/${REPO}/issues`;
  const body = init.body ? JSON.parse(init.body) : {};
  if (u.pathname === base && method === 'GET') {
    const label = u.searchParams.get('labels');
    const hits = github.issues.filter((i) => i.labels.includes(label)).reverse();
    return json(hits.slice(0, Number(u.searchParams.get('per_page') || 30)));
  }
  if (u.pathname === base && method === 'POST') {
    const issue = {
      number: github.issues.length + 1, title: body.title, body: body.body,
      labels: body.labels || [], state: 'open', comments: [],
    };
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
}

globalThis.fetch = async (input, init) => {
  const url = typeof input === 'string' ? input : input.url;
  if (url.startsWith('https://api.github.com/')) return githubFetch(url, init);
  throw new Error('unexpected network call: ' + url);
};

// ------------------------------------------------------------------------------------ harness

let loads = 0;
/** A fresh copy of the module: a cold isolate, with none of the last one's memory. */
async function coldIsolate() {
  const tmp = path.join(os.tmpdir(), `pool-worker-${process.pid}-${loads++}.mjs`);
  fs.copyFileSync(WORKER, tmp);
  return (await import(pathToFileURL(tmp).href)).default;
}

function setup() {
  const state = { failing: false, kvWritesFail: false };
  const sqlite = new DatabaseSync(':memory:');
  const env = {
    DB: makeD1(sqlite, state),
    POOL: makeKV(state),
    GH_TOKEN: 'test-token',
    GH_REPO: REPO,
  };
  return { state, sqlite, env };
}

async function call(worker, env, pathname, { method = 'GET', body = null, who = null } = {}) {
  const headers = {};
  if (who) {
    const ts = String(Date.now());
    headers['X-Install'] = who.id;
    headers['X-Ts'] = ts;
    headers['X-Sig'] = crypto.createHmac('sha256', who.secret).update(`${who.id}.${ts}.${body || ''}`).digest('hex');
  }
  const pending = [];
  const ctx = { waitUntil: (p) => pending.push(p) };
  const request = new Request('https://pool.example' + pathname, { method, headers, body });
  let res;
  try {
    res = await worker.fetch(request, env, ctx);
  } catch (e) {
    // In production an exception out of fetch() is Cloudflare's 1101 error page.
    return { status: 1101, json: null, thrown: String(e.message || e) };
  }
  await Promise.all(pending);
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch (e) { }
  return { status: res.status, json, text };
}

async function enroll(worker, env) {
  const r = await call(worker, env, '/enroll', { method: 'POST' });
  if (!r.json || !r.json.id) throw new Error('enrol failed: ' + JSON.stringify(r));
  return r.json;
}

function crashBody(message, app = '1.2.37 (2000069)') {
  const text = [
    '=== MLMVPN crash ===',
    `java.lang.IllegalStateException: ${message}`,
    '\tat com.mlmvpn.scanner.ui.home.HomeScreen.render(HomeScreen.kt:42)',
  ].join('\n');
  return JSON.stringify({
    summary: `java.lang.IllegalStateException: ${message}  |  at com.mlmvpn.scanner.ui.home.HomeScreen.render(HomeScreen.kt:42)`,
    body: text,
    app,
    device: 'samsung SM-A245F',
    android: '16',
  });
}

const sendCrash = (worker, env, who, message, app) =>
  call(worker, env, '/crash', { method: 'POST', body: crashBody(message, app), who });

let failures = 0;
function check(cond, what, detail) {
  if (cond) console.log('  PASS  ' + what);
  else { failures++; console.log('  FAIL  ' + what + (detail !== undefined ? '\n        ' + JSON.stringify(detail) : '')); }
}

// ------------------------------------------------------------------------------------ scenarios

async function filesAndDedups() {
  console.log('a crash becomes one issue, and repeats do not bury it');
  github.reset();
  const { env } = setup();
  const worker = await coldIsolate();
  const who = await enroll(worker, env);

  const first = await sendCrash(worker, env, who, 'boom');
  check(first.status === 200 && first.json.ok && first.json.stored === true, 'accepted and stored', first);
  check(github.issues.length === 1, 'one issue filed', github.issues.length);
  const issue = github.issues[0];
  check(issue && issue.labels.includes('crash') && issue.labels.some((l) => l.startsWith('sig:')), 'labelled crash + sig:', issue && issue.labels);
  check(issue && issue.body.includes('HomeScreen.kt:42'), 'the stack is in the issue');

  await sendCrash(worker, env, who, 'boom');
  check(github.issues.length === 1 && github.issues[0].comments.length === 0, 'a repeat within the hour neither files nor comments');

  clock.now += HOUR + 1;
  await sendCrash(worker, env, who, 'boom');
  check(github.issues.length === 1 && github.issues[0].comments.length === 1, 'a repeat after an hour adds one comment', github.issues[0].comments);
}

async function ownAllowance() {
  console.log('a crash report is not refused for Quick Connect traffic');
  github.reset();
  const { env, sqlite } = setup();
  const worker = await coldIsolate();
  const who = await enroll(worker, env);
  const today = new Date(Date.now()).toISOString().slice(0, 10);
  sqlite.prepare('UPDATE installs SET day = ?, calls = 60 WHERE id = ?').run(today, who.id);

  const list = await call(worker, env, '/list', { who });
  check(list.status === 429, 'Quick Connect is rate limited after its 60 calls', list.status);
  const r = await sendCrash(worker, env, who, 'after a busy day');
  check(r.status === 200 && github.issues.length === 1, 'the crash report still goes through', r);

  // ... but crash reports have a ceiling of their own.
  let last = null;
  for (let i = 0; i < 40; i++) last = await sendCrash(worker, env, who, 'loop ' + i);
  check(last.status === 429, 'a crash loop is capped by its own daily limit', last.status);
}

async function d1Down() {
  console.log('D1 refusing every query (the daily read ceiling)');
  github.reset();
  const { env, state } = setup();
  const worker = await coldIsolate();
  const who = await enroll(worker, env);
  await call(worker, env, '/list', { who }); // seen once while D1 worked

  state.failing = true;
  const warm = await sendCrash(worker, env, who, 'while D1 is down');
  check(warm.status === 200 && warm.json && warm.json.stored === false, 'a warm isolate still accepts an install it has verified', warm);
  check(github.issues.length === 1, 'and files it on GitHub', github.issues.length);

  const cold = await coldIsolate();
  const r = await sendCrash(cold, env, who, 'cold and D1 down');
  check(r.status !== 1101, 'a cold isolate answers instead of throwing (no 1101 page)', r);
  check(r.status === 503 && r.json && r.json.error, 'with a 503 the app can retry on', r);

  const other = await sendCrash(worker, env, { id: who.id, secret: 'f'.repeat(64) }, 'forged');
  check(other.status === 401, 'a wrong signature is still refused while D1 is down', other.status);

  const dash = await call(worker, env, `/crashes?k=${PASSWORD}&days=14`);
  check(dash.status !== 1101 && dash.json && dash.json.d1_error, '/crashes says D1 is the problem instead of failing', dash);
}

async function tokenExpired() {
  console.log('an expired GitHub token is visible, not silent');
  github.reset();
  const { env } = setup();
  const worker = await coldIsolate();
  const who = await enroll(worker, env);

  github.status = 401;
  const r = await sendCrash(worker, env, who, 'nobody hears this');
  check(r.status === 200 && r.json.stored === true, 'the report is kept in D1', r);
  const dash = await call(worker, env, `/crashes?k=${PASSWORD}&days=14`);
  const gh = dash.json && dash.json.github;
  check(gh && gh.configured === true && gh.last_error && /401/.test(gh.last_error.status + ' ' + gh.last_error.message),
    '/crashes reports the GitHub failure', gh);
  check(dash.json && dash.json.groups && dash.json.groups.length === 1, 'and still lists the crash from D1', dash.json && dash.json.groups);

  github.status = 200;
  clock.now += 1;
  await sendCrash(worker, env, who, 'heard again');
  const after = await call(worker, env, `/crashes?k=${PASSWORD}&days=14`);
  check(after.json.github.last_ok_at >= after.json.github.last_error.at, 'recovery shows as well', after.json.github);

  const unset = { ...env, GH_TOKEN: undefined };
  const d2 = await call(worker, unset, `/crashes?k=${PASSWORD}&days=14`);
  check(d2.json && d2.json.github && d2.json.github.configured === false, 'missing secrets are reported as such', d2.json && d2.json.github);
}

async function regression() {
  console.log('a bug that comes back after its issue was closed');
  github.reset();
  const { env } = setup();
  const worker = await coldIsolate();
  const who = await enroll(worker, env);

  await sendCrash(worker, env, who, 'fixed?', '1.2.37 (2000069)');
  github.issues[0].state = 'closed';

  clock.now += HOUR + 1;
  await sendCrash(worker, env, who, 'fixed?', '1.2.37 (2000069)');
  check(github.issues[0].state === 'closed', 'a report from a build it was already seen on leaves it closed', github.issues[0].state);

  clock.now += HOUR + 1;
  await sendCrash(worker, env, who, 'fixed?', '1.2.39 (2000071)');
  check(github.issues[0].state === 'open', 'the same crash on a newer build reopens it', github.issues[0].state);
  check(github.issues[0].comments.some((c) => /1\.2\.39/.test(c)), 'and says which build', github.issues[0].comments);
}

async function kvFull() {
  console.log('KV refusing writes (the daily write ceiling)');
  github.reset();
  const { env, state } = setup();
  const worker = await coldIsolate();
  const who = await enroll(worker, env);
  state.kvWritesFail = true;
  for (let i = 0; i < 5; i++) await sendCrash(worker, env, who, 'kv full');
  check(github.issues.length === 1, 'still one issue', github.issues.length);
  check(github.issues[0].comments.length === 0, 'and no comment flood within the hour', github.issues[0].comments.length);
}

console.log('pool worker crash path:', path.relative(ROOT, WORKER));
if (!PASSWORD) throw new Error('STATS_PASSWORD not found in the worker source');
for (const scenario of [filesAndDedups, ownAllowance, d1Down, tokenExpired, regression, kvFull]) {
  try {
    await scenario();
  } catch (e) {
    failures++;
    console.log('  FAIL  ' + scenario.name + ' threw: ' + (e.stack || e));
  }
}
console.log(failures === 0 ? 'ALL PASSED' : `${failures} FAILED`);
process.exit(failures === 0 ? 0 : 1);
