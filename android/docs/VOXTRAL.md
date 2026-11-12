# Voxtral Mini 4B Realtime sur téléphone : constat (lot 3)

Résultat du lot 3 pour Mistral : **pas d'intégration**. Le modèle ne tient pas
dans les cibles du téléphone avec les moyens disponibles ; ce document explique
pourquoi et ce qu'il faudrait pour réessayer.

## Le modèle

- `mistralai/Voxtral-Mini-4B-Realtime-2602`, Apache-2.0, non restreint.
  Encodeur audio + décodeur LLM d'environ 4 milliards de paramètres, conçu pour
  la transcription en flux.
- Poids publiés : 8,86 Go (BF16, safetensors), pour vLLM.
- Il existe un portage GGUF communautaire
  (`handy-computer/Voxtral-Mini-4B-Realtime-2602-gguf`, moteur
  « transcribe.cpp », dérivé de llama.cpp) :

  | Quantification | Taille |
  |---|---|
  | Q4_K_M | 2,83 Go |
  | Q5_K_M | 3,28 Go |
  | Q6_K | 3,66 Go |
  | Q8_0 | 4,73 Go |

  Sa fiche annonce un WER FLEURS de 6,3 % en français et 6,3 % en anglais (Q8_0),
  comparable aux trois modèles déjà intégrés (Parakeet v3 : 8,7 % FR et 11,2 % EN
  sur 300 extraits FLEURS selon sa fiche).

## Pourquoi pas d'intégration

1. **Pas pris en charge par sherpa-onnx** (vérifié dans les sources de la
   version 2.0.1 : aucune mention de Voxtral). Il faudrait un second moteur
   d'inférence : compiler transcribe.cpp/llama.cpp pour Android avec le NDK,
   écrire une couche JNI, et l'exposer via l'interface `SttEngine`. C'est
   faisable (l'architecture le prévoit) mais c'est un projet à part entière, et
   rien ne peut en être validé en CI : le modèle ne tient pas dans l'émulateur.
2. **Mémoire** : même en Q4_K_M, ~2,8 Go de poids plus le cache KV, soit environ
   3,5 Go résidents, plus du double de la cible de 1,5 Go en pointe.
3. **Vitesse** : le décodeur génère un jeton toutes les 80 ms d'audio (12,5
   jetons/s pour suivre le temps réel). Un LLM de 4 B en Q4 sur les cœurs CPU
   d'un Snapdragon 8 Gen 2 produit de l'ordre de 8 à 15 jetons/s avec llama.cpp :
   au mieux le temps réel (RTF ≈ 1), loin de la cible RTF < 0,2. Le GPU Adreno
   (backend OpenCL/Vulkan de llama.cpp) pourrait aider mais reste expérimental
   pour ce type de modèle.
4. **Téléchargement** : 2,8 à 4,7 Go par modèle, contre 0,7 à 1 Go aujourd'hui.

## Pour réessayer

- Attendre (ou proposer) une prise en charge par sherpa-onnx : Voxtral
  deviendrait alors une simple entrée de `ModelCatalog`.
- Ou intégrer transcribe.cpp comme second moteur : module NDK, JNI,
  `VoxtralEngine : SttEngine`, entrée de catalogue avec les fichiers GGUF et
  leurs SHA-256, puis mesure sur le S23 Ultra avec l'écran de comparaison.
- Critère de décision proposé : RTF < 0,5 et mémoire en pointe < 3 Go sur le
  S23 Ultra, à qualité au moins égale à Parakeet en français.
