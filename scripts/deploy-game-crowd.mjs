#!/usr/bin/env node
/**
 * Deploys the Game Booster crowd worker (worker-src/game-crowd) to a Cloudflare account through
 * the REST API -- no wrangler, same calls the app itself makes to deploy workers.
 *
 *   node scripts/deploy-game-crowd.mjs <credentials.txt> <secrets.json>
 *
 * <credentials.txt>: line 1 the account e-mail, line 2 its Global API Key. Read, never printed.
 * <secrets.json>: created on the first run and reused on every later one, so a redeploy keeps the
 * same worker, database, signing key and tokens. It holds the snapshot's PRIVATE signing key and
 * the admin token: keep it outside the repository and do not share it.
 *
 * Idempotent: the database is found by name before one is created, the schema is CREATE IF NOT
 * EXISTS, and the script upload replaces the previous version in place.
 */

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const ROOT = path.resolve(import.meta.dirname, '..');
const WORKER = path.join(ROOT, 'worker-src/game-crowd/worker.js');
const SCHEMA = path.join(ROOT, 'worker-src/game-crowd/schema.sql');
const DB_NAME = 'gb-crowd';
const COMPAT_DATE = '2026-09-01';

const [credPath, secretsPath] = process.argv.slice(2);
if (!credPath || !secretsPath) {
  console.error('usage: node scripts/deploy-game-crowd.mjs <credentials.txt> <secrets.json>');
  process.exit(2);
}
const [email, key] = fs.readFileSync(credPath, 'utf8').split(/\r?\n/).map((s) => s.trim());
if (!email || !key) throw new Error('credentials file needs the e-mail on line 1 and the key on line 2');

const API = 'https://api.cloudflare.com/client/v4';
const auth = { 'X-Auth-Email': email, 'X-Auth-Key': key };

async function cf(method, p, body, headers = {}) {
  const res = await fetch(API + p, {
    method,
    headers: { ...auth, ...(body && !(body instanceof FormData) ? { 'content-type': 'application/json' } : {}), ...headers },
    body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined,
  });
  const j = await res.json().catch(() => ({}));
  if (!res.ok || j.success === false) {
    throw new Error(`${method} ${p} -> HTTP ${res.status} ${JSON.stringify(j.errors || j).slice(0, 400)}`);
  }
  return j.result;
}

function loadOrCreateSecrets() {
  if (fs.existsSync(secretsPath)) return JSON.parse(fs.readFileSync(secretsPath, 'utf8'));
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ed25519');
  const jwk = privateKey.export({ format: 'jwk' });
  const s = {
    workerName: 'gb-crowd-' + crypto.randomBytes(3).toString('hex'),
    appKey: crypto.randomBytes(32).toString('hex'),
    adminToken: crypto.randomBytes(32).toString('hex'),
    salt: crypto.randomBytes(16).toString('hex'),
    signJwk: jwk,
    // The raw 32-byte public key, base64: what the app pins.
    publicKey: Buffer.from(jwk.x, 'base64url').toString('base64'),
    note: 'Game Booster crowd worker secrets. signJwk is the PRIVATE snapshot signing key. Keep out of git.',
  };
  fs.writeFileSync(secretsPath, JSON.stringify(s, null, 2));
  // Sanity: the exported public half is the one the private key signs for.
  const sig = crypto.sign(null, Buffer.from('check'), privateKey);
  if (!crypto.verify(null, Buffer.from('check'), publicKey, sig)) throw new Error('key pair self-check failed');
  return s;
}

const secrets = loadOrCreateSecrets();

const accounts = await cf('GET', '/accounts?per_page=5');
if (!accounts.length) throw new Error('no account visible to this key');
const accountId = accounts[0].id;

const subdomain = (await cf('GET', `/accounts/${accountId}/workers/subdomain`)).subdomain;

// ── database ──────────────────────────────────────────────────────────────────────────────
const dbs = await cf('GET', `/accounts/${accountId}/d1/database?name=${DB_NAME}`);
let db = dbs.find((d) => d.name === DB_NAME);
if (!db) {
  db = await cf('POST', `/accounts/${accountId}/d1/database`, { name: DB_NAME });
  console.log('created database', db.uuid);
} else {
  console.log('database exists', db.uuid);
}
const schema = fs.readFileSync(SCHEMA, 'utf8')
  .split(/;\s*\n/).map((s) => s.replace(/--[^\n]*\n/g, '').trim()).filter(Boolean);
for (const sql of schema) {
  await cf('POST', `/accounts/${accountId}/d1/database/${db.uuid}/query`, { sql });
}
console.log('schema applied:', schema.length, 'statements');

// ── script ────────────────────────────────────────────────────────────────────────────────
const metadata = {
  main_module: 'worker.js',
  compatibility_date: COMPAT_DATE,
  bindings: [
    { type: 'd1', name: 'DB', id: db.uuid },
    { type: 'plain_text', name: 'APP_KEY', text: secrets.appKey },
    { type: 'secret_text', name: 'ADMIN_TOKEN', text: secrets.adminToken },
    { type: 'secret_text', name: 'SALT', text: secrets.salt },
    { type: 'secret_text', name: 'SIGN_JWK', text: JSON.stringify(secrets.signJwk) },
  ],
  observability: { enabled: true, head_sampling_rate: 0.05 },
};
const form = new FormData();
form.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
form.append('worker.js', new Blob([fs.readFileSync(WORKER, 'utf8')], { type: 'application/javascript+module' }), 'worker.js');
await cf('PUT', `/accounts/${accountId}/workers/scripts/${secrets.workerName}`, form);
await cf('POST', `/accounts/${accountId}/workers/scripts/${secrets.workerName}/subdomain`, { enabled: true, previews_enabled: false });

const endpoint = `https://${secrets.workerName}.${subdomain}.workers.dev`;
console.log('deployed', secrets.workerName);
console.log('endpoint', endpoint);
console.log('publicKey', secrets.publicKey);
