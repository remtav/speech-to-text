package io.github.remtav.dictee.ime.emoji

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.remtav.dictee.R
import java.util.Locale

/**
 * The emoji panel's tabs and scrolling grid (issue #80). First tab: the emojis used last; then one tab per group.
 * A tap on an emoji calls [onEmoji]; the panel stays open. Always the same height, so the keyboard does not jump
 * from one tab to the next. Every emoji carries its name for TalkBack, in the language of the phone.
 */
class EmojiPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** An emoji was tapped. */
    var onEmoji: ((glyph: String) -> Unit)? = null

    /** The recents to show on the first tab, most recent first (read each time the panel opens or the tab is chosen). */
    var recents: () -> List<String> = { emptyList() }

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var catalogue: EmojiCatalogue? = null
    private var current: EmojiCategory? = null // null: the recents tab

    private val tabRow = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val tabViews = ArrayList<TextView>()
    private val adapter = EmojiAdapter { onEmoji?.invoke(it) }
    private val grid = RecyclerView(context)
    private val layoutManager = GridLayoutManager(context, 8)
    private val empty = TextView(context)

    /** Number of emojis in the grid, for the tests. */
    val shownCount: Int get() = adapter.itemCount

    /** The tab currently chosen: null for the recents. For the tests. */
    val currentCategory: EmojiCategory? get() = current

    init {
        orientation = VERTICAL
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(TAB_DP)))
        }
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, dp(TAB_DP)))
        addTab("🕒", R.string.emoji_tab_recent, null)
        for (c in EmojiCategory.entries) addTab(c.tab, tabName(c), c)

        grid.layoutManager = layoutManager
        grid.adapter = adapter
        grid.itemAnimator = null
        grid.overScrollMode = OVER_SCROLL_NEVER
        empty.apply {
            gravity = Gravity.CENTER
            setText(R.string.emoji_none_recent)
            setTextAppearance(R.style.TextAppearance_Dictee_Value)
            visibility = GONE
        }
        val frame = FrameLayout(context).apply {
            addView(grid, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(empty, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, dp(GRID_DP)))
        select(null)
    }

    private fun tabName(c: EmojiCategory) = when (c) {
        EmojiCategory.SMILEYS -> R.string.emoji_tab_smileys
        EmojiCategory.PEOPLE -> R.string.emoji_tab_people
        EmojiCategory.ANIMALS -> R.string.emoji_tab_animals
        EmojiCategory.FOOD -> R.string.emoji_tab_food
        EmojiCategory.TRAVEL -> R.string.emoji_tab_travel
        EmojiCategory.ACTIVITIES -> R.string.emoji_tab_activities
        EmojiCategory.OBJECTS -> R.string.emoji_tab_objects
        EmojiCategory.SYMBOLS -> R.string.emoji_tab_symbols
        EmojiCategory.FLAGS -> R.string.emoji_tab_flags
    }

    private fun addTab(label: String, name: Int, category: EmojiCategory?) {
        val ripple = TypedValue().also { context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true) }
        val tab = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextAppearance(R.style.TextAppearance_Dictee_Label)
            textSize = 22f
            contentDescription = context.getString(name)
            tag = category
            isClickable = true
            setOnClickListener { select(category) }
            layoutParams = LayoutParams(dp(TAB_WIDTH_DP), dp(TAB_DP))
        }
        if (ripple.resourceId != 0) tab.foreground = context.getDrawable(ripple.resourceId)
        tabViews += tab
        tabRow.addView(tab)
    }

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    /** Opens on the recents when there are some, on the smileys otherwise. */
    fun open() {
        select(if (recents().isEmpty()) EmojiCategory.SMILEYS else null)
    }

    /** The emojis are read in the background; the grid fills when they are there. */
    fun setCatalogue(c: EmojiCatalogue) {
        catalogue = c
        select(current)
    }

    val isReady: Boolean get() = catalogue != null

    fun select(category: EmojiCategory?) {
        current = category
        for (t in tabViews) {
            val chosen = t.tag == category
            t.isSelected = chosen
            t.background = if (chosen) GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(themeColor(com.google.android.material.R.attr.colorSecondaryContainer))
            } else null
        }
        val cat = catalogue
        val list = when {
            cat == null -> emptyList()
            category == null -> recents().mapNotNull { cat.find(it) }
            else -> cat.byCategory[category].orEmpty()
        }
        adapter.submit(list)
        grid.scrollToPosition(0)
        empty.visibility = if (category == null && cat != null && list.isEmpty()) VISIBLE else GONE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutManager.spanCount = maxOf(5, w / dp(CELL_DP))
    }

    private class EmojiAdapter(val onTap: (String) -> Unit) : RecyclerView.Adapter<EmojiAdapter.Holder>() {
        private var items: List<Emoji> = emptyList()
        private val locale: Locale get() = Locale.getDefault()

        class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<Emoji>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val d = parent.resources.displayMetrics.density
            val ripple = TypedValue().also { parent.context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true) }
            val tv = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, (CELL_DP * d).toInt())
                gravity = Gravity.CENTER
                setTextAppearance(R.style.TextAppearance_Dictee_Emoji)
                includeFontPadding = false
                if (ripple.resourceId != 0) setBackgroundResource(ripple.resourceId)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
            return Holder(tv)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val e = items[position]
            holder.text.text = e.glyph
            holder.text.contentDescription = e.name(locale)
            holder.text.setOnClickListener { onTap(e.glyph) }
        }
    }

    companion object {
        private const val TAB_DP = 44
        private const val TAB_WIDTH_DP = 48
        private const val CELL_DP = 44
        private const val GRID_DP = 164
    }
}
