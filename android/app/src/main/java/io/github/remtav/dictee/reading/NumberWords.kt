package io.github.remtav.dictee.reading

/**
 * Spelling of whole numbers (0 to 999 999 999) in the languages of the quick reading test (issue #36a), so a model
 * that writes digits is not penalised against a text written in letters, as [FrenchNumbers] does for French.
 *
 * One spelling per number, the commonest one: American English without « and », Spanish and Brazilian
 * Portuguese (« dezesseis »). A model that says « one hundred and five » is not forgiven; the quick texts contain
 * no digits, so this only matters for what a model adds.
 */
object NumberWords {
    const val MAX = 999_999_999L

    /** Words of [n] in [language] (`en`, `es`, `pt`, `fr`), or null for a language without a spelling here. */
    fun words(language: String, n: Long): String? {
        require(n in 0..MAX) { "hors limites : $n" }
        return when (language) {
            "fr" -> FrenchNumbers.words(n)
            "en" -> English.words(n)
            "es" -> Spanish.words(n)
            "pt" -> Portuguese.words(n)
            else -> null
        }
    }

    private fun groups(n: Long) = Triple((n / 1_000_000).toInt(), ((n / 1000) % 1000).toInt(), (n % 1000).toInt())

    object English {
        private val units = listOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
            "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
        )
        private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

        fun words(n: Long): String {
            if (n == 0L) return units[0]
            val (m, t, r) = groups(n)
            return listOfNotNull(
                if (m > 0) below1000(m) + " million" else null,
                if (t > 0) below1000(t) + " thousand" else null,
                if (r > 0) below1000(r) else null,
            ).joinToString(" ")
        }

        private fun below1000(n: Int): String {
            val h = n / 100
            val r = n % 100
            return listOfNotNull(
                if (h > 0) units[h] + " hundred" else null,
                if (r > 0) below100(r) else null,
            ).joinToString(" ")
        }

        private fun below100(n: Int) =
            if (n < 20) units[n] else tens[n / 10] + if (n % 10 > 0) "-" + units[n % 10] else ""
    }

    object Spanish {
        private val units = listOf(
            "cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez", "once",
            "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete", "dieciocho", "diecinueve", "veinte",
            "veintiuno", "veintidós", "veintitrés", "veinticuatro", "veinticinco", "veintiséis", "veintisiete",
            "veintiocho", "veintinueve",
        )
        private val tens = listOf("", "", "", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa")
        private val hundreds = listOf(
            "", "ciento", "doscientos", "trescientos", "cuatrocientos", "quinientos",
            "seiscientos", "setecientos", "ochocientos", "novecientos",
        )

        fun words(n: Long): String {
            if (n == 0L) return units[0]
            val (m, t, r) = groups(n)
            return listOfNotNull(
                if (m > 0) (if (m == 1) "un millón" else below1000(m, apocope = true) + " millones") else null,
                // « mil », « dos mil », « veintiún mil » (the final « uno » loses its « o » before a noun).
                if (t > 0) (if (t == 1) "mil" else below1000(t, apocope = true) + " mil") else null,
                if (r > 0) below1000(r, apocope = false) else null,
            ).joinToString(" ")
        }

        private fun below1000(n: Int, apocope: Boolean): String {
            if (n == 100) return "cien"
            val h = n / 100
            val r = n % 100
            val parts = listOfNotNull(if (h > 0) hundreds[h] else null, if (r > 0) below100(r) else null)
            val s = parts.joinToString(" ")
            return if (apocope && s.endsWith("uno")) s.removeSuffix("uno").let { if (it.endsWith("veinti")) it + "ún" else it + "un" } else s
        }

        private fun below100(n: Int) = when {
            n < 30 -> units[n]
            n % 10 == 0 -> tens[n / 10]
            else -> tens[n / 10] + " y " + units[n % 10]
        }
    }

    object Portuguese {
        private val units = listOf(
            "zero", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez", "onze",
            "doze", "treze", "catorze", "quinze", "dezesseis", "dezessete", "dezoito", "dezenove",
        )
        private val tens = listOf("", "", "vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa")
        private val hundreds = listOf(
            "", "cento", "duzentos", "trezentos", "quatrocentos", "quinhentos",
            "seiscentos", "setecentos", "oitocentos", "novecentos",
        )

        fun words(n: Long): String {
            if (n == 0L) return units[0]
            val (m, t, r) = groups(n)
            val parts = mutableListOf<String>()
            if (m > 0) parts += if (m == 1) "um milhão" else below1000(m) + " milhões"
            if (t > 0) parts += if (t == 1) "mil" else below1000(t) + " mil"
            if (r > 0) parts += below1000(r)
            // « e » joins the last two groups when the last is under 100 or a round hundred: « mil e cinco », « mil e cem ».
            val sb = StringBuilder(parts[0])
            for (i in 1 until parts.size) {
                val last = i == parts.size - 1
                val value = if (last) r else t
                val round = value < 100 || value % 100 == 0
                sb.append(if (last && round) " e " else " ").append(parts[i])
            }
            return sb.toString()
        }

        private fun below1000(n: Int): String {
            if (n == 100) return "cem"
            val h = n / 100
            val r = n % 100
            return listOfNotNull(if (h > 0) hundreds[h] else null, if (r > 0) below100(r) else null).joinToString(" e ")
        }

        private fun below100(n: Int) =
            if (n < 20) units[n] else tens[n / 10] + if (n % 10 > 0) " e " + units[n % 10] else ""
    }
}
