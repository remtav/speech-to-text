package io.github.remtav.dictee

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout

/** Two text fields and a password field, for the instrumented IME tests (debug builds only). */
class TestFieldActivity : Activity() {
    lateinit var field1: EditText
    lateinit var field2: EditText
    lateinit var password: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 96, 32, 32) }
        field1 = EditText(this).apply {
            id = R.id.test_field1
            hint = "champ 1"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        field2 = EditText(this).apply {
            id = R.id.test_field2
            hint = "champ 2"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        password = EditText(this).apply {
            id = R.id.test_password
            hint = "mot de passe"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        col.addView(field1)
        col.addView(field2)
        col.addView(password)
        setContentView(col)
        field1.requestFocus()
    }
}
