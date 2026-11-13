"""Piste 1.2 de l'issue #40 : cache du décodeur de turbo raccourci de 448 à N positions, sans réexport.

usage : python cache_court.py ENC_IN DEC_IN ENC_OUT DEC_OUT [N=256]

Pourquoi sans réexport : dans le décodeur produit par `scripts/whisper/export-onnx.py` de sherpa-onnx, le cache n'est
lu et écrit que par tranches `[:, :, : offset + n_tokens]` (Slice et ScatterND dont les bornes viennent de `offset`),
le plongement de position est indexé par `offset` et le masque causal est découpé à la longueur de la requête. La taille
448 n'apparaît que dans la forme déclarée des entrées et sorties du cache. sherpa-onnx 2.0.1 crée le cache initial à
la taille lue dans la métadonnée `n_text_ctx` de l'encodeur (`offline-whisper-model.cc`, `GetInitialSelfKVCache`), arrête
le décodage à `offset >= n_text_ctx - 1` et plafonne la sortie à `n_text_ctx / 2` jetons
(`offline-whisper-greedy-search-decoder.cc`). Il suffit donc de changer ces formes et cette métadonnée.

Effet attendu : 2 × 4 couches × N × 1 280 flottants copiés à chaque jeton (entrée puis sortie du ScatterND) au lieu de
448 positions : 10,5 Mo au lieu de 18,4 Mo pour N = 256. Plafond de sortie : 128 jetons (21 s d'audio à 6 jetons par
seconde ; l'app coupe les segments à 20 s)."""
import sys

import onnx


def main(enc_in, dec_in, enc_out, dec_out, n=256):
    dec = onnx.load(dec_in)
    changed = 0
    for vi in list(dec.graph.input) + list(dec.graph.output):
        if "self_k_cache" in vi.name or "self_v_cache" in vi.name:
            d = vi.type.tensor_type.shape.dim[2]
            assert d.dim_value == 448, (vi.name, d)
            d.dim_value = n
            changed += 1
    assert changed == 4, changed
    # formes intermédiaires inférées avec 448 : on les retire, onnxruntime les recalcule
    del dec.graph.value_info[:]
    for init in dec.graph.initializer:
        if 448 in init.dims and "positional_embedding" not in init.name:
            print("initialiseur de dimension 448 (vérifier) :", init.name, list(init.dims))
    onnx.save(dec, dec_out)

    enc = onnx.load(enc_in, load_external_data=True)
    for p in enc.metadata_props:
        if p.key == "n_text_ctx":
            assert p.value == "448", p.value
            p.value = str(n)
    big = enc.ByteSize() > 2_000_000_000 if hasattr(enc, "ByteSize") else False
    onnx.save(enc, enc_out, save_as_external_data=big)
    print(f"décodeur : 4 formes de cache 448 → {n} ; encodeur : n_text_ctx = {n}")


if __name__ == "__main__":
    main(*sys.argv[1:5], *(int(x) for x in sys.argv[5:6]))
