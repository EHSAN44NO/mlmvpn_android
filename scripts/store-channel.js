#!/usr/bin/env node
/*
 * «ام‌ال‌ام استور» (Android) — the maintainer's tool for the signed update channel.
 *
 * The app believes two things about what it installs from its store (StoreChannel.kt):
 *   1. a developer's own GitHub release, checked against the digest GitHub computed at upload;
 *   2. a manifest signed with the key whose public half is written into the app.
 * This builds and publishes the second one. It is the Windows tool's twin (scripts/store-channel.js
 * in the Windows repo) and uses THE SAME KEY, so one private key signs both channels.
 *
 *   node scripts/store-channel.js build      read store/channel.spec.json → dist/store-channel/store-channel.json
 *   node scripts/store-channel.js verify     open the built file exactly as the app does
 *   node scripts/store-channel.js publish    upload it (and any local artifacts) with `gh`
 *
 * THE PRIVATE KEY LIVES OUTSIDE THIS REPO — %USERPROFILE%\.mlmvpn-dev\store-signing\<kid>.pem —
 * made once by the Windows tool's `keygen`.
 *
 * WHERE ARTIFACTS COME FROM, per artifact in the spec:
 *   { github: { repo, tag, asset } }  the digest GitHub computed at upload
 *   { url }                           downloaded and hashed here — pin it to a commit, not a branch
 *   { file }                          built on this machine (our own .so builds); hashed here and
 *                                     uploaded to this repo's `store-channel` release by `publish`
 * An artifact may carry `abi` (arm64-v8a, armeabi-v7a, x86_64, x86); the phone takes its own.
 */

'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const https = require('https');
const { execFileSync } = require('child_process');

const ROOT = path.join(__dirname, '..');
const KID = process.env.MLM_STORE_KID || 'mlm-store-2026-1';
const KEY_FILE = path.join(os.homedir(), '.mlmvpn-dev', 'store-signing', KID + '.pem');
const SPEC = path.join(ROOT, 'store', 'channel.spec.json');
const OUT_DIR = path.join(ROOT, 'dist', 'store-channel');
const OUT = path.join(OUT_DIR, 'store-channel.json');
const REPO = process.env.MLM_STORE_REPO || 'mlmvpn/mlmvpn_android';
const TAG = 'store-channel';

// Must equal StoreChannel.TRUSTED_KEYS in the app.
const TRUSTED_KEYS = { 'mlm-store-2026-1': 'n4IjFvvq0Ef+ynM99cCPwh3jUBsi08SRzt1CNXZHHww=' };
const SPKI = Buffer.from('302a300506032b6570032100', 'hex');

const say = (...a) => console.log(...a);
const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');
const gh = (args, opts = {}) => execFileSync('gh', args, Object.assign({ encoding: 'utf8', maxBuffer: 64 << 20 }, opts));

function get(url, { json = false, redirects = 5 } = {}) {
    return new Promise((resolve, reject) => {
        https.get(url, { headers: { 'User-Agent': 'mlmvpn-store-channel', Accept: json ? 'application/vnd.github+json' : '*/*' } }, (res) => {
            if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirects > 0) {
                res.resume();
                return resolve(get(new URL(res.headers.location, url).toString(), { json, redirects: redirects - 1 }));
            }
            if (res.statusCode !== 200) { res.resume(); return reject(new Error('HTTP ' + res.statusCode + ' for ' + url)); }
            const chunks = [];
            res.on('data', (c) => chunks.push(c));
            res.on('end', () => {
                const buf = Buffer.concat(chunks);
                resolve(json ? JSON.parse(buf.toString('utf8')) : buf);
            });
        }).on('error', reject);
    });
}

async function resolveArtifact(a, id) {
    const out = { name: a.name, format: a.format || 'raw' };
    if (a.abi) out.abi = a.abi;
    if (a.extract) out.extract = a.extract;
    const from = a.from || {};
    if (from.github) {
        const rel = await get(`https://api.github.com/repos/${from.github.repo}/releases/tags/${from.github.tag}`, { json: true });
        const hit = (rel.assets || []).find((x) => x.name === from.github.asset);
        if (!hit) throw new Error(`${id}: ${from.github.asset} not in ${from.github.repo}@${from.github.tag}`);
        if (!/^sha256:/.test(hit.digest || '')) throw new Error(`${id}: GitHub published no digest for ${hit.name}`);
        out.sha256 = hit.digest.slice(7);
        out.size = hit.size;
        out.urls = [hit.browser_download_url].concat(a.mirrors || []);
    } else if (from.url) {
        const buf = await get(from.url);
        out.sha256 = sha256(buf);
        out.size = buf.length;
        out.urls = [from.url].concat(a.mirrors || []);
    } else if (from.file) {
        const file = path.isAbsolute(from.file) ? from.file : path.join(ROOT, from.file);
        const buf = fs.readFileSync(file);
        out.sha256 = sha256(buf);
        out.size = buf.length;
        out.urls = [`https://github.com/${REPO}/releases/download/${TAG}/${a.name}`];
        out._upload = file;
    } else {
        throw new Error(id + ': artifact has no source');
    }
    return out;
}

function open(text) {
    const env = JSON.parse(text);
    const raw = TRUSTED_KEYS[env.kid];
    if (!raw) throw new Error('unknown key ' + env.kid);
    const key = crypto.createPublicKey({ key: Buffer.concat([SPKI, Buffer.from(raw, 'base64')]), format: 'der', type: 'spki' });
    const payload = Buffer.from(env.payload, 'base64');
    if (!crypto.verify(null, payload, key, Buffer.from(env.sig, 'base64'))) throw new Error('bad signature');
    return JSON.parse(payload.toString('utf8'));
}

function previous() {
    try { return open(fs.readFileSync(OUT, 'utf8')); } catch (e) { /* first build here */ }
    try {
        const tmp = path.join(os.tmpdir(), 'store-channel-android.json');
        gh(['release', 'download', TAG, '-R', REPO, '-p', 'store-channel.json', '-O', tmp, '--clobber'], { stdio: 'ignore' });
        return open(fs.readFileSync(tmp, 'utf8'));
    } catch (e) { return null; }
}

async function build() {
    const spec = JSON.parse(fs.readFileSync(SPEC, 'utf8'));
    const items = {};
    const uploads = [];
    for (const item of spec.items || []) {
        if (item.skip) { say('· skip', item.id); continue; }
        const artifacts = [];
        for (const a of item.artifacts || []) {
            const r = await resolveArtifact(a, item.id);
            if (r._upload) { uploads.push({ name: r.name, file: r._upload }); delete r._upload; }
            artifacts.push(r);
            say('·', item.id, r.abi || '', r.name, r.sha256.slice(0, 12) + '…', (r.size / 1048576).toFixed(1) + ' MB');
        }
        items[item.id] = { version: item.version, released: item.released || '', notes: item.notes || '', artifacts };
        if (item.minApp) items[item.id].minApp = item.minApp;
        if (item.forAppsBelow) items[item.id].forAppsBelow = item.forAppsBelow;
    }
    const prev = previous();
    const manifest = {
        schema: 1, channel: spec.channel || 'stable',
        sequence: (prev ? prev.sequence : 0) + 1,
        issuedAt: new Date().toISOString(),
        minApp: spec.minApp || null,
        items,
    };
    const payload = Buffer.from(JSON.stringify(manifest, null, 2), 'utf8');
    const sig = crypto.sign(null, payload, crypto.createPrivateKey(fs.readFileSync(KEY_FILE, 'utf8')));
    fs.mkdirSync(OUT_DIR, { recursive: true });
    fs.writeFileSync(OUT, JSON.stringify({ format: 'mlm-store-channel', kid: KID, alg: 'ed25519', payload: payload.toString('base64'), sig: sig.toString('base64') }, null, 2));
    fs.writeFileSync(path.join(OUT_DIR, 'uploads.json'), JSON.stringify(uploads, null, 2));
    say('\nwrote', OUT, '(sequence ' + manifest.sequence + ', ' + Object.keys(items).length + ' items)');
}

function verify() {
    const m = open(fs.readFileSync(OUT, 'utf8'));
    say('signature OK · sequence', m.sequence, '· issued', m.issuedAt);
    for (const [id, it] of Object.entries(m.items)) {
        say('  ' + id.padEnd(16), String(it.version).padEnd(14), it.artifacts.map((a) => (a.abi ? a.abi + ':' : '') + a.name).join(', '));
    }
}

function publish() {
    verify();
    const uploads = JSON.parse(fs.readFileSync(path.join(OUT_DIR, 'uploads.json'), 'utf8'));
    let exists = true;
    try { gh(['release', 'view', TAG, '-R', REPO], { stdio: 'ignore' }); } catch (e) { exists = false; }
    if (!exists) {
        gh(['release', 'create', TAG, '-R', REPO, '--prerelease', '--title', 'Store channel',
            '--notes', 'Signed version manifest for «ام‌ال‌ام استور» plus our own engine builds. Not an app release.']);
    }
    for (const f of uploads.map((u) => u.file + '#' + u.name).concat([OUT])) {
        say('uploading', path.basename(f.split('#')[0]));
        gh(['release', 'upload', TAG, f, '-R', REPO, '--clobber']);
    }
    say('published to https://github.com/' + REPO + '/releases/tag/' + TAG);
}

const run = { build, verify, publish }[process.argv[2] || 'verify'];
if (!run) { console.error('usage: build | verify | publish'); process.exit(2); }
Promise.resolve().then(run).catch((e) => { console.error('ERROR: ' + e.message); process.exit(1); });
