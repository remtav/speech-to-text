package io.github.remtav.dictee.dictation

import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.ModelFamily
import io.github.remtav.dictee.text.Command
import io.github.remtav.dictee.text.CommandTable
import io.github.remtav.dictee.text.HallucinationFilter
import io.github.remtav.dictee.text.Piece
import io.github.remtav.dictee.text.TextAssembler
import io.github.remtav.dictee.text.TextEdits
import io.github.remtav.dictee.text.VoiceCommands
import io.github.remtav.dictee.text.Vocabulary

/** Optional rewrite of a finished segment (rules or local LLM). Must never throw. */
fun interface TextCleaner {
    fun clean(text: String): String

    companion object {
        val NONE = TextCleaner { it }
    }
}

/**
 * Turns a raw transcript into edits on a [TextSink]: hallucination filter,
 * voice commands, vocabulary, optional clean-up, then spacing/capitalization.
 */
class TextPipeline(
    private val commandsEnabled: Boolean = true,
    private val vocabulary: Vocabulary = Vocabulary.EMPTY,
    private val cleaner: TextCleaner = TextCleaner.NONE,
    /**
     * Chosen dictation languages: they pick the active commands and the
     * typography of dictated signs (CommandTable). The default, Québec French
     * and English, has every command.
     */
    private val languages: Set<DictationLanguage> = setOf(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.ENGLISH),
) {
    private val activeCommands = CommandTable.active(languages)

    /** Final text of a segment: applies it to [sink]. Returns what was inserted (for logs/tests). */
    fun applyFinal(raw: String, family: ModelFamily?, sink: TextSink): String = apply(prepare(raw, family), sink)

    /**
     * The slow, context-free part (may run a local LLM): call on a worker thread.
     * Text pieces come back fully processed, ready for [apply].
     */
    fun prepare(raw: String, family: ModelFamily?): List<Piece> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        if (family == ModelFamily.WHISPER && HallucinationFilter.isHallucination(text)) return emptyList()
        val pieces = if (commandsEnabled) VoiceCommands.parse(text, activeCommands) else listOf(Piece.Text(text))
        return pieces.mapNotNull { piece ->
            when (piece) {
                is Piece.Text -> cleaner.clean(vocabulary.apply(piece.text)).trim()
                    .takeIf { it.isNotEmpty() }?.let { Piece.Text(it) }
                is Piece.Cmd -> piece
            }
        }
    }

    /** Applies prepared pieces to [sink], reading the live text before the cursor. Main thread. */
    fun apply(pieces: List<Piece>, sink: TextSink): String {
        sink.clearComposing()
        val inserted = StringBuilder()
        var glue = false
        for ((index, piece) in pieces.withIndex()) {
            when (piece) {
                is Piece.Text -> {
                    var out = TextAssembler.join(sink.textBeforeCursor(CONTEXT), piece.text)
                    if (glue) out = out.trimStart()
                    glue = false
                    sink.commit(out)
                    inserted.append(out)
                }
                is Piece.Cmd -> {
                    glue = false
                    val before = sink.textBeforeCursor(CONTEXT)
                    when (piece.command) {
                        Command.NEW_LINE -> {
                            trimTrailingSpaces(before, sink)
                            sink.commit("\n"); inserted.append("\n")
                        }
                        Command.NEW_PARAGRAPH -> {
                            trimTrailingSpaces(before, sink)
                            sink.commit("\n\n"); inserted.append("\n\n")
                        }
                        Command.DELETE_LAST_SENTENCE -> sink.deleteBefore(TextEdits.lastSentenceLength(before))
                        Command.DELETE_LAST_WORD -> sink.deleteBefore(TextEdits.lastWordLength(before))
                        else -> {
                            val next = (pieces.getOrNull(index + 1) as? Piece.Text)?.text
                            val typography = CommandTable.typography(piece.language, languages)
                            val edit = TextAssembler.sign(before, piece.command, typography, next)
                            if (edit == null) {
                                // Nothing to close, or a closing sign at the start: the words stay.
                                val out = TextAssembler.join(before, piece.spoken)
                                if (out.isNotEmpty()) { sink.commit(out); inserted.append(out) }
                            } else {
                                if (edit.delete > 0) {
                                    sink.deleteBefore(edit.delete)
                                    inserted.setLength((inserted.length - edit.delete).coerceAtLeast(0))
                                }
                                sink.commit(edit.insert)
                                inserted.append(edit.insert)
                                glue = edit.glueNext
                            }
                        }
                    }
                }
            }
        }
        return inserted.toString()
    }

    /** Provisional text while the speaker is still talking. */
    fun preview(raw: String, sink: TextSink) {
        val text = vocabulary.apply(raw.trim())
        if (text.isEmpty()) {
            sink.clearComposing()
            return
        }
        // Read the context without the old provisional text.
        sink.clearComposing()
        sink.setComposing(TextAssembler.join(sink.textBeforeCursor(CONTEXT), text))
    }

    private fun trimTrailingSpaces(before: String, sink: TextSink) {
        val n = before.length - before.trimEnd(' ', ' ').length
        if (n > 0) sink.deleteBefore(n)
    }

    companion object {
        const val CONTEXT = 2000
    }
}
