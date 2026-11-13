"""Piste 2.1 de l'issue #40 : simulation de la marge courte avec reprise, à partir d'un TSV de `banc_rapide.py`.

usage : python reprise.py MESURES.tsv [REF=turbo] [COURTE[/REPRISE] ...]
COURTE/REPRISE : reprise sur la variante REPRISE au lieu de REF (ex. c300/ctx256 pour la combinaison du cache court
et de la marge courte) ; les temps restent comparés à REF.

Le TSV doit contenir, pour chaque clip, la variante de référence (marge 1 000) et les variantes à marge courte, mesurées
dans la même exécution en alternance. Stratégie simulée pour chaque variante courte : décoder à la marge courte ; si
`repetition()` signale un défaut, redécoder à 1 000 (temps = les deux décodages, texte = celui à 1 000).

Détecteur (règles fixées avant de regarder le jeu de 172 extraits, à partir des défauts connus de la section 1 du rapport
et de la description de l'issue) :
  1. jetons au plafond de sherpa (6 par seconde d'audio), à une unité près ;
  2. texte = X + X (mots normalisés), quelle que soit la longueur de X ;
  3. un bloc d'au moins 3 mots répété immédiatement (« … rebelles. La sorcière décide … La sorcière décide … ») ;
  4. la fin reprend le début (« Du reste sa mère. Du ») : les k derniers mots égaux aux k premiers, k ≥ 1, texte de plus
     de 2k mots, sans ponctuation finale.
Une reprise de trop ne coûte que du temps (le texte à 1 000 est la référence) ; une reprise manquée coûte du WER."""
import collections
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wer import errs, norm  # noqa: E402


def repetition(text, jetons, plafond):
    """Retourne le nom de la règle déclenchée, ou None."""
    if plafond > 0 and jetons >= plafond - 1:
        return "plafond"
    w = norm(text)
    n = len(w)
    if n >= 2 and n % 2 == 0 and w[: n // 2] == w[n // 2:]:
        return "X+X"
    for L in range(3, n // 2 + 1):
        for i in range(0, n - 2 * L + 1):
            if w[i:i + L] == w[i + L:i + 2 * L]:
                return "bloc répété"
    t = text.rstrip()
    if t and t[-1] not in ".?!…":
        for k in range(1, n // 2):
            if n > 2 * k and w[-k:] == w[:k]:
                return "fin = début"
    return None


def main(path, ref="turbo", courtes=None):
    rows = collections.defaultdict(dict)
    for i, line in enumerate(open(path, encoding="utf-8")):
        if i == 0:
            continue
        c = line.rstrip("\n").split("\t")
        rows[c[0]][c[1]] = dict(t=float(c[3]), jetons=int(c[5]), plafond=int(c[6]), ref=c[7], hyp=c[8])
    clips = sorted(rows)
    variantes = sorted({v for r in rows.values() for v in r} - {ref}) if not courtes else courtes
    t_ref = sum(rows[c][ref]["t"] for c in clips)
    e_ref = sum(errs(rows[c][ref]["ref"], rows[c][ref]["hyp"])[0] for c in clips)
    mots = sum(errs(rows[c][ref]["ref"], rows[c][ref]["hyp"])[1] for c in clips)
    print(f"{len(clips)} clips, {mots} mots ; référence {ref} : {t_ref:.1f} s, WER {e_ref}/{mots} = {100*e_ref/mots:.1f} %")
    print("| Variante | Temps seul | WER seule | Reprises (règles) | Temps avec reprise | WER avec reprise | "
          "Sorties sans reprise ≠ 1 000 | dont répétition manquée | Oracle : temps / WER |")
    print("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for spec in variantes:
        v, _, rv = spec.partition("/")
        rv = rv or ref
        t_seul = sum(rows[c][v]["t"] for c in clips)
        e_seul = sum(errs(rows[c][v]["ref"], rows[c][v]["hyp"])[0] for c in clips)
        t_rep, e_rep, regles, diff, manque = 0.0, 0, collections.Counter(), [], 0
        t_or, e_or = 0.0, 0
        for c in clips:
            r, b = rows[c][v], rows[c][rv]
            rule = repetition(r["hyp"], r["jetons"], r["plafond"])
            if rule:
                regles[rule] += 1
                t_rep += r["t"] + b["t"]
                e_rep += errs(b["ref"], b["hyp"])[0]
            else:
                t_rep += r["t"]
                e_rep += errs(r["ref"], r["hyp"])[0]
                if norm(r["hyp"]) != norm(b["hyp"]):
                    diff.append(c)
                    if len(norm(r["hyp"])) > len(norm(b["hyp"])) + 2:
                        manque += 1
            # oracle : reprise exactement quand la sortie courte diffère de celle à 1 000
            if norm(r["hyp"]) != norm(b["hyp"]):
                t_or += r["t"] + b["t"]
            else:
                t_or += r["t"]
            e_or += errs(b["ref"], b["hyp"])[0]
        nrep = sum(regles.values())
        rl = ", ".join(f"{k} {n}" for k, n in regles.most_common())
        print(f"| {spec} | {t_seul:.1f} s ({100*(t_seul/t_ref-1):+.1f} %) | {100*e_seul/mots:.1f} % | "
              f"{nrep} ({100*nrep/len(clips):.0f} % ; {rl}) | {t_rep:.1f} s ({100*(t_rep/t_ref-1):+.1f} %) | "
              f"{100*e_rep/mots:.1f} % | {len(diff)} | {manque} | {t_or:.1f} s ({100*(t_or/t_ref-1):+.1f} %) / "
              f"{100*e_or/mots:.1f} % |")
    if os.environ.get("DETAIL"):
        for v in (x.partition("/")[0] for x in variantes):
            for c in clips:
                r, b = rows[c][v], rows[c][ref]
                rule = repetition(r["hyp"], r["jetons"], r["plafond"])
                if rule or norm(r["hyp"]) != norm(b["hyp"]):
                    print(f"{v}|{c}|{rule}|{r['jetons']}/{r['plafond']}|{r['hyp']}|1000: {b['hyp']}")


if __name__ == "__main__":
    main(sys.argv[1], *(sys.argv[2:3] or ["turbo"]), courtes=sys.argv[3:] or None)
