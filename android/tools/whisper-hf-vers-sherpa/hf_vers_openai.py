#!/usr/bin/env python3
"""Convertit un Whisper au format transformers (model.safetensors + config.json)
en point de contrôle au format OpenAI (.pt) que lit `openai-whisper`.

Usage : python hf_vers_openai.py DOSSIER_HF SORTIE.pt
"""
import json
import re
import sys
from pathlib import Path

import torch
from safetensors.torch import load_file


def main(src: Path, out: Path) -> None:
    cfg = json.loads((src / "config.json").read_text())
    dims = {
        "n_mels": cfg["num_mel_bins"],
        "n_vocab": cfg["vocab_size"],
        "n_audio_ctx": cfg["max_source_positions"],
        "n_audio_state": cfg["d_model"],
        "n_audio_head": cfg["encoder_attention_heads"],
        "n_audio_layer": cfg["encoder_layers"],
        "n_text_ctx": cfg["max_target_positions"],
        "n_text_state": cfg["d_model"],
        "n_text_head": cfg["decoder_attention_heads"],
        "n_text_layer": cfg["decoder_layers"],
    }
    hf = load_file(str(src / "model.safetensors"))
    rules = [
        (r"^model\.encoder\.embed_positions\.weight$", "encoder.positional_embedding"),
        (r"^model\.encoder\.layer_norm\.", "encoder.ln_post."),
        (r"^model\.decoder\.embed_tokens\.weight$", "decoder.token_embedding.weight"),
        (r"^model\.decoder\.embed_positions\.weight$", "decoder.positional_embedding"),
        (r"^model\.decoder\.layer_norm\.", "decoder.ln."),
        (r"^model\.(encoder|decoder)\.layers\.(\d+)\.", r"\1.blocks.\2."),
        (r"\.self_attn\.q_proj\.", ".attn.query."),
        (r"\.self_attn\.k_proj\.", ".attn.key."),
        (r"\.self_attn\.v_proj\.", ".attn.value."),
        (r"\.self_attn\.out_proj\.", ".attn.out."),
        (r"\.self_attn_layer_norm\.", ".attn_ln."),
        (r"\.encoder_attn\.q_proj\.", ".cross_attn.query."),
        (r"\.encoder_attn\.k_proj\.", ".cross_attn.key."),
        (r"\.encoder_attn\.v_proj\.", ".cross_attn.value."),
        (r"\.encoder_attn\.out_proj\.", ".cross_attn.out."),
        (r"\.encoder_attn_layer_norm\.", ".cross_attn_ln."),
        (r"\.fc1\.", ".mlp.0."),
        (r"\.fc2\.", ".mlp.2."),
        (r"\.final_layer_norm\.", ".mlp_ln."),
        (r"^model\.(encoder\.conv[12]\.)", r"\1"),
    ]
    state = {}
    for key, tensor in hf.items():
        if key == "proj_out.weight":  # lié à decoder.embed_tokens
            continue
        new = key
        for pat, rep in rules:
            new = re.sub(pat, rep, new)
        if new.startswith("model."):
            raise SystemExit(f"clé non convertie : {key}")
        state[new] = tensor.float()
    torch.save({"dims": dims, "model_state_dict": state}, out)
    print(f"{len(state)} tenseurs, dims={dims} -> {out}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    main(Path(sys.argv[1]), Path(sys.argv[2]))
