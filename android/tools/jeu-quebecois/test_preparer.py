"""Test sur un petit échantillon simulé (TSV + archive de mp3 synthétiques) : pytest test_preparer.py"""
import io
import os
import subprocess
import tarfile
import shutil

import pytest

import preparer

pytestmark = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg requis")

EN_TETE = ["client_id", "path", "sentence_id", "sentence", "sentence_domain", "up_votes",
           "down_votes", "age", "gender", "accents", "variant", "locale", "segment"]


def ligne(client, clip, texte, accents):
    d = dict.fromkeys(EN_TETE, "")
    d.update(client_id=client, path=clip, sentence=texte, accents=accents, locale="fr")
    return "\t".join(d[k] for k in EN_TETE)


@pytest.fixture
def simule(tmp_path):
    lignes = []
    # A : 5 clips québécois (3 retenus) ; B : 1 ; C : Canada + France (exclu) ; D : sans accent (exclu)
    for i in range(5):
        lignes.append(ligne("A", f"common_voice_fr_{i}.mp3", f"Phrase numéro {i}.", "Français du Canada"))
    lignes.append(ligne("B", "common_voice_fr_10.mp3", "Une autre phrase.", "Québécois"))
    lignes.append(ligne("C", "common_voice_fr_11.mp3", "Mixte.", "Français du Canada,France"))
    lignes.append(ligne("D", "common_voice_fr_12.mp3", "Neutre.", ""))
    tsv = tmp_path / "test.tsv"
    tsv.write_text("\t".join(EN_TETE) + "\n" + "\n".join(lignes) + "\n", encoding="utf-8")
    tar = tmp_path / "audio.tar"
    with tarfile.open(tar, "w") as t:
        for n in [0, 1, 2, 3, 4, 10, 11, 12]:
            mp3 = subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=0.5",
                                  "-f", "mp3", "-"], check=True, capture_output=True).stdout
            info = tarfile.TarInfo(f"fr_test_0/common_voice_fr_{n}.mp3")
            info.size = len(mp3)
            t.addfile(info, io.BytesIO(mp3))
    return tsv, tar


def test_selection_filtre_et_plafond_par_locuteur(simule):
    retenus = preparer.selectionner(preparer.lire_tsv(str(simule[0])))
    assert [r["path"] for r in retenus] == [f"common_voice_fr_{n}.mp3" for n in (0, 1, 2, 10)]


def test_production_des_wav_et_de_references_tsv(simule, tmp_path):
    sortie = tmp_path / "sortie"
    args = ["--sortie", str(sortie), "--tsv", str(simule[0]), "--tar", str(simule[1])]
    preparer.main(args)
    assert sorted(p.name for p in sortie.glob("*.wav")) == [f"common_voice_fr_{n}.wav" for n in (0, 1, 10, 2)]
    lignes = (sortie / "references.tsv").read_text(encoding="utf-8").splitlines()
    assert lignes[0].startswith("# fichier")
    champs = [l.split("\t") for l in lignes[1:]]
    assert len(champs) == 4 and all(len(c) == 4 and c[1] == "fr" for c in champs)
    assert champs[0][:3] == ["common_voice_fr_0.wav", "fr", "Phrase numéro 0."]
    # idempotent : deuxième passage, même résultat, même sans l'archive
    avant = (sortie / "references.tsv").read_bytes()
    os.remove(simule[1])
    preparer.main(args)
    assert (sortie / "references.tsv").read_bytes() == avant
