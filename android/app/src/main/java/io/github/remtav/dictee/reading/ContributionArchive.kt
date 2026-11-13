package io.github.remtav.dictee.reading

import io.github.remtav.dictee.audio.PcmAudio
import io.github.remtav.dictee.audio.WavReader
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The elements a contribution contains (issue #15, lot 4, step 4.3), in the order of the metadata file.
 * This is the ONE description of the archive: the screen lists [ContributionContent.fields] (one line per
 * field, label from `contrib_field_<name>`), and the archive writes the same list, so the two cannot
 * diverge (`ContributionArchiveTest` compares a written archive with this list, both ways).
 *
 * [key] is the name in `contribution.json`. Nothing else is ever written: no identifier (Android ID,
 * installation id, account, serial number), no other dictation, no vocabulary, no setting, no time of day.
 */
enum class ArchiveField(val key: String) {
    AUDIO("audio"),
    TEXT_VERSION("version_du_texte"),
    TEXT("texte_lu"),
    TRANSCRIPTION("transcription"),
    SCORE("score"),
    MODEL("modele"),
    APP_VERSION("version_de_l_application"),
    PHONE_MODEL("modele_du_telephone"),
    DECLARATION("declaration"),
    DATE("date"),
    REGION("region"),
}

/** What the phone adds to a contribution: the application version, the phone model and the day of the test. */
data class DeviceFacts(val appVersion: String, val phoneModel: String, val date: LocalDate)

/** One recording of the archive: paragraph [paragraph] (1-based, as shown) in file [name], WAV 16 kHz. */
class ArchiveAudio(val paragraph: Int, val name: String, val audio: PcmAudio) {
    /** Duration in tenths of a second, rounded (what the metadata says). */
    val tenths: Int get() = Math.round(audio.samples.size * 10.0 / audio.sampleRate).toInt()
}

/** One element of the archive and its value as written in `contribution.json` (String, Int, Map, List or null). */
data class ArchiveValue(val field: ArchiveField, val value: Any?)

/**
 * Everything a contribution would contain, built from the result of the test: what the screen shows
 * BEFORE consent ("exactly what would leave") and what [ContributionArchive.write] writes AFTER it.
 *
 * Only the RECOMMENDED model's transcription and score are included (the share threshold is computed on
 * it, and the consent announces « la transcription obtenue et le score », in the singular): the other
 * installed models' results stay on the phone, and the screen says so.
 */
class ContributionContent internal constructor(
    val fields: List<ArchiveValue>,
    val audio: List<ArchiveAudio>,
    val fileName: String,
) {
    operator fun get(field: ArchiveField): Any? = fields.first { it.field == field }.value

    /** The names of the files inside the archive, exactly. */
    val entryNames: List<String> get() = listOf(METADATA_NAME) + audio.map { it.name }

    /** The metadata file, readable JSON, with exactly [fields], in order. */
    fun metadataJson(): String = Json.write(LinkedHashMap<String, Any?>().apply { for (f in fields) put(f.field.key, f.value) })

    companion object {
        const val METADATA_NAME = "contribution.json"
        const val WAV_FORMAT = "WAV, PCM 16 bits, mono, 16000 Hz"
        const val DECLARATION_TEXT = "je parle un français québécois"

        /** Longest region kept, in characters: a region, not an address. */
        const val REGION_MAX_CHARS = 60

        /** The optional region as written: trimmed, on one line, without control characters, at most [REGION_MAX_CHARS]; blank is null. */
        fun normalizeRegion(raw: String?): String? {
            if (raw == null) return null
            val cleaned = raw.map { if (it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()) ' ' else it }
                .joinToString("").replace(Regex("\\s+"), " ").trim()
            return cleaned.take(REGION_MAX_CHARS).trim().ifEmpty { null }
        }

        /**
         * The content of a contribution. [paragraphTexts] is the text that was read (all paragraphs, by index),
         * [modelLabel] the display name of the recommended model.
         */
        fun from(
            result: ReadingResult,
            paragraphTexts: List<String>,
            modelLabel: String,
            facts: DeviceFacts,
            region: String?,
        ): ContributionContent {
            val model = result.recommendedModelId
            val global = checkNotNull(result.globalScores[model]) { "pas de score du modèle recommandé" }
            val read = result.paragraphs.filter { it.scores[model] != null }
            val audio = read.map { p -> ArchiveAudio(p.index + 1, "paragraphe-${p.index + 1}.wav", p.audio.resampled(SAMPLE_RATE)) }
            val values = ArchiveField.entries.map { field ->
                ArchiveValue(
                    field,
                    when (field) {
                        ArchiveField.AUDIO -> linkedMapOf(
                            "format" to WAV_FORMAT,
                            "fichiers" to audio.map {
                                linkedMapOf("paragraphe" to it.paragraph, "fichier" to it.name, "duree_secondes" to Tenths(it.tenths))
                            },
                        )
                        ArchiveField.TEXT_VERSION -> result.textVersion
                        ArchiveField.TEXT -> read.map { linkedMapOf("paragraphe" to it.index + 1, "texte" to paragraphTexts[it.index]) }
                        ArchiveField.TRANSCRIPTION -> read.map {
                            linkedMapOf("paragraphe" to it.index + 1, "texte" to it.transcriptions[model].orEmpty())
                        }
                        ArchiveField.SCORE -> linkedMapOf(
                            "ressemblance_pourcent" to global.similarityPercent,
                            "erreurs" to global.errors,
                            "mots" to global.referenceWords,
                            "par_paragraphe" to read.map {
                                val s = it.scores.getValue(model)
                                linkedMapOf("paragraphe" to it.index + 1, "erreurs" to s.errors, "mots" to s.referenceWords)
                            },
                        )
                        ArchiveField.MODEL -> linkedMapOf("id" to model, "nom" to modelLabel)
                        ArchiveField.APP_VERSION -> facts.appVersion
                        ArchiveField.PHONE_MODEL -> facts.phoneModel
                        ArchiveField.DECLARATION -> DECLARATION_TEXT
                        // The day only: no time, no time zone (finer than needed to find a contribution again).
                        ArchiveField.DATE -> facts.date.toString()
                        ArchiveField.REGION -> normalizeRegion(region)
                    },
                )
            }
            val safeVersion = result.textVersion.replace(Regex("[^a-z0-9-]"), "")
            return ContributionContent(values, audio, "kalmia-blabla-$safeVersion-${facts.date}.zip")
        }

        const val SAMPLE_RATE = 16000
    }
}

/** A duration in tenths of a second, written as a decimal number (9.4) without floating-point noise. */
class Tenths(val tenths: Int) {
    override fun toString(): String = "${tenths / 10}.${tenths % 10}"
    override fun equals(other: Any?) = other is Tenths && other.tenths == tenths
    override fun hashCode() = tenths
}

/**
 * The sharing offer of one result (issue #15, step 4.3, the third consent gesture). It never writes
 * anything by itself: [createArchive] is the ONLY way an archive comes into existence, and it refuses
 * unless the offer stands (WER above the threshold with the recommended model, complete text, the person
 * declared a Québec French) AND [consent] was given by the explicit gesture (box ticked, then button).
 */
class ShareOffer(
    val result: ReadingResult,
    private val declaration: Declaration,
    private val paragraphTexts: List<String>,
    private val modelLabel: String,
) {
    /** Whether sharing is proposed at all. Below the threshold, nothing is proposed. */
    val offered: Boolean = result.offerSharing && declaration == Declaration.YES &&
        result.globalScores[result.recommendedModelId] != null

    /** The consent box. Not ticked by default; only the person's tap sets it. */
    var consent: Boolean = false

    /** The optional region, as typed (normalized when written). */
    var region: String = ""

    /** What would leave, shown before consent; null when nothing is offered. */
    fun preview(facts: DeviceFacts): ContributionContent? =
        if (!offered) null else ContributionContent.from(result, paragraphTexts, modelLabel, facts, region)

    /** Writes the archive into [dir], only with the offer AND the consent; otherwise writes nothing and returns null. */
    fun createArchive(dir: File, facts: DeviceFacts): File? {
        if (!offered || !consent) return null
        val content = preview(facts) ?: return null
        return ContributionArchive.write(dir, content)
    }
}

/**
 * The archive file: one zip in the app's private storage holding `contribution.json` and one WAV file
 * (16 kHz, mono, 16 bits) per paragraph read. At most one archive exists at a time.
 */
object ContributionArchive {
    /** Kept by [deleteOlderThan] at most this long, when nothing else deleted it (the process was killed). */
    const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1000

    /**
     * Same time on every entry (2 January 1980, the start of the zip calendar), so the archive does not
     * carry the time of the test, only the day written in the metadata.
     */
    val ENTRY_TIME: Long = LocalDateTime.of(1980, 1, 2, 0, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Writes [content] into [dir] (created if needed), replacing any earlier archive, and returns the file. */
    internal fun write(dir: File, content: ContributionContent): File {
        deleteAll(dir)
        check(dir.mkdirs() || dir.isDirectory) { "dossier de l'archive impossible à créer" }
        val target = File(dir, content.fileName)
        val partial = File(dir, content.fileName + ".part")
        try {
            ZipOutputStream(partial.outputStream().buffered()).use { zip ->
                fun entry(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name).apply { time = ENTRY_TIME })
                    zip.write(bytes)
                    zip.closeEntry()
                }
                entry(ContributionContent.METADATA_NAME, content.metadataJson().toByteArray(Charsets.UTF_8))
                for (a in content.audio) entry(a.name, WavReader.encode(a.audio))
            }
            check(partial.renameTo(target)) { "archive impossible à finaliser" }
        } catch (e: Exception) {
            partial.delete()
            target.delete()
            throw e
        }
        return target
    }

    /** The archives present in [dir] (none, normally, or one). */
    fun list(dir: File): List<File> = dir.listFiles()?.filter { it.isFile }.orEmpty()

    /** Deletes every archive in [dir], then [dir] itself. Returns true when nothing is left. */
    fun deleteAll(dir: File): Boolean {
        dir.listFiles()?.forEach { it.delete() }
        if (dir.isDirectory) dir.delete()
        return !dir.exists()
    }

    /** Deletes the archives older than [maxAgeMillis] (a leftover of a killed process); the folder goes when empty. */
    fun deleteOlderThan(dir: File, maxAgeMillis: Long = MAX_AGE_MILLIS, now: Long = System.currentTimeMillis()) {
        for (f in list(dir)) if (now - f.lastModified() > maxAgeMillis) f.delete()
        if (dir.isDirectory && dir.listFiles().isNullOrEmpty()) dir.delete()
    }
}

/** A small, deterministic JSON writer (keys in insertion order, two-space indent) for `contribution.json`. */
internal object Json {
    fun write(value: Any?): String = StringBuilder().also { append(it, value, 0) }.append('\n').toString()

    private fun append(out: StringBuilder, value: Any?, depth: Int) {
        when (value) {
            null -> out.append("null")
            is String -> string(out, value)
            is Int, is Long, is Tenths -> out.append(value.toString())
            is Boolean -> out.append(value.toString())
            is Map<*, *> -> container(out, '{', '}', value.entries.toList(), depth) { e ->
                string(out, e.key as String)
                out.append(": ")
                append(out, e.value, depth + 1)
            }
            is List<*> -> container(out, '[', ']', value, depth) { append(out, it, depth + 1) }
            else -> throw IllegalArgumentException("type non prévu dans l'archive : ${value.javaClass.name}")
        }
    }

    private fun <T> container(out: StringBuilder, open: Char, close: Char, items: List<T>, depth: Int, item: (T) -> Unit) {
        if (items.isEmpty()) { out.append(open).append(close); return }
        out.append(open).append('\n')
        items.forEachIndexed { i, x ->
            out.append("  ".repeat(depth + 1))
            item(x)
            if (i < items.size - 1) out.append(',')
            out.append('\n')
        }
        out.append("  ".repeat(depth)).append(close)
    }

    private fun string(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\t' -> out.append("\\t")
            c == '\r' -> out.append("\\r")
            c < ' ' || c == ' ' || c == ' ' -> out.append("\\u%04x".format(c.code))
            else -> out.append(c)
        }
        out.append('"')
    }
}
