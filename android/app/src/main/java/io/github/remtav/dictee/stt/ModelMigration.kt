package io.github.remtav.dictee.stt

/**
 * Moving the user off a model that is no longer offered (issue #22): a hidden model (Qwen3-ASR,
 * the Quebec Whisper, and since issue #72 Parakeet), or one that no longer suits the chosen languages.
 * Pure Kotlin: tested on the JVM.
 *
 * Nothing is deleted here: the notice only offers to delete a hidden model's files, on a click.
 */
object ModelMigration {

    /** What to tell the user once, after the active model was replaced. */
    data class Notice(
        /** The model that was active. */
        val retired: ModelSpec,
        /** The model now active (Whisper turbo). */
        val replacement: ModelSpec,
        /** The replacement is not installed yet: invite to download it. */
        val needsDownload: Boolean,
        /** The retired model is hidden and its files are on the phone: offer to delete them. */
        val offerDelete: Boolean,
    )

    /** The model put in place of one that no longer suits. */
    val replacement: ModelSpec get() = ModelCatalog.whisperTurbo

    /**
     * The notice for an active model [activeId] with [languages], or null when it still suits
     * (or is unknown: [io.github.remtav.dictee.settings.AppSettings.activeModel] already falls
     * back to the default for an unknown id).
     */
    fun plan(activeId: String, languages: Set<DictationLanguage>, isInstalled: (ModelSpec) -> Boolean): Notice? {
        val active = ModelCatalog.byId(activeId) ?: return null
        if (ModelCatalog.suits(active, languages)) return null
        return Notice(
            retired = active,
            replacement = replacement,
            needsDownload = !isInstalled(replacement),
            offerDelete = active.visibility == ModelVisibility.HIDDEN && isInstalled(active),
        )
    }

    /**
     * The local fallback of the server (issue #22, step 6): same rule, never a hidden model nor
     * one that no longer suits the languages. "" (no fallback) stays "".
     */
    fun fallbackId(currentId: String, languages: Set<DictationLanguage>): String {
        if (currentId.isEmpty()) return ""
        val spec = ModelCatalog.all.firstOrNull { it.id == currentId } ?: return replacement.id
        return if (ModelCatalog.suits(spec, languages)) currentId else replacement.id
    }
}
