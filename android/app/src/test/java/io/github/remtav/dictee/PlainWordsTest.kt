package io.github.remtav.dictee

import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelPlainNames
import io.github.remtav.dictee.stt.ModelRecommendations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #37: no model name in the normal path (installation, quick test, keyboard messages), choices described by
 * their effect, and « développé au Québec » / « code ouvert » said in plain words that separate the code (public)
 * from the person's data (private). Issue #57: the welcome texts are true whether or not a server is set up.
 */
class PlainWordsTest {
    private val main = File(System.getProperty("dictee.assetsDir")!!).parentFile
    private val strings: Map<String, String> by lazy {
        Regex("<string name=\"([a-z0-9_]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(main.resolve("res/values/strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** The strings of the normal path: the installation screen, the quick test, the model cards and notices, the keyboard. */
    private val normalPath: Map<String, String> get() = strings.filterKeys { name ->
        listOf(
            "setup_", "quick_", "model_notice_", "model_recommended", "model_publisher", "model_warning", "status_", "delete_",
            // Review of #65: the tutorial, the reading test, the keyboard's messages and the first-level dictation help.
            "howto_", "reading_", "contrib_", "msg_", "cd_", "settings_partials",
        ).any { name.startsWith(it) }
    }

    /**
     * The screens of the normal path never format a model's technical name into a text: `displayName`,
     * `shortName` and `description` are for « Avancé », the comparison and the diagnostic.
     */
    private val normalPathSources = listOf(
        "ui/SetupActivity.kt", "ui/QuickTestActivity.kt", "ui/QuickTestModel.kt", "ui/HowToActivity.kt",
        "ui/ReadingTestActivity.kt", "ui/ReadingTestModel.kt", "ime/DicteeImeService.kt", "DicteeApp.kt",
        "stt/EngineManager.kt",
    )

    /**
     * Known, justified uses, each with its reason. Remove an entry as soon as its reason goes.
     * - `ReadingTestModel`: the contribution archive (sent by the person, read by the people who improve the
     *   models) keeps the technical name next to the id; the screen shows the plain name.
     */
    private val allowed = mapOf(
        "ui/ReadingTestModel.kt" to "val technical = onDevice(result.recommendedModelId)?.displayName",
    )

    @Test
    fun theNormalPathNeverFormatsATechnicalModelName() {
        val problems = normalPathSources.flatMap { path ->
            main.resolve("java/io/github/remtav/dictee/$path").readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//")
                val uses = Regex("\\.(displayName|shortName|description)\\b").containsMatchIn(code)
                if (uses && allowed[path]?.let { line.contains(it) } != true) "$path:${i + 1} : ${line.trim()}" else null
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun thePhoneModelIsNamedWhisperAndNothingMoreTechnical() {
        val whisper = ModelCatalog.whisperTurbo
        assertEquals("Whisper", ModelPlainNames.of(whisper))
        assertEquals("Whisper", ModelPlainNames.inSentence(whisper))
        assertEquals("Whisper", ModelPlainNames.short(whisper))
        for (name in listOf(ModelPlainNames.of(whisper), ModelPlainNames.inSentence(whisper), ModelPlainNames.short(whisper))) {
            for (word in ModelPlainNames.technicalWords) assertFalse("$name contains $word", name.contains(word, ignoreCase = true))
        }
        // « Whisper » is the one technical word the normal path now says; the others stay forbidden.
        assertFalse("Whisper" in ModelPlainNames.technicalWords)
        for (word in listOf("Parakeet", "large-v3", "turbo", "int8", "quantification", "Qwen", "Hugging Face", "SHA-256")) {
            assertTrue(word, word in ModelPlainNames.technicalWords)
        }
    }

    /** Issue #72: no model is named « Plus précis », « Plus léger »… any more (the retired names). */
    @Test
    fun theRetiredNamesAreGoneFromPlainNamesAndFromTheNormalPathStrings() {
        val retired = listOf("Plus précis", "modèle plus précis", "Précis")
        val names = listOf(ModelCatalog.whisperTurbo, ModelCatalog.remote).flatMap {
            listOf(ModelPlainNames.of(it), ModelPlainNames.inSentence(it), ModelPlainNames.short(it))
        }
        for (r in retired) for (n in names) assertFalse("$n : $r", n.contains(r))
        val source = main.resolve("java/io/github/remtav/dictee/stt/ModelPlainNames.kt").readText()
        for (r in retired) assertFalse("ModelPlainNames.kt : $r", source.substringAfter("object ModelPlainNames").contains("\"$r\""))
        // The strings of the hidden quick test too: Whisper, not the old name (their verdicts are not rewritten).
        for ((key, text) in strings) {
            for (r in listOf("modèle plus précis", "Plus précis")) assertFalse("$key : $r", text.contains(r))
        }
    }

    @Test
    fun noModelNameInTheStringsOfTheNormalPath() {
        assertTrue(normalPath.size > 30)
        val problems = normalPath.flatMap { (name, text) ->
            ModelPlainNames.technicalWords.filter { text.contains(it, ignoreCase = true) }.map { "$name : « $it »" }
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun noModelNameInTheRecommendationReasons() {
        for (c in listOf(setOf(DictationLanguage.FRENCH_QUEBEC), setOf(DictationLanguage.ENGLISH), setOf(DictationLanguage.MANDARIN))) {
            val reason = ModelRecommendations.recommend(c).reason
            for (word in ModelPlainNames.technicalWords) assertFalse("$reason : $word", reason.contains(word, ignoreCase = true))
        }
    }

    @Test
    fun theWelcomeSaysQuebecAndOpenCodeInPlainWords() {
        for (key in listOf("setup_intro", "setup_intro_server")) {
            val intro = strings.getValue(key)
            assertTrue(intro, intro.contains("développé au Québec"))
            assertTrue(intro, intro.contains("Code ouvert"))
            assertTrue(intro, intro.contains("Le code est public"))
            assertFalse("« open source » can worry: plain words instead", intro.contains("open source", ignoreCase = true))
        }
        // The code is public, the person's dictations are private: said apart, and only where it is true.
        assertTrue(strings.getValue("setup_intro").contains("vos dictées, elles, restent privées"))
    }

    /** Issue #57: « sent to no one » is never said when a server is in use; the server is never mentioned otherwise. */
    @Test
    fun theWelcomeTextsAreTrueInBothCases() {
        val local = listOf("setup_intro", "howto_privacy", "setup_step_enable_warning")
        val server = listOf("setup_intro_server", "howto_privacy_server", "setup_step_enable_warning_server")
        for (k in local) assertFalse(k, strings.getValue(k).contains("serveur"))
        for (k in server) {
            val t = strings.getValue(k)
            assertFalse(k, t.contains("envoyée à personne"))
            assertFalse(k, t.contains("reste sur votre téléphone"))
            assertFalse(k, t.contains("restent privées"))
        }
        assertTrue(strings.getValue("setup_intro").contains("n\\'est envoyée à personne"))
    }
}
