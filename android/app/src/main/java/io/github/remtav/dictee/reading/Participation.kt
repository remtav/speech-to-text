package io.github.remtav.dictee.reading

import io.github.remtav.dictee.stt.DictationLanguage

/** The person's answer to « Parlez-vous un français québécois ? » (issue #15, lot 4). Self-declared, never checked. */
enum class Declaration {
    UNANSWERED, YES, NO;

    companion object {
        fun fromName(name: String?): Declaration = entries.firstOrNull { it.name == name } ?: UNANSWERED
    }
}

/** What the card's "Faire le test" button opens first. */
enum class ReadingEntry { DECLARE, ACCEPT, DECLINED }

/**
 * Who is offered the reading test and where (issue #15, lot 4, "Offre" and "Déclaration"), as a value the
 * screens read and the tests drive. Pure Kotlin: [AppSettings][io.github.remtav.dictee.settings.AppSettings]
 * stores the three inputs, nothing else decides.
 *
 * - The offer exists only when "Français (Québec)" is among the chosen languages.
 * - Answering no excludes the person: no card (setup or settings), no test, no recording, no sharing.
 *   The answer can be corrected in the settings, where the card then comes back.
 * - Closing the card hides it on the main screen only; it stays in the settings.
 */
data class Participation(
    val languages: Set<DictationLanguage>,
    val declaration: Declaration = Declaration.UNANSWERED,
    val cardDismissed: Boolean = false,
) {
    val quebecFrenchChosen: Boolean get() = DictationLanguage.FRENCH_QUEBEC in languages

    /** Answered no: nothing in the reading test is available. */
    val excluded: Boolean get() = declaration == Declaration.NO

    /** The card on the main screen: at the end of the setup, not closed, not excluded. */
    fun cardOnSetup(setupComplete: Boolean): Boolean =
        setupComplete && quebecFrenchChosen && !excluded && !cardDismissed

    /** The card in the settings: same offer, but closing it on the main screen does not hide it. */
    val cardInSettings: Boolean get() = quebecFrenchChosen && !excluded

    /**
     * The answer to the question can be read and corrected in the settings when the offer applies, and also
     * once an answer exists (a person who answered no, then dropped Québec French, can still see and change it).
     */
    val declarationInSettings: Boolean get() = quebecFrenchChosen || declaration != Declaration.UNANSWERED

    fun answered(yes: Boolean): Participation = copy(declaration = if (yes) Declaration.YES else Declaration.NO)

    fun dismissed(): Participation = copy(cardDismissed = true)

    /** Where the test starts: the question first, unless it has been answered. */
    val entry: ReadingEntry
        get() = when (declaration) {
            Declaration.UNANSWERED -> ReadingEntry.DECLARE
            Declaration.YES -> ReadingEntry.ACCEPT
            Declaration.NO -> ReadingEntry.DECLINED
        }
}
