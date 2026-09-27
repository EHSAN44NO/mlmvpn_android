#!/usr/bin/env node
/**
 * Refresh the VPN Gate list shipped in the APK (the gateway's offline copy on a fresh install).
 *
 *   node scripts/update-vpngate-seed.js [path-to-a-csv]
 *
 * With no argument it fetches VPN Gate's own API; with one it takes that file (for a machine that
 * cannot reach vpngate.net). Writes app/src/main/assets/vpngate/default_servers.csv and, beside
 * it, default_servers.fetched -- the epoch-ms stamp the app shows as the list's age. A list with
 * fewer than 20 servers is refused rather than shipped.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const http = require('http');

const API = 'http://www.vpngate.net/api/iphone/';
const DIR = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'vpngate');
const MIN_SERVERS = 20;

function fetch(url) {
    return new Promise((resolve, reject) => {
        const req = http.get(url, { timeout: 60000 }, (res) => {
            if (res.statusCode !== 200) { res.resume(); return reject(new Error('HTTP ' + res.statusCode)); }
            const chunks = [];
            res.on('data', (c) => chunks.push(c));
            res.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
        });
        req.on('timeout', () => req.destroy(new Error('timeout')));
        req.on('error', reject);
    });
}

/** Rows with a hostname, an IPv4 and a non-empty profile (column 15). */
function countServers(csv) {
    return csv.split(/\r?\n/).filter((line) => {
        if (!line || line.startsWith('*') || line.startsWith('#')) return false;
        const cols = line.split(',');
        return cols.length >= 15 && /^\d+\.\d+\.\d+\.\d+$/.test(cols[1]) && cols[14].length > 100;
    }).length;
}

(async () => {
    const source = process.argv[2];
    const csv = source ? fs.readFileSync(source, 'utf8') : await fetch(API);
    const n = countServers(csv);
    if (n < MIN_SERVERS) throw new Error(`only ${n} servers in the list; not shipping it`);
    fs.mkdirSync(DIR, { recursive: true });
    fs.writeFileSync(path.join(DIR, 'default_servers.csv'), csv);
    const stamp = source ? fs.statSync(source).mtimeMs : Date.now();
    fs.writeFileSync(path.join(DIR, 'default_servers.fetched'), String(Math.round(stamp)));
    console.log(`${n} servers written, stamped ${new Date(stamp).toISOString()}`);
})().catch((e) => { console.error(e.message); process.exit(1); });
