package io.github.remtav.dictee

import android.content.Context
import java.io.File

/**
 * Every file the app owns (private, cache, external, no-backup, databases), to check that a flow creates or leaves
 * nothing. Shared by the before/after checks of the reading and contribution tests (issue #15, lot 4).
 *
 * Files of system libraries that hold no user data are left out, by exact path under the app's private directory
 * and nothing else (no pattern): a leftover from the user's flow must still show.
 * - `files/profileInstalled`: marker of androidx.profileinstaller (`ProfileVerifier`, pulled in by the AndroidX
 *   dependencies), written asynchronously after the first activity launch, at a moment that varies. It holds the
 *   result of the baseline-profile install (a status code), nothing the user did.
 */
internal object AppFiles {
    private val libraryFiles = setOf("files/profileInstalled")

    fun list(context: Context): Set<String> {
        val dataDir = context.filesDir.parentFile
        val ignored = libraryFiles.map { File(dataDir, it).path }.toSet()
        return listOfNotNull(
            context.filesDir, context.cacheDir, context.codeCacheDir, context.noBackupFilesDir,
            context.getExternalFilesDir(null), context.getDatabasePath("x").parentFile,
        ).flatMap { root -> root.walkTopDown().filter(File::isFile).map(File::getPath).toList() }
            .filterNot { it in ignored }.toSet()
    }
}
