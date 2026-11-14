package io.github.remtav.dictee

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import io.github.remtav.dictee.stt.EngineOptions
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.SherpaEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Issue #53, point d'arrêt « contexte entre segments » : sherpa-onnx 2.0.1 ne donne aucun moyen de passer une
 * amorce de texte (`initial_prompt`, jetons `<|startofprev|>`) au décodeur Whisper hors ligne.
 *
 * Preuve dans le code source de la v2.0.1 (commit af2d8d81, voir DECISIONS.md, « Issue #53 ») :
 *  - `OfflineWhisperModelConfig` (C++, Kotlin et JNI) n'a que encoder, decoder, language, task, tail_paddings et
 *    les deux drapeaux d'horodatage ;
 *  - `OfflineWhisperGreedySearchDecoder::Decode` part de `model_->GetInitialTokens()`, c'est-à-dire la
 *    métadonnée `sot_sequence` lue dans le fichier ONNX au chargement, n'y change que la langue et la tâche, et
 *    ajoute `no_timestamps` : aucune autre entrée ;
 *  - `OfflineStream::SetOption` existe, mais l'implémentation Whisper ne lit aucune option du flux
 *    (`GetOption` n'apparaît que chez Qwen3-ASR, FunASR-nano, Canary, SenseVoice, FireRedASR…).
 *
 * Ce test le vérifie sur le JAR Linux x86-64 de la même version :
 *  - sans modèle (donc aussi sur la CI) : la classe de configuration n'a pas de champ d'amorce ; si une version
 *    future de sherpa-onnx en ajoute un, ce test échoue et le dit ;
 *  - avec le vrai Whisper turbo (-PmodelsDir) : une amorce passée par toutes les options de flux plausibles est
 *    bien enregistrée par le flux, mais le texte décodé est identique, octet pour octet, à celui sans amorce.
 *
 *   ./gradlew testDebugUnitTest -PmodelsDir=$HOME/dictee-models --tests '*WhisperPromptHostTest*' --console=plain
 */
class WhisperPromptHostTest {
    @Test fun whisperConfigOfSherpa201HasNoPromptField() {
        val fields = OfflineWhisperModelConfig::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name }.toSet()
        println("PROMPT|champs de OfflineWhisperModelConfig (sherpa-onnx 2.0.1)|${fields.sorted().joinToString(", ")}")
        assertEquals(
            "sherpa-onnx a changé la configuration Whisper : vérifier si une amorce est désormais possible (issue #53)",
            setOf("encoder", "decoder", "language", "task", "tailPaddings", "enableTokenTimestamps", "enableSegmentTimestamps"),
            fields,
        )
    }

    /** Options de flux qu'un appelant pourrait essayer ; aucune n'est lue par le décodeur Whisper de la 2.0.1. */
    private val promptKeys = listOf("prompt", "initial_prompt", "context", "hotwords", "prefix", "text")

    @Test fun streamOptionsDoNotReachTheWhisperDecoder() {
        val spec = ModelCatalog.whisperTurbo
        val dir = Host.ensureModel(spec).dirFor(spec.id)
        val rec = OfflineRecognizer(config = SherpaEngine.buildConfig(spec, dir, EngineOptions(numThreads = 4, language = "fr")))
        try {
            val clips = Host.references.filter { it.language != "en" }.map { it.name to it.audio.resampled(16000).samples }
            var previous = "Bonjour, nous sommes à Trois-Rivières, au Québec, pour la réunion du conseil."
            for ((name, a) in clips) {
                val plain = decode(rec, a, emptyMap())
                // L'amorce : la fin du texte du segment précédent, comme en mode serveur (#35), 200 caractères.
                val prompt = previous.takeLast(200)
                val primed = decode(rec, a, promptKeys.associateWith { prompt })
                println("PROMPT|$name|sans amorce : ${plain.first}|avec amorce par ${promptKeys.joinToString("/")} : ${primed.first}|" +
                    "options relues sur le flux : ${primed.second}")
                assertEquals("$name : chaque option est enregistrée par le flux", promptKeys.associateWith { prompt }, primed.second)
                assertEquals("$name : l'amorce n'a aucun effet sur Whisper (aucun chemin dans sherpa-onnx 2.0.1)", plain.first, primed.first)
                previous = plain.first
            }
            println("PROMPT|résumé|${clips.size} extraits, texte identique avec et sans amorce : aucun chemin vers le décodeur Whisper")
        } finally {
            rec.release()
        }
    }

    private fun decode(rec: OfflineRecognizer, samples: FloatArray, options: Map<String, String>): Pair<String, Map<String, String>> {
        val st = rec.createStream()
        try {
            options.forEach { (k, v) -> st.setOption(k, v) }
            st.acceptWaveform(samples, 16000)
            rec.decode(st)
            return rec.getResult(st).text.trim() to options.keys.associateWith { st.getOption(it) }
        } finally {
            st.release()
        }
    }
}
