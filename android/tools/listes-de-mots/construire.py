#!/usr/bin/env python3
"""Builds the word lists of the text keyboard's suggestions (issue #71, lot 1).

Source: the AOSP "combined" word lists shipped in AnySoftKeyboard's language packs (Apache License 2.0),
read at a fixed commit. Each line of a source looks like ` word=maison,f=180,flags=,originalFreq=180`;
`possibly_offensive=true` marks a word never offered as a suggestion.

Output, one file per keyboard language: app/src/main/assets/words/<lang>.txt.gzip (gzip; not `.gz`, which the
Android Gradle plugin unpacks and renames in the APK), UTF-8 lines

    word<TAB>frequency            frequency 1-255, as in the source
    word<TAB>frequency<TAB>x      x: possibly offensive

sorted by the folded word (lowercase, accents removed, œ -> oe), then by the word. `Lexicon` searches the
lines in that order; `WordListAssetsTest` checks the order with the Kotlin folding, so the two cannot drift.
Words whose folded form is not made of a-z, apostrophe and hyphen are left out (digits, other scripts).

Usage:  python3 tools/listes-de-mots/construire.py <directory holding the downloaded *.combined.gz>
The files are downloaded from
https://raw.githubusercontent.com/AnySoftKeyboard/AnySoftKeyboard/<COMMIT>/addons/languages/<path>
"""
import gzip
import io
import pathlib
import re
import sys
import unicodedata

COMMIT = "1ee43e7f037b905e1c762e3beceac1c0178797b6"
SOURCES = {
    "fr": ["french/pack/dictionary/fr_wordlist.combined.gz"],
    "en": ["english/pack/dictionary/en_wordlist.combined.gz"],
    "es": ["spain/pack/dictionary/es_wordlist.combined.gz"],
    # One Portuguese keyboard language: Brazil and Portugal together, the higher frequency when in both.
    "pt": ["brazilian/pack/dictionary/pt_BR_wordlist.combined.gz", "portuguese/pack/dictionary/pt_PT_wordlist.combined.gz"],
}
# Same table as WordFold.SPECIAL in Kotlin.
SPECIAL = {"œ": "oe", "æ": "ae", "ß": "ss", "’": "'"}
LINE = re.compile(r"^ word=(.*?),f=(\d+),flags=([^,]*)(.*)$")
FOLDED = re.compile(r"^[a-z][a-z'\-]*$")


def fold(word: str) -> str:
    out = []
    for c in word.lower():
        if c in SPECIAL:
            out.append(SPECIAL[c])
            continue
        out.extend(ch for ch in unicodedata.normalize("NFD", c) if not unicodedata.combining(ch))
    return "".join(out)


def read(path: pathlib.Path) -> dict:
    words = {}
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            m = LINE.match(line.rstrip("\n"))
            if not m:
                continue
            word, freq, rest = m.group(1), int(m.group(2)), m.group(4)
            if freq <= 0 or len(word) > 32 or not FOLDED.match(fold(word)):
                continue
            offensive = "possibly_offensive=true" in rest
            old = words.get(word)
            if old is None or freq > old[0]:
                words[word] = (min(freq, 255), offensive or (old is not None and old[1]))
    return words


def main() -> None:
    src = pathlib.Path(sys.argv[1])
    out = pathlib.Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "assets" / "words"
    out.mkdir(parents=True, exist_ok=True)
    for lang, sources in SOURCES.items():
        merged = {}
        for s in sources:
            for word, (freq, offensive) in read(src / pathlib.Path(s).name).items():
                old = merged.get(word)
                if old is None or freq > old[0]:
                    merged[word] = (freq, offensive or (old is not None and old[1]))
        rows = sorted(merged.items(), key=lambda kv: (fold(kv[0]), kv[0]))
        buf = io.BytesIO()
        # mtime=0 and no file name: the same sources give the same bytes.
        with gzip.GzipFile(filename="", fileobj=buf, mode="wb", compresslevel=9, mtime=0) as gz:
            for word, (freq, offensive) in rows:
                gz.write(f"{word}\t{freq}{chr(9) + 'x' if offensive else ''}\n".encode("utf-8"))
        (out / f"{lang}.txt.gzip").write_bytes(buf.getvalue())
        print(f"{lang}: {len(rows)} words, {len(buf.getvalue()) / 1e6:.2f} MB")


if __name__ == "__main__":
    main()
