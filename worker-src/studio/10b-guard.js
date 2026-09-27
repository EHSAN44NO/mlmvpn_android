// ==========================================================
// The abuse guard: what this engine refuses to carry
// ==========================================================
//
// Cloudflare suspends a Worker (and sometimes the account) when complaints about traffic leaving
// its addresses pile up. Those complaints come from a short list of activities, and none of them is
// something a subscriber needs a VPN for:
//
//  * BitTorrent -- the source of copyright notices, which arrive by the hundred and name the address
//    the swarm saw. That address is Cloudflare's.
//  * Mail submission -- spam sent through a Worker is reported by every receiving mail system.
//  * Scanning -- hundreds of new connections a minute to different hosts is what a port scanner or
//    a credential-stuffing bot looks like from the outside, and it is reported as an attack.
//  * Private and reserved addresses -- probing somebody's internal network through a public proxy.
//
// Refusing these is the difference between an engine that is quietly tolerated and one that draws
// reports. It costs a legitimate user nothing, and it runs on bytes already in hand: no D1 read,
// no extra connection.

const GUARD_BLOCKED_PORTS = new Set([25, 465, 587, 2525, 6881, 6882, 6883, 6884, 6885, 6886, 6887, 6888, 6889, 6969, 51413]);

/** username -> { start, n }: new outbound connections in the current ten-second window. */
const GUARD_RATE = new Map();
const GUARD_RATE_WINDOW_MS = 10000;
const GUARD_RATE_MAX = 200;

function studioGuardPrivate(host) {
  const h = String(host || '').toLowerCase();
  if (!h || h === 'localhost' || h.endsWith('.localhost') || h.endsWith('.local') || h.endsWith('.internal')) return true;
  if (!isIPv4(h)) return false;
  const [a, b] = h.split('.').map((x) => parseInt(x, 10));
  return a === 0 || a === 10 || a === 127 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31)
    || (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127) || a >= 224;
}

/** The first bytes of a BitTorrent peer handshake, or a tracker announce over HTTP. */
function studioGuardTorrent(data) {
  const d = data ? convertToUint8Array(data) : null;
  if (!d || d.byteLength < 20) return false;
  if (d[0] === 19) {
    const head = new TextDecoder().decode(d.slice(1, 20));
    if (head === 'BitTorrent protocol') return true;
  }
  if (d[0] === 71 /* G */) {
    const line = new TextDecoder().decode(d.slice(0, Math.min(d.byteLength, 512)));
    if (/^GET \/[^ ]*(announce|scrape)[^ ]*info_hash=/i.test(line)) return true;
  }
  return false;
}

/**
 * Why this connection must not be made, or null to let it through.
 *
 * [who] is the user it is counted against for the rate check. The reasons are short stable words,
 * because they land in the activity log where the operator reads them.
 */
function studioGuardVerdict(who, host, port, firstBytes) {
  if (GUARD_BLOCKED_PORTS.has(Number(port))) return Number(port) >= 6000 ? 'torrent' : 'mail';
  if (studioGuardPrivate(host)) return 'private';
  if (studioGuardTorrent(firstBytes)) return 'torrent';
  if (who) {
    const now = Date.now();
    let r = GUARD_RATE.get(who);
    if (!r || now - r.start > GUARD_RATE_WINDOW_MS) { r = { start: now, n: 0 }; GUARD_RATE.set(who, r); }
    if (++r.n > GUARD_RATE_MAX) return 'flood';
  }
  return null;
}
