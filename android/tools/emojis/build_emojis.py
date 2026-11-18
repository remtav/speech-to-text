#!/usr/bin/env python3
"""Produit app/src/main/assets/emojis/emojis.tsv pour le panneau d'émojis (issue #80).

Sources (licence Unicode v3, voir assets/licences/Unicode-LICENSE.txt) :
  - emoji-test.txt, https://unicode.org/Public/emoji/latest/emoji-test.txt : liste, groupes, ordre du clavier ;
  - annotations CLDR en et fr (cldr-json : cldr-annotations-full et cldr-annotations-derived-full) : noms lus par TalkBack.

Usage : build_emojis.py <dossier des sources> <fichier de sortie>
  <dossier des sources> contient emoji-test.txt, ann-en.json, der-en.json, ann-fr.json, der-fr.json.

Une ligne par émoji : groupe <TAB> émoji <TAB> nom anglais <TAB> nom français.
Seuls les émojis « fully-qualified » sont gardés, sans les variantes de couleur de peau (premier lot, voir DECISIONS.md)
ni le groupe « Component ». L'ordre est celui du clavier Unicode.
"""
import json
import sys
from pathlib import Path

TONES = {chr(c) for c in range(0x1F3FB, 0x1F400)}
VS16 = "️"


def names(src: Path, lang: str) -> dict:
    out = {}
    for f, key in ((f"ann-{lang}.json", "annotations"), (f"der-{lang}.json", "annotationsDerived")):
        data = json.load(open(src / f, encoding="utf-8"))[key]["annotations"]
        for emoji, v in data.items():
            if v.get("tts"):
                out[emoji] = v["tts"][0].replace(" ", " ").replace(" ", " ")
    return out


def lookup(table: dict, emoji: str):
    for k in (emoji, emoji.replace(VS16, "")):
        if k in table:
            return table[k]
    return None


def main(src: Path, dest: Path) -> None:
    en, fr = names(src, "en"), names(src, "fr")
    group = None
    rows, seen = [], set()
    for line in open(src / "emoji-test.txt", encoding="utf-8"):
        line = line.rstrip("\n")
        if line.startswith("# group:"):
            group = line.split(":", 1)[1].strip()
            continue
        if not line or line.startswith("#") or group in (None, "Component"):
            continue
        fields, _, comment = line.partition("#")
        codes, status = (x.strip() for x in fields.split(";"))
        if status != "fully-qualified":
            continue
        emoji = "".join(chr(int(c, 16)) for c in codes.split())
        if any(t in emoji for t in TONES) or emoji in seen:
            continue
        seen.add(emoji)
        fallback = comment.split(" ", 2)[2].strip()  # « E1.0 name » après le glyphe
        n_en = lookup(en, emoji) or fallback
        n_fr = lookup(fr, emoji) or n_en
        rows.append(f"{group}\t{emoji}\t{n_en}\t{n_fr}")
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text("\n".join(rows) + "\n", encoding="utf-8")
    print(f"{len(rows)} émojis -> {dest}")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
