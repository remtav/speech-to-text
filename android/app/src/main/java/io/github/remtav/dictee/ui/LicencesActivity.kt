package io.github.remtav.dictee.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.github.remtav.dictee.R

/** « Licences des composants » (issue #71): each component, its notice, and its licence text folded. */
class LicencesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = scrollingColumn()
        col.title(getString(R.string.licences_title))
        col.body(getString(R.string.licences_intro), small = true)
        Licences.components.forEachIndexed { i, c ->
            col.heading(c.name).tag = "licence_$i"
            col.body(c.use)
            col.body(c.licence)
            col.body(c.notice, small = true)
            val text = assets.open(c.licenceAsset).bufferedReader().use { it.readText() }
            val folded = col.collapsible(getString(R.string.licences_full_text), "licence_text_header_$i", "licence_text_$i")
            folded.content.body(text, small = true).setTextIsSelectable(true)
        }
    }
}
