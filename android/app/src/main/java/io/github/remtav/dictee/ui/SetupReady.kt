package io.github.remtav.dictee.ui

import android.view.ViewGroup
import io.github.remtav.dictee.R

/** Tags of the « C'est prêt » card and its invitation, for tests. */
internal const val READY_CARD_TAG = "setup_ready_card"
internal const val READY_MORE_TAG = "setup_ready_more"

/**
 * The end of the setup (issue #56): « C'est prêt », and a discreet invitation to "Comment dicter"
 * (an outlined button: the tutorial is a choice, never opened by itself). The decision to show it is
 * [SetupProgress.isComplete].
 */
internal fun ViewGroup.readyCard(onMore: () -> Unit) {
    val card = card()
    card.tag = READY_CARD_TAG
    card.heading(context.getString(R.string.setup_ready_title)).setPadding(0, 0, 0, context.dp(6))
    card.body(context.getString(R.string.setup_ready_body), small = true)
    card.button(context.getString(R.string.setup_ready_more), outlined = true) { onMore() }.tag = READY_MORE_TAG
}
