package io.github.remtav.dictee.ui

import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

// Small controls of the settings screens: a switch, a radio group and a slider that store their value on change.

internal fun ViewGroup.switch(label: String, value: Boolean, onChange: (Boolean) -> Unit): MaterialSwitch =
    MaterialSwitch(context).apply {
        text = label
        isChecked = value
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }.also { addView(it) }

/**
 * Each button gets an id of its own (issue #69): with the same ids in every group (1000, 1001...), a
 * recreated screen (rotation) restored one group's saved choice into all the others, and stored it.
 */
internal fun ViewGroup.radio(labels: List<String>, selected: Int, onChange: (Int) -> Unit): RadioGroup {
    val group = RadioGroup(context)
    labels.forEach { l ->
        group.addView(RadioButton(context).apply { id = View.generateViewId(); text = l })
    }
    group.check(group.getChildAt(selected).id)
    group.listenByIndex(onChange)
    addView(group)
    return group
}

internal fun RadioGroup.listenByIndex(onChange: (Int) -> Unit) =
    setOnCheckedChangeListener { g, id -> onChange(g.indexOfChild(g.findViewById(id))) }

/** Checks [index] without running the change callback (it would store the value as an explicit choice). */
internal fun RadioGroup.checkSilently(index: Int, onChange: (Int) -> Unit) {
    setOnCheckedChangeListener(null)
    check(getChildAt(index).id)
    listenByIndex(onChange)
}

internal fun ViewGroup.slider(
    label: String, from: Float, to: Float, step: Float, value: Float,
    format: (Float) -> String, onChange: (Float) -> Unit,
) {
    val text = body("$label : ${format(value)}")
    addView(Slider(context).apply {
        valueFrom = from
        valueTo = to
        stepSize = step
        this.value = (Math.round(value.coerceIn(from, to) / step) * step).coerceIn(from, to)
        setLabelFormatter { format(it) }
        addOnChangeListener { _, v, fromUser ->
            text.text = "$label : ${format(v)}"
            if (fromUser) onChange(v)
        }
    })
}
