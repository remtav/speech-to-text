#!/usr/bin/env python3
"""Builds the word lists of the text keyboard (issue #71) from AnySoftKeyboard's language packs.

The packs hold the AOSP "combined" word lists (word, frequency 0-255, flags), under the Apache
License 2.0. This script keeps what the keyboard needs and writes one small file per language:

    app/src/main/assets/words/<lang>.tsv.gz    lines "word<TAB>frequency[<TAB>x]", x = possibly offensive

Lines are sorted by the folded word (lowercase, no accent), the order `Lexicon` searches in.
`LexiconAssetsTest` checks that order with the Kotlin folding, so the two cannot drift apart.

Usage:  python3 tools/mots/build_wordlists.py <path to an AnySoftKeyboard checkout>
The checkout used for the committed files: commit 1ee43e7f037b905e1c762e3beceac1c0178797b6.
"""
import gzip
import io
import pathlib
import re
import sys
import unicodedata

SOURCES = {
    "fr": ["french/pack/dictionary/fr_wordlist.combined.gz"],
    "en": ["english/pack/dictionary/en_wordlist.combined.gz"],
    "es": ["spain/pack/dictionary/es_wordlist.combined.gz"],
    # One Portuguese keyboard language: both lists, the higher frequency when a word is in both.
    "pt": ["brazilian/pack/dictionary/pt_BR_wordlist.combined.gz", "portuguese/pack/dictionary/pt_PT_wordlist.combined.gz"],
}
SPECIAL = {"œ": "o", "æ": "a", "ß": "s", "ø": "o", "đ": "d", "ł": "l", "’": "'"}
LINE = re.compile(r"^ word=(.*?),f=(\d+),flags=([^,]*)(.*)$")


def fold_char(ch: str) -> str:
    low = ch.lower()
    if len(low) != 1:
        return ch
    if low in SPECIAL:
        return SPECIAL[low]
    return unicodedata.normalize("NFD", low)[0]


def fold(word: str) -> str:
    return "".join(fold_char(c) for c in word)


def usable(word: str) -> bool:
    if not word or len(word) > 40:
        return False
    for c in word:
        if c in "'-":
            continue
        if not c.isalpha() or ord(c) >= 0x250 or len(c.lower()) != 1:
            return False
    return word[0].isalpha()


def read(path: pathlib.Path) -> dict:
    words = {}
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            m = LINE.match(line.rstrip("\n"))
            if not m:
                continue
            word, freq, rest = m.group(1), int(m.group(2)), m.group(4)
            if freq <= 0 or not usable(word):
                continue
            offensive = "possibly_offensive=true" in rest
            old = words.get(word)
            if old is None or freq > old[0]:
                words[word] = (freq, offensive or (old is not None and old[1]))
    return words


def main() -> None:
    ask = pathlib.Path(sys.argv[1]) / "addons" / "languages"
    out = pathlib.Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "assets" / "words"
    out.mkdir(parents=True, exist_ok=True)
    for lang, sources in SOURCES.items():
        merged = {}
        for s in sources:
            for word, (freq, offensive) in read(ask / s).items():
                old = merged.get(word)
                if old is None or freq > old[0]:
                    merged[word] = (freq, offensive or (old is not None and old[1]))
        rows = sorted(merged.items(), key=lambda kv: (fold(kv[0]), kv[0]))
        buf = io.BytesIO()
        # mtime=0 and no file name: the same input gives the same bytes.
        with gzip.GzipFile(filename="", fileobj=buf, mode="wb", compresslevel=9, mtime=0) as gz:
            for word, (freq, offensive) in rows:
                gz.write(f"{word}\t{freq}{chr(9) + 'x' if offensive else ''}\n".encode("utf-8"))
        (out / f"{lang}.tsv.gz").write_bytes(buf.getvalue())
        print(f"{lang}: {len(rows)} words, {len(buf.getvalue()) / 1e6:.2f} MB")


if __name__ == "__main__":
    main()
