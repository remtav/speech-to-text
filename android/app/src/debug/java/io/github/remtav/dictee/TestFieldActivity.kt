package io.github.remtav.dictee

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout

/** Two text fields and a password field, for the instrumented IME tests (debug builds only). */
class TestFieldActivity : AppCompatActivity() {
    lateinit var field1: EditText
    lateinit var field2: EditText
    lateinit var password: EditText
    /** Two fields with no id: same EditorInfo key, like inputs of one web page. */
    lateinit var noId1: EditText
    lateinit var noId2: EditText
    /** Digit fields (issue #42): whole number, signed decimal, phone. */
    lateinit var number: EditText
    lateinit var decimal: EditText
    lateinit var phone: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16) }
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
        noId1 = EditText(this).apply { hint = "sans id 1"; inputType = InputType.TYPE_CLASS_TEXT; isSaveEnabled = false }
        noId2 = EditText(this).apply { hint = "sans id 1"; inputType = InputType.TYPE_CLASS_TEXT; isSaveEnabled = false }
        number = EditText(this).apply { id = R.id.test_number; hint = "nombre"; inputType = InputType.TYPE_CLASS_NUMBER }
        decimal = EditText(this).apply {
            id = R.id.test_decimal
            hint = "montant"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        phone = EditText(this).apply { id = R.id.test_phone; hint = "téléphone"; inputType = InputType.TYPE_CLASS_PHONE }
        col.addView(field1)
        col.addView(field2)
        col.addView(password)
        col.addView(noId1)
        col.addView(noId2)
        col.addView(number)
        col.addView(decimal)
        col.addView(phone)
        setContentView(col)
        field1.requestFocus()
    }
}
