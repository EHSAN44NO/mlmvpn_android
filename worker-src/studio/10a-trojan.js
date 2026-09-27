// ==========================================================
// Trojan — the second protocol this data plane speaks
// ==========================================================
//
// Detected from the bytes rather than from the path, because one WebSocket endpoint has to serve
// both: a config's transport is what the operator chose and the tunnel should not need a second
// address to carry a second protocol.
//
// The two headers are unmistakable. Trojan opens with **56 lowercase hex characters followed by
// CRLF**; a VLESS header's second byte is the first byte of a UUID and the pair at offset 56 is
// whatever the payload happens to contain. Checking for the CRLF *and* that everything before it is
// hex is what keeps a VLESS stream that happens to have 0x0D 0x0A there from being misread.
//
// ```
//   hex(SHA-224(password))   56 bytes, ASCII
//   CRLF                      2
//   CMD                       1     0x01 connect, 0x03 udp associate
//   ATYP                      1     0x01 ipv4, 0x03 domain, 0x04 ipv6
//   DST.ADDR                  variable
//   DST.PORT                  2     big endian
//   CRLF                      2
//   payload...
// ```
//
// **The worker never hashes anything.** Workers' `crypto.subtle` has no SHA-224, and the wire
// carries the hash rather than the password, so the app computes `auth_hash` once when the config
// is created and this looks it up on `ux_configs_auth` (migration v11). One indexed read, no
// crypto, on the hottest path in the file.

const STUDIO_TROJAN_HEADER = 56;

/** Whether this buffer opens with a Trojan header. Cheap enough to run before anything else. */
function studioIsTrojanHeader(buf) {
  if (!buf || buf.byteLength < STUDIO_TROJAN_HEADER + 4) return false;
  if (buf[STUDIO_TROJAN_HEADER] !== 0x0d || buf[STUDIO_TROJAN_HEADER + 1] !== 0x0a) return false;
  for (let i = 0; i < STUDIO_TROJAN_HEADER; i++) {
    const c = buf[i];
    const isDigit = c >= 0x30 && c <= 0x39;
    const isLower = c >= 0x61 && c <= 0x66;
    if (!isDigit && !isLower) return false;
  }
  return true;
}

function studioTrojanAuth(buf) {
  return new TextDecoder().decode(buf.slice(0, STUDIO_TROJAN_HEADER));
}

/**
 * Parse the request that follows the hash, or null if it is not complete yet.
 *
 * Returns the same shape the VLESS arm produces, so everything downstream -- admission, the proxy
 * fallback ladder, the byte accounting -- is one code path rather than two that drift.
 */
function studioParseTrojanRequest(buf) {
  let offset = STUDIO_TROJAN_HEADER + 2;
  if (buf.byteLength < offset + 4) return null;

  const cmd = buf[offset++];
  const atyp = buf[offset++];

  let addr = '';
  if (atyp === 1) {
    if (buf.byteLength < offset + 4) return null;
    addr = `${buf[offset++]}.${buf[offset++]}.${buf[offset++]}.${buf[offset++]}`;
  } else if (atyp === 3) {
    const len = buf[offset++];
    if (buf.byteLength < offset + len) return null;
    addr = new TextDecoder().decode(buf.slice(offset, offset + len));
    offset += len;
  } else if (atyp === 4) {
    if (buf.byteLength < offset + 16) return null;
    addr = studioIpv6At(buf, offset);
    offset += 16;
  } else {
    return null;
  }

  if (buf.byteLength < offset + 4) return null;
  const port = (buf[offset++] << 8) | buf[offset++];
  offset += 2; // the CRLF that closes the request

  // Trojan's server says nothing back before the payload -- unlike VLESS, which answers with its
  // version byte and a zero. An empty header here is what makes `connectStreams` shared.
  return { cmd, addr, port, payload: buf.slice(offset), respHeader: new Uint8Array(0) };
}

/**
 * Find the person a VLESS credential belongs to — **the config's, then the user's**.
 *
 * In that order, and the order is the bug fix. The builder generates a credential per config for
 * every shape that can carry one, which is the default for VLESS over WebSocket — the most common
 * config this product makes. Nothing in the data plane read `configs`, so that credential matched
 * no row and the connection was refused as an unknown one. The failure was silent and expensive to
 * diagnose from either end: the operator sees a config sitting in the list, the subscriber sees a
 * server in their app, the link imports cleanly, and it simply never connects.
 *
 * The `users.uuid` fallback is not legacy tolerance — it is required. XHTTP authenticates against
 * the user's own uuid and never had a per-config credential (see `ConfigDraft.VLESS_XHTTP`), every
 * config written before per-config credentials existed carries the user's uuid in that column, and
 * an installation adopted from the legacy panel has users with a uuid and no configs row at all.
 *
 * `studio_config_id` is carried out the same way `studioTrojanUser` has always carried it, which is
 * what lets a closed session record WHICH config it went through — and therefore what makes
 * per-config traffic and the popular-protocol figures answerable at all. It is null on the fallback
 * branch, and a session row with a null config is written rather than skipped: "connected on a
 * credential older than configs" is a real state and dropping the row would lose the traffic.
 */
async function studioVlessUser(env, credential) {
  if (!credential) return null;
  try {
    const viaConfig = await studioConfigLookup(env,
      `SELECT u.*, c.id AS studio_config_id, c.protocol AS studio_protocol,
              c.transport_type AS studio_transport{EXIT}
         FROM configs c JOIN users u ON u.uid = c.user_uid
        WHERE c.credential = ? AND c.enabled = 1 AND c.deleted_at IS NULL`, credential);
    if (viaConfig) return viaConfig;
  } catch (e) {
    // A pre-v16 installation has no index here and an adopted one may have no `configs` table at
    // all. Falling through to the uuid lookup keeps such an account connecting exactly as it did.
  }
  try {
    return await env.DB.prepare('SELECT * FROM users WHERE uuid = ?').bind(credential).first();
  } catch (e) {
    return null;
  }
}

/**
 * The user behind a Trojan credential.
 *
 * A join rather than two reads: the hash identifies a CONFIG, and everything that follows is about
 * the user who owns it. Both halves are indexed, so this is the same cost as the VLESS lookup.
 */
async function studioTrojanUser(env, authHash) {
  try {
    return await studioConfigLookup(env,
      `SELECT u.*, c.id AS studio_config_id, c.protocol AS studio_protocol,
              c.transport_type AS studio_transport{EXIT}
         FROM configs c JOIN users u ON u.uid = c.user_uid
        WHERE c.auth_hash = ? AND c.enabled = 1 AND c.deleted_at IS NULL`, authHash);
  } catch (e) {
    return null;
  }
}

/**
 * A config lookup that also carries the config's country (`studio_exit_cc`) and, from schema 19, the
 * exit it is pinned to (`studio_exit_pin`, 04j), where the schema has them.
 *
 * The data plane never migrates, so between a redeploy and the first API call a column may not exist
 * yet. If the engine says no such column, this isolate asks one level down for ten seconds and then
 * tries again -- never for longer, because once the migration lands a config CAN have a country, and
 * serving it without one would send that person out directly: the leak locations exist to prevent.
 * No config can have a country before the column exists, so those seconds without it leak nothing.
 */
const STUDIO_LOOKUP_LEVELS = [
  ', c.exit_cc AS studio_exit_cc, c.exit_pin AS studio_exit_pin',
  ', c.exit_cc AS studio_exit_cc',
  '',
];
let STUDIO_LOOKUP_LEVEL = 0;
let STUDIO_LOOKUP_LEVEL_UNTIL = 0;
async function studioConfigLookup(env, sql, key) {
  if (STUDIO_LOOKUP_LEVEL && Date.now() >= STUDIO_LOOKUP_LEVEL_UNTIL) STUDIO_LOOKUP_LEVEL = 0;
  for (let level = STUDIO_LOOKUP_LEVEL; level < STUDIO_LOOKUP_LEVELS.length; level++) {
    try {
      return await env.DB.prepare(sql.replace('{EXIT}', STUDIO_LOOKUP_LEVELS[level])).bind(key).first();
    } catch (e) {
      const msg = String(e && e.message);
      if (level + 1 >= STUDIO_LOOKUP_LEVELS.length || !/exit_pin|exit_cc/.test(msg)) throw e;
      STUDIO_LOOKUP_LEVEL = level + 1;
      STUDIO_LOOKUP_LEVEL_UNTIL = Date.now() + 10000;
    }
  }
  return null;
}

// ---------------------------------------------------------------------------- the credential cache

/**
 * credential -> { at, user } for a few seconds, per isolate.
 *
 * The lookup above is the one D1 read every tunnel connection made before its first byte could
 * leave, and a client opens one connection per site it loads -- so a page with thirty requests
 * waited on thirty identical reads. Fifteen seconds of a row is what spares them, and what it costs
 * is bounded: an account switched off keeps connecting for at most that long, and the sessions it
 * already has are ended by the meter's re-check within the minute. Usage is not served from here:
 * the meter's own room is used whenever this isolate has one (07a-meter.js › studioMeterRoom).
 *
 * Only hits are cached. A config just created in another isolate must work on its first connection.
 */
const CRED_CACHE = new Map();
const CRED_CACHE_MS = 15000;

async function studioCachedUser(kind, key, load) {
  const k = kind + ':' + key;
  const now = Date.now();
  const hit = CRED_CACHE.get(k);
  if (hit && now - hit.at < CRED_CACHE_MS) return { user: hit.user, at: hit.at, cached: true, kind, key, load };
  const user = await load();
  if (user) {
    if (CRED_CACHE.size > 5000) CRED_CACHE.clear();
    CRED_CACHE.set(k, { at: now, user });
  }
  return { user, at: now, cached: false, kind, key, load };
}

function studioDropCachedUser(kind, key) {
  CRED_CACHE.delete(kind + ':' + key);
}

/**
 * Called by the control plane on every write, in its own isolate: what the operator just changed
 * must not be answered from a cache in the same place they changed it.
 */
function studioInvalidateHotCaches() {
  CRED_CACHE.clear();
  ROUTE_KEY_CACHE.clear();
  PROXY_IP_CACHE.at = 0;
}