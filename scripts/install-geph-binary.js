#!/usr/bin/env node
/*
 * Installs Geph's own Android engine into jniLibs, straight from Geph's signed release.
 *
 *     node scripts/install-geph-binary.js            # the version Geph currently ships
 *     node scripts/install-geph-binary.js --apk <file>  # an APK already on disk (still verified)
 *
 * Geph publishes no bare Android binary. Its Android app runs `geph5-client` as a child process,
 * packaged as lib/<abi>/libgeph.so (geph-android's PREBUILD.sh: `cargo ndk ... --features
 * aws_lambda`, copied to prebuild/<abi>/libgeph.so) -- exactly the shape this app needs, since an
 * executable on Android has to live in nativeLibraryDir under a lib*.so name. So this takes that
 * file out of Geph's own release, unmodified, the way the Windows app takes aether.exe out of its
 * developer's zip.
 *
 * Trust chain, all of it checked, nothing skipped:
 *   1. metadata.yaml is signed with Geph's minisign key -- the same key geph5-client itself
 *      hard-codes to verify updates (binaries/geph5-client/src/updates.rs).
 *   2. That signed file names the APK's sha256.
 *   3. The APK's hash must match before a single byte is extracted from it.
 *   4. Each libgeph.so must be an ELF for its ABI, and PIE (the store runs updated copies through
 *      the system linker, which needs a position-independent executable).
 */
'use strict';
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = path.resolve(__dirname, '..');
const JNI = path.join(ROOT, 'app', 'src', 'main', 'jniLibs');
const CACHE = path.join(ROOT, 'build', 'geph');

/** Geph's release mirrors, in the order geph5-client tries them (updates.rs). */
const MIRRORS = [
  'https://f001.backblazeb2.com/file/geph4-dl/geph-releases',
  'https://sos-ch-dk-2.exo.io/utopia/geph-releases-new',
];
/** Geph's minisign public key, verbatim from updates.rs. */
const MINISIGN_KEY = 'RWSzEWRCN0AaNpPj+yw0zbOI87jI8PNpnCoITCroKQxRAANAzUawpph7';
const TRACK = 'android-stable';
/** ABI directory -> ELF e_machine. */
const ABIS = { 'arm64-v8a': 183 /* EM_AARCH64 */, 'armeabi-v7a': 40 /* EM_ARM */ };

async function get(url, asText) {
  const res = await fetch(url, { signal: AbortSignal.timeout(asText ? 30000 : 600000) });
  if (!res.ok) throw new Error(`${url}: HTTP ${res.status}`);
  return asText ? res.text() : Buffer.from(await res.arrayBuffer());
}

// ── minisign ──────────────────────────────────────────────────────────────────────────────────

function ed25519Key(raw32) {
  const spki = Buffer.concat([Buffer.from('302a300506032b6570032100', 'hex'), raw32]);
  return crypto.createPublicKey({ key: spki, format: 'der', type: 'spki' });
}

/**
 * Verifies a minisign signature the way minisign-verify does: the key id must match, the
 * signature covers the file (or its BLAKE2b-512 for the prehashed "ED" form), and the global
 * signature covers the signature plus the trusted comment, so the comment cannot be swapped.
 */
function minisignVerify(data, sigText) {
  const pk = Buffer.from(MINISIGN_KEY, 'base64');
  if (pk.length !== 42 || pk.toString('latin1', 0, 2) !== 'Ed') throw new Error('bad minisign public key');
  const keyId = pk.subarray(2, 10);
  const key = ed25519Key(pk.subarray(10));

  const lines = sigText.split(/\r?\n/);
  const sig = Buffer.from(lines[1].trim(), 'base64');
  const trusted = lines[2];
  const global = Buffer.from(lines[3].trim(), 'base64');
  if (sig.length !== 74) throw new Error('bad signature length');
  const alg = sig.toString('latin1', 0, 2);
  if (!sig.subarray(2, 10).equals(keyId)) throw new Error('signature is not from Geph\'s key');
  const message = alg === 'ED' ? crypto.createHash('blake2b512').update(data).digest()
    : alg === 'Ed' ? data : null;
  if (!message) throw new Error('unknown signature algorithm ' + alg);
  if (!crypto.verify(null, message, key, sig.subarray(10))) throw new Error('metadata.yaml signature does not verify');
  if (!trusted.startsWith('trusted comment: ')) throw new Error('missing trusted comment');
  const comment = Buffer.from(trusted.slice('trusted comment: '.length), 'utf8');
  if (!crypto.verify(null, Buffer.concat([sig.subarray(10), comment]), key, global)) {
    throw new Error('trusted comment signature does not verify');
  }
  return trusted;
}

// ── the signed manifest ───────────────────────────────────────────────────────────────────────

function trackEntry(yaml) {
  const m = new RegExp(`^${TRACK}:\\s*\\n((?:[ \\t]+.*\\n?)+)`, 'm').exec(yaml);
  if (!m) throw new Error(`no ${TRACK} entry in metadata.yaml`);
  const field = (k) => {
    const f = new RegExp(`^[ \\t]+${k}:\\s*(\\S+)`, 'm').exec(m[1]);
    if (!f) throw new Error(`${TRACK}.${k} missing`);
    return f[1].replace(/^["']|["']$/g, '');
  };
  return { version: field('version'), sha256: field('sha256').toLowerCase(), filename: field('filename') };
}

// ── zip (an APK) ──────────────────────────────────────────────────────────────────────────────

function zipEntries(buf) {
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65557); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('not a zip file');
  const count = buf.readUInt16LE(eocd + 10);
  let p = buf.readUInt32LE(eocd + 16);
  const out = {};
  for (let n = 0; n < count; n++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('bad central directory');
    const method = buf.readUInt16LE(p + 10);
    const csize = buf.readUInt32LE(p + 20);
    const usize = buf.readUInt32LE(p + 24);
    const nlen = buf.readUInt16LE(p + 28);
    const xlen = buf.readUInt16LE(p + 30);
    const clen = buf.readUInt16LE(p + 32);
    const local = buf.readUInt32LE(p + 42);
    const name = buf.toString('utf8', p + 46, p + 46 + nlen);
    out[name] = { method, csize, usize, local };
    p += 46 + nlen + xlen + clen;
  }
  return out;
}

function zipRead(buf, e) {
  if (buf.readUInt32LE(e.local) !== 0x04034b50) throw new Error('bad local header');
  const start = e.local + 30 + buf.readUInt16LE(e.local + 26) + buf.readUInt16LE(e.local + 28);
  const raw = buf.subarray(start, start + e.csize);
  const data = e.method === 0 ? raw : e.method === 8 ? zlib.inflateRawSync(raw) : null;
  if (!data) throw new Error('unsupported zip method ' + e.method);
  if (data.length !== e.usize) throw new Error('size mismatch after extraction');
  return data;
}

function checkElf(buf, abi) {
  if (buf.readUInt32BE(0) !== 0x7f454c46) throw new Error(`${abi}: not an ELF file`);
  const is64 = buf[4] === 2;
  const type = buf.readUInt16LE(16);
  const machine = buf.readUInt16LE(18);
  if (machine !== ABIS[abi]) throw new Error(`${abi}: ELF machine ${machine}, expected ${ABIS[abi]}`);
  if ((abi === 'arm64-v8a') !== is64) throw new Error(`${abi}: wrong ELF class`);
  if (type !== 3) throw new Error(`${abi}: not position-independent (e_type ${type})`);
}

async function main() {
  const apkArg = process.argv.indexOf('--apk');
  let yaml = null;
  let sig = null;
  let mirror = null;
  for (const base of MIRRORS) {
    try {
      yaml = await get(`${base}/metadata.yaml`, true);
      sig = await get(`${base}/metadata.yaml.minisig`, true);
      mirror = base;
      break;
    } catch (e) {
      console.warn('mirror failed: ' + e.message);
    }
  }
  if (!yaml) throw new Error('no Geph mirror answered');
  const trusted = minisignVerify(Buffer.from(yaml, 'utf8'), sig);
  const entry = trackEntry(yaml);
  console.log(`signed manifest OK (${trusted.slice(0, 80)}) -> ${TRACK} ${entry.version}`);

  let apk;
  if (apkArg > 0) {
    apk = fs.readFileSync(process.argv[apkArg + 1]);
  } else {
    fs.mkdirSync(CACHE, { recursive: true });
    const cached = path.join(CACHE, `${entry.version}-${entry.filename}`);
    if (fs.existsSync(cached)) {
      apk = fs.readFileSync(cached);
    } else {
      const url = `${mirror}/${TRACK}/${entry.version}/${entry.filename}`;
      console.log('downloading ' + url);
      apk = await get(url, false);
      fs.writeFileSync(cached, apk);
    }
  }
  const digest = crypto.createHash('sha256').update(apk).digest('hex');
  if (digest !== entry.sha256) throw new Error(`APK sha256 ${digest} does not match the signed ${entry.sha256}`);
  console.log(`APK sha256 matches the signed manifest (${apk.length} bytes)`);

  const entries = zipEntries(apk);
  for (const abi of Object.keys(ABIS)) {
    const e = entries[`lib/${abi}/libgeph.so`];
    if (!e) throw new Error(`the APK has no lib/${abi}/libgeph.so`);
    const so = zipRead(apk, e);
    checkElf(so, abi);
    const dest = path.join(JNI, abi, 'libgeph.so');
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.writeFileSync(dest, so);
    const soHash = crypto.createHash('sha256').update(so).digest('hex');
    console.log(`${abi}: ${so.length} bytes, sha256 ${soHash}`);
  }
  console.log(`\nGeph ${entry.version} installed. Update StoreCatalog's "geph" shipped version to ${entry.version}.`);
}

main().catch((e) => { console.error('FAILED: ' + e.message); process.exit(1); });
