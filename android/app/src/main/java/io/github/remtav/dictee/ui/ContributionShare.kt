package io.github.remtav.dictee.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import io.github.remtav.dictee.BuildConfig
import io.github.remtav.dictee.R
import io.github.remtav.dictee.reading.ArchiveField
import io.github.remtav.dictee.reading.Contribution
import io.github.remtav.dictee.reading.DeviceFacts
import java.io.File
import java.time.LocalDate

/**
 * The Android side of the contribution hand-off (issue #15, lot 4, step 4.3): where the archive lives,
 * what the phone adds to it, and the intents that hand it to the person's own mail (or another app).
 *
 * The app never sends anything itself: it opens the person's mail with the address, the subject, a short
 * body and the attachment filled in; the person presses « Envoyer » there, or cancels. The attachment is
 * served by a [FileProvider] limited to the archive folder (`res/xml/contribution_paths.xml`), and the read
 * permission goes to the app that receives the intent, nobody else.
 */
object ContributionShare {
    /** The FileProvider of the archive folder, declared in the manifest. */
    const val AUTHORITY = BuildConfig.APPLICATION_ID + ".contribution"

    const val MIME_TYPE = "application/zip"

    /** The only folder the FileProvider serves: private storage, `files/contribution/`. */
    fun archiveDir(context: Context): File = File(context.filesDir, "contribution")

    /** What the phone adds: application version, maker and model, and today's date (the day only). */
    fun deviceFacts(): DeviceFacts =
        DeviceFacts(BuildConfig.VERSION_NAME, "${Build.MANUFACTURER} ${Build.MODEL}".trim(), LocalDate.now())

    fun uriFor(context: Context, archive: File): Uri = FileProvider.getUriForFile(context, AUTHORITY, archive)

    /** The label of each element of the archive, as the screen shows it (same list as the archive). */
    fun fieldLabel(context: Context, field: ArchiveField, modelLabel: String): String = when (field) {
        ArchiveField.AUDIO -> context.getString(R.string.contrib_field_audio)
        ArchiveField.TEXT_VERSION -> context.getString(R.string.contrib_field_text_version)
        ArchiveField.TEXT -> context.getString(R.string.contrib_field_text)
        ArchiveField.TRANSCRIPTION -> context.getString(R.string.contrib_field_transcription, modelLabel)
        ArchiveField.SCORE -> context.getString(R.string.contrib_field_score)
        ArchiveField.MODEL -> context.getString(R.string.contrib_field_model)
        ArchiveField.APP_VERSION -> context.getString(R.string.contrib_field_app_version)
        ArchiveField.PHONE_MODEL -> context.getString(R.string.contrib_field_phone_model)
        ArchiveField.DECLARATION -> context.getString(R.string.contrib_field_declaration)
        ArchiveField.DATE -> context.getString(R.string.contrib_field_date)
        ArchiveField.REGION -> context.getString(R.string.contrib_field_region)
    }

    private fun subject(context: Context, textVersion: String) = context.getString(R.string.contrib_email_subject, textVersion)

    private fun body(context: Context, archive: File, modelLabel: String): String = context.getString(
        R.string.contrib_email_body,
        archive.name,
        ArchiveField.entries.joinToString("\n") { "• " + fieldLabel(context, it, modelLabel) },
        Contribution.POLICY_URL,
    )

    /** The bare share intent: the archive, read permission for the receiving app only. */
    private fun send(context: Context, archive: File, textVersion: String, modelLabel: String): Intent {
        val uri = uriFor(context, archive)
        return Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_EMAIL, arrayOf(Contribution.RECEIVING_ADDRESS))
            putExtra(Intent.EXTRA_SUBJECT, subject(context, textVersion))
            putExtra(Intent.EXTRA_TEXT, body(context, archive, modelLabel))
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, archive.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * « Envoyer par courriel »: the share intent restricted to mail apps (selector `mailto:`), recipient
     * [Contribution.RECEIVING_ADDRESS], subject and body filled in, the archive attached. Starting it throws
     * `ActivityNotFoundException` when no mail app is installed (the screen says so).
     */
    fun emailIntent(context: Context, archive: File, textVersion: String, modelLabel: String): Intent =
        send(context, archive, textVersion, modelLabel).apply {
            selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        }

    /** « Partager le fichier autrement »: the system share sheet, same attachment and fields, any app. */
    fun otherIntent(context: Context, archive: File, textVersion: String, modelLabel: String): Intent =
        Intent.createChooser(send(context, archive, textVersion, modelLabel), context.getString(R.string.contrib_share_chooser_title))
}
