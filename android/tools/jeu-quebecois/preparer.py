#!/usr/bin/env python3
"""Prépare le jeu d'extraits québécois (issue #24) : Common Voice 22.0 fr, partie `test`, CC0.

Filtre : colonne `accents` contenant « Canada », « Québec » ou « québ », et pas « France ».
Sélection : 3 clips au plus par locuteur (`client_id`), dans l'ordre du fichier.
Sortie : un WAV 16 kHz mono par clip + `references.tsv` (fichier, langue, transcription, source).
Le jeu n'est ni versionné ni inclus dans l'APK : il est produit dans le dossier donné.

usage : python preparer.py --sortie DOSSIER [--tsv URL_OU_CHEMIN] [--tar URL_OU_CHEMIN]
Dépendance : ffmpeg dans le PATH (bibliothèque standard Python uniquement).
Idempotent : un WAV déjà présent n'est pas reproduit ; l'archive n'est parcourue que s'il en manque.
"""
import argparse
import csv
import io
import os
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import wave

BASE = "https://huggingface.co/datasets/fsicoli/common_voice_22_0/resolve/main"
TSV = f"{BASE}/transcript/fr/test.tsv"
TAR = f"{BASE}/audio/fr/test/fr_test_0.tar"
PAR_LOCUTEUR = 3
SOURCE = ("Common Voice 22.0 fr, test split (CC0), clip {clip}, accents=\"{accents}\", "
          "via huggingface.co/datasets/fsicoli/common_voice_22_0 audio/fr/test/fr_test_0.tar")


def est_quebecois(accents):
    a = accents.lower()
    return ("canada" in a or "québec" in a or "québ" in a) and "france" not in a


def selectionner(lignes, par_locuteur=PAR_LOCUTEUR):
    """lignes : dicts du TSV Common Voice. Retourne les clips retenus, dans l'ordre du fichier."""
    vus, retenus = {}, []
    for r in lignes:
        if not est_quebecois(r.get("accents", "")):
            continue
        if vus.get(r["client_id"], 0) >= par_locuteur:
            continue
        vus[r["client_id"]] = vus.get(r["client_id"], 0) + 1
        retenus.append(r)
    return retenus


def nom_wav(clip):
    return os.path.splitext(os.path.basename(clip))[0] + ".wav"


def ouvrir(src):
    if src.startswith(("http://", "https://")):
        return urllib.request.urlopen(src)
    return open(src, "rb")


def lire_tsv(src):
    csv.field_size_limit(10 ** 7)
    with ouvrir(src) as f:
        texte = io.TextIOWrapper(f, encoding="utf-8", newline="")
        return list(csv.DictReader(texte, delimiter="\t", quoting=csv.QUOTE_NONE))


def convertir(mp3, wav):
    tmp = wav + ".part.wav"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", "-", "-ar", "16000", "-ac", "1",
                    "-c:a", "pcm_s16le", tmp], input=mp3, check=True)
    os.replace(tmp, wav)


def extraire(src_tar, retenus, sortie):
    manque = {r["path"]: os.path.join(sortie, nom_wav(r["path"])) for r in retenus
              if not os.path.exists(os.path.join(sortie, nom_wav(r["path"])))}
    if not manque:
        return
    print(f"{len(manque)} clip(s) à extraire de l'archive (lecture en flux)…", file=sys.stderr)
    with ouvrir(src_tar) as f, tarfile.open(fileobj=f, mode="r|*") as tar:
        for m in tar:
            nom = os.path.basename(m.name)
            if nom in manque and m.isfile():
                convertir(tar.extractfile(m).read(), manque.pop(nom))
                if not manque:
                    break  # le reste de l'archive (687 Mo) n'est pas lu
    if manque:
        raise SystemExit(f"clips absents de l'archive : {sorted(manque)[:5]}…")


def ecrire_references(retenus, sortie):
    tmp = os.path.join(sortie, "references.tsv.part")
    with open(tmp, "w", encoding="utf-8", newline="") as f:
        f.write("# fichier\tlangue\ttranscription\tsource\n")
        for r in retenus:
            texte = " ".join(r["sentence"].split())
            f.write(f"{nom_wav(r['path'])}\tfr\t{texte}\t"
                    f"{SOURCE.format(clip=r['path'], accents=r['accents'])}\n")
    os.replace(tmp, os.path.join(sortie, "references.tsv"))


def resume(retenus, sortie):
    total = 0.0
    for r in retenus:
        with wave.open(os.path.join(sortie, nom_wav(r["path"]))) as w:
            assert w.getframerate() == 16000 and w.getnchannels() == 1
            total += w.getnframes() / 16000
    mots = sum(len(r["sentence"].split()) for r in retenus)
    print(f"{len(retenus)} clips, {len({r['client_id'] for r in retenus})} locuteurs, "
          f"{total / 60:.1f} min, {mots} mots → {sortie}")


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--sortie", required=True, help="dossier de sortie (créé au besoin)")
    p.add_argument("--tsv", default=TSV, help="transcriptions (URL ou chemin)")
    p.add_argument("--tar", default=TAR, help="archive audio (URL ou chemin)")
    a = p.parse_args(argv)
    if not shutil.which("ffmpeg"):
        raise SystemExit("ffmpeg est introuvable dans le PATH")
    os.makedirs(a.sortie, exist_ok=True)
    retenus = selectionner(lire_tsv(a.tsv))
    extraire(a.tar, retenus, a.sortie)
    ecrire_references(retenus, a.sortie)
    resume(retenus, a.sortie)


if __name__ == "__main__":
    main()
