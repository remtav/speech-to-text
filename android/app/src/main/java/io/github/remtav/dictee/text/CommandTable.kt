package io.github.remtav.dictee.text

import io.github.remtav.dictee.stt.DictationLanguage

/**
 * The single table of spoken commands (issue #15, step 2.2): every wording,
 * the sign or action it produces and its language. The parser
 * ([VoiceCommands]) reads only this table, and the "How to dictate" screen
 * (step 2.3) is meant to be generated from it, so the help cannot drift from
 * the behaviour. Wordings and sources: DECISIONS.md, steps 2.1 and 2.2.
 */

/** How a command behaves in the text. */
enum class CommandKind {
    /** A sign glued to the next word: « ( “. */
    OPENING_SIGN,

    /** A sign glued to the previous word: : ; ? ! » ) ”. */
    CLOSING_SIGN,

    /** Line breaks. Only between punctuation or segment edges. */
    LAYOUT,

    /** Deletions. Only when they are the whole segment. */
    EDIT,
}

enum class Command(val kind: CommandKind) {
    OPEN_QUOTE(CommandKind.OPENING_SIGN),
    CLOSE_QUOTE(CommandKind.CLOSING_SIGN),
    COLON(CommandKind.CLOSING_SIGN),
    SEMICOLON(CommandKind.CLOSING_SIGN),
    OPEN_PARENTHESIS(CommandKind.OPENING_SIGN),
    CLOSE_PARENTHESIS(CommandKind.CLOSING_SIGN),
    QUESTION_MARK(CommandKind.CLOSING_SIGN),
    EXCLAMATION_MARK(CommandKind.CLOSING_SIGN),
    NEW_LINE(CommandKind.LAYOUT),
    NEW_PARAGRAPH(CommandKind.LAYOUT),
    DELETE_LAST_SENTENCE(CommandKind.EDIT),
    DELETE_LAST_WORD(CommandKind.EDIT);

    val isSign: Boolean get() = kind == CommandKind.OPENING_SIGN || kind == CommandKind.CLOSING_SIGN
}

/** Language of a wording. Québec and France share the French wordings; only the typography differs. */
enum class CommandLanguage(val label: String) { FRENCH("Français"), ENGLISH("Anglais") }

/** Where a wording comes from (DECISIONS.md, step 2.1). */
enum class WordingSource(val code: String, val label: String) {
    APPLE("A", "Apple"),
    GOOGLE("G", "Google"),
    USAGE("U", "usage courant, non sourcé"),
}

/**
 * How picky the parser is with a wording.
 * [NORMAL]: the name of a sign, which is only an ordinary noun after a
 * determiner ("un point d'interrogation"). [HOMOGRAPH]: also an ordinary word
 * ("deux points" is a number and a noun, "colon" is an organ), so it must
 * stand apart from the words that follow (DECISIONS.md, step 2.2).
 */
enum class Strictness { NORMAL, HOMOGRAPH }

/** One accepted wording. [text] is written as the user would read it in the help. */
data class CommandForm(
    val text: String,
    val sources: Set<WordingSource>,
    val strictness: Strictness = Strictness.NORMAL,
) {
    /** Folded words used for matching (accents and case removed, hyphens as spaces). */
    internal val words: List<String> = Normalize.words(text).map { Normalize.fold(it.first) }

    /** "A, G", "U"… for the help screen or the docs. */
    val sourceCodes: String get() = sources.sortedBy { it.ordinal }.joinToString(", ") { it.code }
}

/**
 * "Say this → you get that": dictating [said] as one segment in a field that
 * holds [before] leaves [result] in it (checked by a unit test, Québec
 * typography for French).
 */
data class CommandExample(val said: String, val result: String, val before: String = "")

data class CommandEntry(
    val command: Command,
    val language: CommandLanguage,
    /** First = main form, the one to show; the others are accepted too. */
    val forms: List<CommandForm>,
    val example: CommandExample,
    /** What the command produces, for the help ("« … »", "retour à la ligne"…). */
    val produces: String,
    /**
     * A shorter example for the first screen of "Comment dicter" (issue #56), when [example] teaches
     * several commands at once. Same rule as [example]: dictated as one segment, it gives its result
     * (checked by a unit test). Null: [example] is short enough.
     */
    val firstScreen: CommandExample? = null,
) {
    val primary: String get() = forms.first().text

    /** The example the first screen shows. */
    val firstScreenExample: CommandExample get() = firstScreen ?: example
}

/** Typography of dictated signs (DECISIONS.md, step 2.2). */
enum class Typography {
    /** OQLF: no-break space before « : » and inside « », nothing before ; ? !. */
    QUEBEC,

    /** France: no-break space before : ; ? ! and inside « ». */
    FRANCE,

    /** Nothing before any sign, quotes “ ”. */
    ENGLISH,
}

object CommandTable {
    private val A = WordingSource.APPLE
    private val G = WordingSource.GOOGLE
    private val U = WordingSource.USAGE

    private fun f(text: String, vararg s: WordingSource, strictness: Strictness = Strictness.NORMAL) =
        CommandForm(text, s.toSet(), strictness)

    private fun fr(command: Command, produces: String, example: CommandExample, vararg forms: CommandForm) =
        CommandEntry(command, CommandLanguage.FRENCH, forms.toList(), example, produces)

    private fun en(command: Command, produces: String, example: CommandExample, vararg forms: CommandForm) =
        CommandEntry(command, CommandLanguage.ENGLISH, forms.toList(), example, produces)

    private val quoteFr = CommandExample(
        "il a dit deux-points ouvrir les guillemets bonjour fermer les guillemets",
        "Il a dit : « bonjour »",
    )
    private val parenFr = CommandExample(
        "on se voit lundi ouvrir la parenthèse ou mardi fermer la parenthèse",
        "On se voit lundi (ou mardi)",
    )
    private val colonFr = CommandExample("il a dit deux-points bonjour", "Il a dit\u00A0: bonjour")
    private val quoteEn = CommandExample("she said colon open quote hello close quote", "She said: “hello”")
    private val parenEn = CommandExample(
        "see you Monday open parenthesis or Tuesday close parenthesis",
        "See you Monday (or Tuesday)",
    )

    /** Every command, French first. */
    val entries: List<CommandEntry> = listOf(
        // ---- French ----
        fr(
            Command.OPEN_QUOTE, "«", quoteFr,
            f("ouvrir les guillemets", U), f("ouvrez les guillemets", U), f("ouvre les guillemets", U),
            f("ouvrir guillemets", U), f("ouvrir guillemet", U),
            f("guillemet ouvrant", A), f("guillemets ouvrants", A),
            f("guillemets ouverts", U), f("guillemet ouvert", U),
        ),
        fr(
            Command.CLOSE_QUOTE, "»", quoteFr,
            f("fermer les guillemets", U), f("fermez les guillemets", U), f("ferme les guillemets", U),
            f("fermer guillemets", U), f("fermer guillemet", U),
            f("guillemet fermant", A), f("guillemets fermants", A),
            f("guillemets fermés", U), f("guillemet fermé", U),
        ),
        fr(
            Command.COLON, ":", quoteFr,
            f("deux-points", A),
            // Without the hyphen it is also the number "deux points" (iPhone writes it so).
            f("deux points", A, strictness = Strictness.HOMOGRAPH),
        ).copy(firstScreen = colonFr),
        fr(
            Command.SEMICOLON, ";", CommandExample("du pain point-virgule du lait", "Du pain; du lait"),
            f("point-virgule", A),
        ),
        fr(
            Command.OPEN_PARENTHESIS, "(", parenFr,
            f("ouvrir la parenthèse", U), f("ouvrez la parenthèse", U), f("ouvre la parenthèse", U),
            f("ouvrir parenthèse", U), f("parenthèse ouvrante", A),
        ),
        fr(
            Command.CLOSE_PARENTHESIS, ")", parenFr,
            f("fermer la parenthèse", U), f("fermez la parenthèse", U), f("ferme la parenthèse", U),
            f("fermer parenthèse", U), f("parenthèse fermante", A),
        ),
        fr(
            Command.QUESTION_MARK, "?", CommandExample("tu viens demain point d'interrogation", "Tu viens demain?"),
            f("point d'interrogation", A, G),
        ),
        fr(
            Command.EXCLAMATION_MARK, "!", CommandExample("quelle belle journée point d'exclamation", "Quelle belle journée!"),
            f("point d'exclamation", A, G),
        ),
        fr(
            Command.NEW_LINE, "retour à la ligne", CommandExample("Bonjour, nouvelle ligne, merci", "Bonjour\nMerci"),
            f("nouvelle ligne", A, G), f("à la ligne", A), f("retour à la ligne", U),
        ),
        fr(
            Command.NEW_PARAGRAPH, "ligne vide puis nouveau paragraphe",
            CommandExample("Bonjour, nouveau paragraphe, merci", "Bonjour\n\nMerci"),
            f("nouveau paragraphe", A, G),
        ),
        fr(
            Command.DELETE_LAST_SENTENCE, "efface la dernière phrase (commande dite seule)",
            CommandExample("efface la dernière phrase", "Bonjour.", before = "Bonjour. Phrase de trop."),
            f("efface la dernière phrase", U), f("effacer la dernière phrase", U), f("effacez la dernière phrase", U),
            f("supprime la dernière phrase", U), f("supprimer la dernière phrase", U), f("supprimez la dernière phrase", U),
        ),
        fr(
            Command.DELETE_LAST_WORD, "efface le dernier mot (commande dite seule)",
            CommandExample("efface le dernier mot", "Bonjour", before = "Bonjour monde"),
            f("efface le dernier mot", U), f("effacer le dernier mot", U), f("effacez le dernier mot", U),
            f("supprime le dernier mot", U), f("supprimer le dernier mot", U), f("supprimez le dernier mot", U),
        ),
        // ---- English ----
        en(
            Command.OPEN_QUOTE, "“", quoteEn,
            f("open quote", U), f("open quotes", U), f("begin quote", U), f("begin quotes", U),
        ),
        en(
            Command.CLOSE_QUOTE, "”", quoteEn,
            f("close quote", U), f("close quotes", U), f("end quote", A), f("end quotes", U),
        ),
        en(
            Command.COLON, ":", quoteEn,
            // Also the organ: "colon cancer".
            f("colon", A, strictness = Strictness.HOMOGRAPH),
        ),
        en(
            Command.SEMICOLON, ";", CommandExample("bread semicolon milk", "Bread; milk"),
            f("semicolon", A), f("semi colon", U),
        ),
        en(
            Command.OPEN_PARENTHESIS, "(", parenEn,
            f("open parenthesis", A), f("open parentheses", U), f("open paren", U),
        ),
        en(
            Command.CLOSE_PARENTHESIS, ")", parenEn,
            f("close parenthesis", A), f("close parentheses", U), f("close paren", U),
        ),
        en(
            Command.QUESTION_MARK, "?", CommandExample("are you coming question mark", "Are you coming?"),
            f("question mark", A, G),
        ),
        en(
            Command.EXCLAMATION_MARK, "!", CommandExample("what a day exclamation mark", "What a day!"),
            f("exclamation mark", A), f("exclamation point", A, G),
        ),
        en(
            Command.NEW_LINE, "retour à la ligne", CommandExample("Hello, new line, thanks", "Hello\nThanks"),
            f("new line", A, G), f("newline", U), f("next line", U),
        ),
        en(
            Command.NEW_PARAGRAPH, "ligne vide puis nouveau paragraphe",
            CommandExample("Hello, new paragraph, thanks", "Hello\n\nThanks"),
            f("new paragraph", A, G), f("next paragraph", U),
        ),
        en(
            Command.DELETE_LAST_SENTENCE, "efface la dernière phrase (commande dite seule)",
            CommandExample("delete last sentence", "Hello.", before = "Hello. Wrong sentence."),
            f("delete last sentence", U), f("delete the last sentence", U), f("scratch that", U),
        ),
        en(
            Command.DELETE_LAST_WORD, "efface le dernier mot (commande dite seule)",
            CommandExample("delete last word", "Hello", before = "Hello world"),
            f("delete last word", U), f("delete the last word", U),
        ),
    )

    /**
     * Wording languages for the chosen dictation languages: French for Québec
     * or France, English for English. Empty when neither is chosen.
     */
    fun languagesFor(chosen: Set<DictationLanguage>): Set<CommandLanguage> = buildSet {
        if (DictationLanguage.FRENCH_QUEBEC in chosen || DictationLanguage.FRENCH_FRANCE in chosen) add(CommandLanguage.FRENCH)
        if (DictationLanguage.ENGLISH in chosen) add(CommandLanguage.ENGLISH)
    }

    /**
     * Commands active for [chosen], in table order (what the help screen lists).
     * French and/or English as chosen. With neither (Spanish only, for
     * instance), no punctuation command: it would be French or English words in
     * another language; the line breaks and deletions of both languages stay, as
     * before step 2.2.
     */
    fun active(chosen: Set<DictationLanguage>): List<CommandEntry> {
        val langs = languagesFor(chosen)
        return if (langs.isEmpty()) {
            entries.filter { !it.command.isSign }
        } else {
            entries.filter { it.language in langs }
        }
    }

    /**
     * Typography of a sign dictated in [language]: English for English
     * wordings; for French ones, France only when France is chosen without
     * Québec, Québec otherwise (also when both are chosen: the OQLF rule adds
     * fewer spaces, and France allows it).
     */
    fun typography(language: CommandLanguage, chosen: Set<DictationLanguage>): Typography = when {
        language == CommandLanguage.ENGLISH -> Typography.ENGLISH
        DictationLanguage.FRENCH_FRANCE in chosen && DictationLanguage.FRENCH_QUEBEC !in chosen -> Typography.FRANCE
        else -> Typography.QUEBEC
    }
}
