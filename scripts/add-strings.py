# -*- coding: utf-8 -*-
"""Add or update string pairs in both catalogues at once.

    python scripts/add-strings.py <file.tsv>

Each line of the TSV is `key<TAB>english<TAB>persian`. A key already present in a catalogue has its
text replaced; a new key is appended before `</resources>`. Apostrophes are escaped (`\\'`) and XML
metacharacters are encoded, so text can be written as it reads.

Both files, every time: `checkStringParity` fails the build when one catalogue has a key the other
does not, and adding strings by hand to two 4,000-line files is how that happens.
"""
import io, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EN = os.path.join(ROOT, 'app/src/main/res/values/strings.xml')
FA = os.path.join(ROOT, 'app/src/main/res/values-fa/strings.xml')


def esc(text):
    text = text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    text = text.replace("\\'", "'").replace("'", "\\'")
    text = text.replace('"', '\\"')
    return text


def apply(path, pairs):
    s = io.open(path, encoding='utf-8').read()
    added = replaced = 0
    for key, text in pairs:
        line = '    <string name="%s">%s</string>' % (key, esc(text))
        pat = re.compile(r'^\s*<string name="%s"[^>]*>.*?</string>\s*$' % re.escape(key), re.M | re.S)
        if pat.search(s):
            s = pat.sub(lambda m: line, s, count=1)
            replaced += 1
        else:
            s = s.replace('</resources>', line + '\n</resources>', 1)
            added += 1
    io.open(path, 'w', encoding='utf-8', newline='\n').write(s)
    return added, replaced


def main():
    rows = []
    for raw in io.open(sys.argv[1], encoding='utf-8'):
        raw = raw.rstrip('\n')
        if not raw.strip() or raw.startswith('#'):
            continue
        parts = raw.split('\t')
        if len(parts) != 3:
            sys.exit('bad line (need key<TAB>en<TAB>fa): %r' % raw)
        rows.append(parts)
    en = apply(EN, [(k, e) for k, e, _ in rows])
    fa = apply(FA, [(k, f) for k, _, f in rows])
    print('values: %d added, %d replaced; values-fa: %d added, %d replaced' % (en + fa))


if __name__ == '__main__':
    main()
