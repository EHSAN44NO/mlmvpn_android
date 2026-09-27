# -*- coding: utf-8 -*-
"""Everything the Gradle verification tasks check, without Gradle.

    python scripts/check-studio.py

`checkStringParity`, both halves of its D6 vocabulary ban, `checkEngineVersion`, and one check
Gradle does not do: every `R.string.<key>` referenced from Kotlin must exist in the catalogue.
That last one is the failure this script was written for -- an `Unresolved reference` from a key
that was used and never added costs a two-minute build to discover.

It exists because `./gradlew` cannot be run in every environment this repo is worked on from: the
Windows username contains a space, so `TEMP` resolves to an 8.3 short path and the daemon dies on
`Unable to establish loopback connection` before it compiles anything. See §2.6 of
`docs/CONFIG-STUDIO-HANDOFF.md` for the incantation that does work when Gradle is available.

This is NOT a substitute for a build: it does not compile Kotlin. It catches the class of mistake
that is expensive to find in a build log and free to find here.
"""
import io, os, re, sys, xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EN = os.path.join(ROOT, 'app/src/main/res/values/strings.xml')
FA = os.path.join(ROOT, 'app/src/main/res/values-fa/strings.xml')
WORKER = os.path.join(ROOT, 'worker-src/studio')
problems = []


def names(path):
    out = []
    for el in ET.parse(path).getroot().iter('string'):
        out.append((el.get('name'), ''.join(el.itertext())))
    return out


en = names(EN)
fa = names(FA)
en_keys = [k for k, _ in en]
fa_keys = [k for k, _ in fa]

for label, keys in (('values', en_keys), ('values-fa', fa_keys)):
    seen, dupes = set(), set()
    for k in keys:
        if k in seen:
            dupes.add(k)
        seen.add(k)
    if dupes:
        problems.append('%s duplicate keys: %s' % (label, sorted(dupes)))

miss_fa = sorted(set(en_keys) - set(fa_keys))
miss_en = sorted(set(fa_keys) - set(en_keys))
if miss_fa:
    problems.append('in values/ but not values-fa/: %s' % miss_fa[:20])
if miss_en:
    problems.append('in values-fa/ but not values/: %s' % miss_en[:20])

BANNED_EN = ['seller', 'sellers', 'customer', 'customers', 'buyer', 'buyers',
             'purchase', 'purchased', 'price', 'pricing']
BANNED_FA = [u'\u0641\u0631\u0648\u0634', u'\u0645\u0634\u062a\u0631\u06cc',
             u'\u062e\u0631\u06cc\u062f', u'\u0642\u06cc\u0645\u062a', u'\u062a\u0648\u0645\u0627\u0646']
for label, rows in (('values', en), ('values-fa', fa)):
    for k, text in rows:
        if not k.startswith('studio_'):
            continue
        low = text.lower()
        for w in BANNED_EN:
            if re.search(r'(?<![a-z])%s(?![a-z])' % w, low):
                problems.append('D6 %s/%s: %r' % (label, k, w))
        for w in BANNED_FA:
            if w in text:
                problems.append('D6 %s/%s: %r' % (label, k, w))


# ---- unescaped apostrophes ----------------------------------------------------------------
#
# An apostrophe in an Android string resource must be written `\'`. A bare one is not a warning:
# aapt refuses the whole file with "Invalid unicode escape sequence", names the resource and not
# the character, and does it during `mergeDebugResources` -- so the build fails before a single
# line of Kotlin is compiled and the message points at the wrong thing.
#
# Cheap to find here for the same reason the R.string check is: it is a whole build to discover,
# and one regex to prevent. A double quote is deliberately NOT checked -- it only carries meaning
# when a value STARTS with one, and several shipping strings quote a word mid-sentence.
APOS_RE = re.compile(r"<string name=\"([^\"]+)\">(.*)</string>")
for path, label in ((EN, 'values'), (FA, 'values-fa')):
    for no, raw in enumerate(io.open(path, encoding='utf-8'), 1):
        m = APOS_RE.search(raw)
        if not m:
            continue
        body = m.group(2)
        for i, ch in enumerate(body):
            if ch == "'" and (i == 0 or body[i - 1] != '\\'):
                problems.append("%s/%s:%d has an unescaped apostrophe; write \\'" % (label, m.group(1), no))
                break

BANNED_SQL = ['price', 'pricing', 'currency', 'paid_at', 'amount_paid',
              'invoice', 'payment', 'customer', 'seller', 'buyer']
for fn in sorted(os.listdir(WORKER)):
    if not fn.endswith('.js'):
        continue
    for no, raw in enumerate(io.open(os.path.join(WORKER, fn), encoding='utf-8'), 1):
        line = re.sub(r'//.*$', '', raw)
        line = re.sub(r'^\s*\*.*$', '', line)
        if not line.strip():
            continue
        low = line.lower()
        for w in BANNED_SQL:
            if re.search(r'(?<![a-z_])%s(?![a-z_])' % w, low):
                problems.append('D6 worker %s:%d: %r' % (fn, no, w))

# ---- checkEngineVersion -------------------------------------------------------------------
kt = io.open(os.path.join(ROOT, 'app/src/main/java/com/mlmvpn/scanner/data/PanelBuild.kt'),
             encoding='utf-8').read()
js = io.open(os.path.join(WORKER, '04a-studio-api.js'), encoding='utf-8').read()
mig = io.open(os.path.join(WORKER, '05a-migrations.js'), encoding='utf-8').read()
app_v = int(re.search(r'const val MLM = (\d+)', kt).group(1))
eng_v = int(re.search(r'const STUDIO_API_VERSION = (\d+)', js).group(1))
if app_v != eng_v:
    problems.append('engine version mismatch: PanelBuild.MLM=%d STUDIO_API_VERSION=%d' % (app_v, eng_v))
app_s = int(re.search(r'const val MLM_SCHEMA = (\d+)', kt).group(1))
eng_s = max(int(m) for m in re.findall(r'\n\s*v:\s*(\d+),', mig))
if app_s != eng_s:
    problems.append('schema mismatch: MLM_SCHEMA=%d worker=%d' % (app_s, eng_s))

# ---- every R.string.<key> referenced from Kotlin must exist --------------------------------
defined = set(en_keys)
used = {}
for dirpath, _, files in os.walk(os.path.join(ROOT, 'app/src/main/java')):
    for fn in files:
        if not fn.endswith('.kt'):
            continue
        path = os.path.join(dirpath, fn)
        text = io.open(path, encoding='utf-8', errors='replace').read()
        # Comments carry example code (`Text(S(R.string.quick_ready_to_connect))` in a KDoc), and
        # an example is not a reference. Stripped the same way the R2 guard strips them.
        text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
        text = re.sub(r'//.*', '', text)
        for m in re.finditer(r'R\.string\.([A-Za-z0-9_]+)', text):
            used.setdefault(m.group(1), path)
missing = sorted(k for k in used if k not in defined)
if missing:
    for k in missing:
        problems.append('R.string.%s is used in %s and defined nowhere'
                        % (k, os.path.relpath(used[k], ROOT)))

# ---- worker asset in step -----------------------------------------------------------------
manifest = io.open(os.path.join(WORKER, 'manifest.json'), encoding='utf-8').read()
asset = os.path.join(ROOT, 'app/src/main/assets/mlm_worker.js')
frag_mtime = max(os.path.getmtime(os.path.join(WORKER, f))
                 for f in os.listdir(WORKER) if f.endswith('.js'))
if os.path.getmtime(asset) < frag_mtime:
    problems.append('mlm_worker.js is older than a fragment; run node scripts/build-studio-worker.js')

if problems:
    print('FAILED (%d)' % len(problems))
    for p in problems:
        print('  - ' + p)
    sys.exit(1)

print('string catalogues: %d keys, in step; worker vocabulary clean' % len(en_keys))
print('engine version: %d, app and worker agree' % app_v)
print('engine schema: %d, app and worker agree' % app_s)
print('R.string references: %d distinct, all defined' % len(used))
print('worker asset: in step with its sources')
