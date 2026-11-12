package io.github.remtav.dictee.polish

import io.github.remtav.dictee.Host
import io.github.remtav.dictee.audio.WavReader
import io.github.remtav.dictee.stt.EngineOptions
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.SherpaEngine
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Generator, not a check: transcribes synthetic audio with the three real
 * models, through the app's own [SherpaEngine], to produce the `tts+<model>`
 * raw texts of the polishing evaluation set (issue #15, step 3.1).
 *
 * Skipped unless both `-PmodelsDir=…` and `-PpolishAudioDir=…` are given. The
 * audio directory holds `liste.tsv` (`id<TAB>langue<TAB>dit`, one line per
 * utterance) and one 16 kHz mono `<id>.wav` per line, made outside the
 * repository with a free TTS (see DECISIONS.md, step 3.1). Writes
 * `transcriptions.tsv` (`id<TAB>modèle<TAB>texte`) into that directory. Neither
 * the audio nor the TTS is committed.
 *
 *     ./gradlew testDebugUnitTest --tests '*PolishSetTranscriptionHostTest*' \
 *         -PmodelsDir=$HOME/dictee-models -PpolishAudioDir=/tmp/polish-audio
 */
class PolishSetTranscriptionHostTest {
    @Test fun transcribeSyntheticUtterancesWithTheThreeModels() {
        val audioDir = System.getProperty("dictee.polishAudioDir")?.let { File(it) }
        assumeTrue("set -PpolishAudioDir=... to regenerate the tts rows", audioDir != null)
        val lines = File(audioDir!!, "liste.tsv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
        val out = StringBuilder()
        for (spec in listOf(ModelCatalog.parakeet, ModelCatalog.qwen3, ModelCatalog.whisperTurbo)) {
            val store = Host.ensureModel(spec)
            for ((lang, group) in lines.groupBy { it[1] }) {
                // Whisper is told the language, as the app does when a single language is chosen.
                val options = EngineOptions(numThreads = 4, language = if (spec == ModelCatalog.whisperTurbo) lang else "")
                val engine = SherpaEngine(spec, store.dirFor(spec.id), options)
                try {
                    for ((id, _, _) in group) {
                        val audio = File(audioDir, "$id.wav").inputStream().use { WavReader.read(it) }.resampled(16000)
                        val text = engine.transcribe(audio.samples).text.replace('\t', ' ').replace('\n', ' ')
                        println("$id\t${spec.shortName}\t$text")
                        out.append(id).append('\t').append(PolishEvalSet.modelTag(spec.shortName))
                            .append('\t').append(text).append('\n')
                    }
                } finally {
                    engine.release()
                }
            }
        }
        File(audioDir, "transcriptions.tsv").writeText(out.toString())
    }
}
