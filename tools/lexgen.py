"""Builds the keyboard word lists from wordfreq (data: CC BY-SA 4.0), Romanian topped up from FrequencyWords.
Usage: lexgen.py <wordfreq dir> <out dir> [probe | ro_full.txt]"""
import re
import sys
from pathlib import Path

sys.path.insert(0, sys.argv[1])
from wordfreq import top_n_list, zipf_frequency  # noqa: E402

out = Path(sys.argv[2])
SIZES = {'ro': 60000, 'en': 50000, 'ru': 90000}
LETTERS = {
    'ro': re.compile(r"^[a-zăâîșț]+$"),
    'en': re.compile(r"^[a-z]+('[a-z]+)?$"),
    'ru': re.compile(r"^[а-яё]+$"),
}


def norm(lang, w):
    w = w.lower()
    if lang == 'ro':  # old cedilla forms -> correct comma-below
        w = w.replace('ş', 'ș').replace('ţ', 'ț')
    return w


if len(sys.argv) > 3 and sys.argv[3] == 'probe':
    for lang, words in [('ro', 'si și sa să in în ca că ce cse este eşti ești casa casă fata fată'), ('en', 'in the teh'), ('ru', 'еще ещё все всё привет')]:
        print(lang, [(w, zipf_frequency(w, lang)) for w in words.split()])
    sys.exit()

out.mkdir(parents=True, exist_ok=True)
for lang, n in SIZES.items():
    best = {}
    for w in top_n_list(lang, int(n * 1.6)):
        k = norm(lang, w)
        if not LETTERS[lang].match(k):
            continue
        if lang == 'en' and len(k) == 1 and k not in ('a', 'i'):
            continue
        z = int(round(zipf_frequency(w, lang) * 100))
        best[k] = max(best.get(k, 0), z)
    items = sorted(best.items(), key=lambda x: -x[1])[:n]
    if lang == 'ro' and len(sys.argv) > 3 and sys.argv[3] != 'probe':
        # wordfreq has only ~43k Romanian words: add the inflected forms it lacks (rusește, vorbeam…) from
        # FrequencyWords (OpenSubtitles 2018, CC BY-SA 4.0), at the frequency they have there.
        import math
        known = dict(items)
        rows = [l.rsplit(' ', 1) for l in open(sys.argv[3], encoding='utf-8')]
        total = sum(int(c) for _, c in rows)
        extra = {}
        for w, c in rows:
            k = norm('ro', w)
            if int(c) < 10 or k in known or not LETTERS['ro'].match(k) or len(k) < 2:
                continue
            z = int(round(math.log10(int(c) / total * 1e9) * 100))
            extra[k] = max(extra.get(k, 0), z)
        items = sorted(list(known.items()) + list(extra.items()), key=lambda x: -x[1])
        print('ro: +', len(extra), 'words from FrequencyWords')
    with open(out / f'lex_{lang}.txt', 'w', encoding='utf-8', newline='\n') as f:
        for w, z in items:
            f.write(f'{w}\t{z}\n')
    print(lang, len(items), items[:5], items[-2:])
