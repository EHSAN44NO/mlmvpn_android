#!/usr/bin/env node
/*
 * GitHub Tunnel: copy the SERVER side from the Windows sources into this app's assets.
 *
 * Both apps drive the same cloud machinery on the user's own accounts — the v2 workflow and its
 * agent in the GitHub repository `mlmvpn-cloud-tunnel`, and the relay Worker on Cloudflare. The
 * workflow and the agent are pushed into ONE repository per GitHub account, by whichever app
 * starts a session; if the two apps carried different copies, each would overwrite the other's
 * on every session. So there is one source — ../github-tunnel and ../cloudflare-worker in the
 * Windows repository — and this script is the only way the Android copy is made.
 *
 *   node scripts/sync-gt-assets.js          (from android/)
 *
 * Writes app/src/main/assets/gt/: workflow-v2.yml, agent/*.mjs, broker_worker.js, manifest.json.
 * The output is committed, so building the app never needs the Windows tree.
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ANDROID = path.resolve(__dirname, '..');
const WIN = path.resolve(ANDROID, '..');
const OUT = path.join(ANDROID, 'app', 'src', 'main', 'assets', 'gt');

const tpl = require(path.join(WIN, 'github-tunnel', 'gt-workflow-template.js'));
const workerSrc = path.join(WIN, 'cloudflare-worker', 'gt-broker', 'worker.js');

const files = [];
function write(rel, content) {
    const dst = path.join(OUT, rel);
    fs.mkdirSync(path.dirname(dst), { recursive: true });
    fs.writeFileSync(dst, content, 'utf8');
    files.push({ file: rel, sha256: crypto.createHash('sha256').update(content, 'utf8').digest('hex') });
}

write('workflow-v2.yml', tpl.buildWorkflowYamlV2());
const agent = tpl.agentFiles();
for (const [repoPath, content] of Object.entries(agent)) write(repoPath, content);

const worker = fs.readFileSync(workerSrc, 'utf8');
write('broker_worker.js', worker);
const workerVersion = Number((worker.match(/WORKER_VERSION\s*=\s*(\d+)/) || [])[1] || 0);

const manifest = {
    // Where each asset goes in the user's repository (the workflow path is fixed by GitHub).
    workflow: { repoPath: tpl.WORKFLOW_V2_PATH, file: 'workflow-v2.yml', name: tpl.WORKFLOW_V2_FILENAME },
    agent: Object.keys(agent).map((repoPath) => ({ repoPath, file: repoPath })),
    usableSessionMinutes: tpl.USABLE_SESSION_MINUTES,
    workerVersion,
    files,
};
write('manifest.json', JSON.stringify(manifest, null, 2));
console.log(`gt assets: ${files.length} files → ${path.relative(ANDROID, OUT)} (worker v${workerVersion}, ${tpl.USABLE_SESSION_MINUTES} usable minutes)`);
