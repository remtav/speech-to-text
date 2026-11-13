"""Normalisation et distance d'édition en mots, identiques à `../whisper-mesure-python/banc_lite.py` (issue #25)."""
import re
import unicodedata


def norm(s):
    s = unicodedata.normalize("NFC", s.lower()).replace("’", "'")
    s = re.sub(r"[^\w'\- ]+", " ", s).replace("-", " ")
    return s.split()


def errs(ref, hyp):
    r, h = norm(ref), norm(hyp)
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
            prev, d[j] = d[j], cur
    return d[len(h)], len(r)
