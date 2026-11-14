package io.github.remtav.dictee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Generic Material 3 theme. Reads the colours and the theme from the resource files
 * (`res/values/colors.xml`, `res/values-night/colors.xml`, `res/values/themes.xml`), never from a copy
 * kept here, and requires a contrast of at least 4.5:1 (WCAG AA) for every text/background pair the
 * app really uses, in the light and the dark theme.
 */
class ThemeContrastTest {
    private val res = File(System.getProperty("dictee.assetsDir")!!).parentFile.resolve("res")

    private fun parse(file: File): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

    private fun colors(dir: String): Map<String, String> {
        val nodes = parse(res.resolve("$dir/colors.xml")).getElementsByTagName("color")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent.trim() }
    }

    /** Theme item name -> "@color/name". */
    private val theme: Map<String, String> by lazy {
        val styles = parse(res.resolve("values/themes.xml")).getElementsByTagName("style")
        val style = (0 until styles.length).map { styles.item(it) as Element }.first { it.getAttribute("name") == "Theme.Dictee" }
        val items = style.getElementsByTagName("item")
        (0 until items.length).map { items.item(it) as Element }.associate { it.getAttribute("name") to it.textContent.trim() }
    }

    private val light by lazy { colors("values") }
    private val dark by lazy {
        // The night file only overrides: every other name keeps its light value.
        light + colors("values-night")
    }

    private fun resolve(palette: Map<String, String>, attr: String): String {
        val ref = theme[attr] ?: error("theme item $attr is not defined in Theme.Dictee")
        assertTrue("$attr must point at a palette colour, got $ref", ref.startsWith("@color/"))
        return palette[ref.removePrefix("@color/")] ?: error("colour $ref is not defined")
    }

    private fun luminance(hex: String): Double {
        val v = hex.removePrefix("#")
        assertEquals("opaque #RRGGBB expected, got $hex", 6, v.length)
        fun channel(i: Int): Double {
            val c = v.substring(i, i + 2).toInt(16) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4)
    }

    private fun ratio(a: String, b: String): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    /** (text attribute, background attribute, where the pair is used) */
    private val pairs = listOf(
        Triple("colorOnSurface", "colorSurface", "texte courant sur les écrans et le clavier"),
        Triple("colorOnSurface", "colorSurfaceContainer", "texte des cartes et fond du clavier"),
        Triple("colorOnSurfaceVariant", "colorSurface", "texte secondaire, étiquettes"),
        Triple("colorOnSurfaceVariant", "colorSurfaceContainer", "état du clavier, texte secondaire dans les cartes"),
        Triple("colorPrimary", "colorSurface", "titres de section, badge « Recommandé », liens"),
        Triple("colorPrimary", "colorSurfaceContainer", "badge « Recommandé » dans les cartes, signe obtenu des exemples de « Comment dicter »"),
        Triple("colorOnPrimary", "colorPrimary", "boutons d'action, micro au repos, coche et interrupteur activés"),
        Triple("colorOnPrimaryContainer", "colorPrimaryContainer", "éléments sélectionnés, interrupteur"),
        Triple("colorOnSecondaryContainer", "colorSecondaryContainer", "touches du clavier, boutons tonals, commande dite des exemples de « Comment dicter »"),
        Triple("colorOnError", "colorError", "micro à l'écoute (rouge)"),
        Triple("colorError", "colorSurface", "erreur du clavier et des écrans (avec texte et icône)"),
        Triple("colorError", "colorSurfaceContainer", "erreur dans une carte et sur le clavier (avec texte et icône)"),
        Triple("colorOnErrorContainer", "colorErrorContainer", "conteneurs d'erreur des composants Material"),
        Triple("colorOnTertiaryContainer", "colorTertiaryContainer", "mots mal compris surlignés du test de lecture (avec soulignement) ; conteneurs tertiaires Material"),
        Triple("colorOnSurfaceInverse", "colorSurfaceInverse", "messages et info-bulles"),
    )

    @Test
    fun everyUsedTextPairHasAtLeast4_5ToOneInBothThemes() {
        val failures = ArrayList<String>()
        for ((name, palette) in listOf("clair" to light, "sombre" to dark)) {
            for ((fg, bg, use) in pairs) {
                val f = resolve(palette, fg)
                val b = resolve(palette, bg)
                val r = ratio(f, b)
                println("%-7s %-26s %s sur %-26s %s  %.2f:1  (%s)".format(name, fg, f, bg, b, r, use))
                if (r < 4.5) failures += "$name : $fg $f sur $bg $b = %.2f:1 (%s)".format(r, use)
            }
        }
        assertTrue("paires sous 4,5:1 :\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun controlOutlineHasAtLeast3ToOneOnSurfaceAndSurfaceContainer() {
        // Borders of outlined buttons and inputs are functional graphics: WCAG 1.4.11 asks 3:1.
        for ((name, palette) in listOf("clair" to light, "sombre" to dark)) {
            for (bg in listOf("colorSurface", "colorSurfaceContainer")) {
                val r = ratio(resolve(palette, "colorOutline"), resolve(palette, bg))
                assertTrue("$name : colorOutline sur $bg = %.2f:1".format(r), r >= 3.0)
            }
        }
    }

    @Test
    fun readingHighlightUsesAMeasuredPairAndAnUnderlineNeverTheErrorColourAlone() {
        // The misheard words of the reading test: the pair measured above, plus an underline (never the colour alone).
        val src = res.parentFile.resolve("java/io/github/remtav/dictee/ui/ReadingHighlight.kt").readText()
        val code = src.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        assertTrue(code.contains("attr.colorTertiaryContainer") && code.contains("attr.colorOnTertiaryContainer"))
        assertTrue("surlignage sans soulignement", code.contains("UnderlineSpan"))
        assertTrue("la couleur d'erreur ne sert pas au surlignage", !code.contains("colorError"))
        assertTrue(Triple("colorOnTertiaryContainer", "colorTertiaryContainer", "") .let { t -> pairs.any { it.first == t.first && it.second == t.second } })
    }

    @Test
    fun howToHighlightUsesMeasuredPairsAndNeverTheColourAlone() {
        // The command and the sign of the first screen of "Comment dicter" (issue #56): measured pairs, plus bold and underline.
        val src = res.parentFile.resolve("java/io/github/remtav/dictee/ui/HowToStyle.kt").readText()
        val code = src.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        assertTrue(code.contains("attr.colorSecondaryContainer") && code.contains("attr.colorOnSecondaryContainer"))
        assertTrue(code.contains("attr.colorPrimary"))
        assertTrue("mise en évidence sans gras", code.contains("Typeface.BOLD"))
        assertTrue("commande sans soulignement", code.contains("UnderlineSpan"))
        assertTrue("la couleur d'erreur ne sert pas à la mise en évidence", !code.contains("colorError"))
        for ((fg, bg) in listOf(
            "colorOnSecondaryContainer" to "colorSecondaryContainer",
            "colorPrimary" to "colorSurface",
            "colorPrimary" to "colorSurfaceContainer",
        )) assertTrue("$fg sur $bg n'est pas mesurée", pairs.any { it.first == fg && it.second == bg })
    }

    @Test
    fun errorIsTheMaterialRed() {
        assertEquals("#BA1A1A", resolve(light, "colorError").uppercase())
        assertEquals("#FFB4AB", resolve(dark, "colorError").uppercase())
    }

    @Test
    fun primaryIsTheOriginalBlue() {
        assertEquals("#2E5E8C", resolve(light, "colorPrimary").uppercase())
        assertEquals("#9FCAFF", resolve(dark, "colorPrimary").uppercase())
    }

    @Test
    fun everyMaterialColourRoleIsDefinedSoNothingFallsBackToTheDefaultPalette() {
        val roles = listOf(
            "colorPrimary", "colorOnPrimary", "colorPrimaryInverse", "colorPrimaryContainer", "colorOnPrimaryContainer",
            "colorSecondary", "colorOnSecondary", "colorSecondaryContainer", "colorOnSecondaryContainer",
            "colorTertiary", "colorOnTertiary", "colorTertiaryContainer", "colorOnTertiaryContainer",
            "colorError", "colorOnError", "colorErrorContainer", "colorOnErrorContainer",
            "android:colorBackground", "colorOnBackground", "colorSurface", "colorOnSurface",
            "colorSurfaceVariant", "colorOnSurfaceVariant", "colorSurfaceInverse", "colorOnSurfaceInverse",
            "colorSurfaceDim", "colorSurfaceBright", "colorSurfaceContainerLowest", "colorSurfaceContainerLow",
            "colorSurfaceContainer", "colorSurfaceContainerHigh", "colorSurfaceContainerHighest",
            "colorOutline", "colorOutlineVariant",
        )
        for (role in roles) {
            val ref = theme[role]
            assertTrue("rôle $role absent du thème", ref != null)
            assertTrue("$role doit renvoyer à la palette, pas à une valeur écrite ici : $ref", ref!!.startsWith("@color/md_"))
            assertTrue("$role : couleur absente en clair", light.containsKey(ref.removePrefix("@color/")))
        }
    }

    /** `res/color/<name>.xml` -> the colour of its last (default) item, e.g. "?attr/colorOnSurface". */
    private fun selectorDefault(name: String): String {
        val items = parse(res.resolve("color/$name.xml")).getElementsByTagName("item")
        return (items.item(items.length - 1) as Element).getAttribute("android:color")
    }

    @Test
    fun frameworkTextColoursFollowTheThemeNotTheMaterialDefaults() {
        // Without these, a TextView, CheckBox or RadioButton with no explicit colour takes the
        // default Material text colour (m3_default_color_primary_text), not one of the theme.
        val expected = mapOf(
            "android:textColorPrimary" to "?attr/colorOnSurface",
            "android:textColorSecondary" to "?attr/colorOnSurfaceVariant",
            "android:textColorTertiary" to "?attr/colorOnSurfaceVariant",
            "android:textColorPrimaryDisableOnly" to "?attr/colorOnSurface",
            "android:textColorHint" to "?attr/colorOnSurfaceVariant",
        )
        for ((item, attr) in expected) {
            val ref = theme[item] ?: error("$item absent du thème")
            assertTrue("$item doit renvoyer à res/color : $ref", ref.startsWith("@color/text_"))
            assertEquals(item, attr, selectorDefault(ref.removePrefix("@color/")))
        }
        for (item in listOf("android:textColorHighlight", "android:textColorPrimaryInverse", "android:textColorHintInverse")) {
            assertTrue("$item absent du thème", (theme[item]?.startsWith("@color/text_") == true || theme[item]?.startsWith("@color/md_") == true))
        }
    }

    @Test
    fun colourStateListsUseOnlyThemeRolesOrThePalette() {
        for (f in res.resolve("color").listFiles()!!.filter { it.extension == "xml" }) {
            val items = parse(f).getElementsByTagName("item")
            for (i in 0 until items.length) {
                val c = (items.item(i) as Element).getAttribute("android:color")
                assertTrue("${f.name} : $c", c.startsWith("?attr/color") || c.startsWith("@color/md_"))
            }
        }
    }

    @Test
    fun micSelectorsMatchTheTestedPairs() {
        // The foreground/background of the mic, as drawn, is one of the pairs measured above.
        val tested = pairs.map { it.first to it.second }.toSet()
        // Mic at rest: default item of each selector.
        val rest = selectorDefault("mic_icon").removePrefix("?attr/") to selectorDefault("mic_bg").removePrefix("?attr/")
        assertTrue("micro au repos $rest, paire non mesurée", rest in tested)
        // Mic while listening (activated state).
        fun activated(name: String): String {
            val items = parse(res.resolve("color/$name.xml")).getElementsByTagName("item")
            return (0 until items.length).map { items.item(it) as Element }
                .first { it.getAttribute("android:state_activated") == "true" }
                .getAttribute("android:color").removePrefix("?attr/")
        }
        val listening = activated("mic_icon") to activated("mic_bg")
        assertTrue("micro à l'écoute $listening, paire non mesurée", listening in tested)
        assertNotEquals("le micro à l'écoute doit se distinguer du micro au repos", rest, listening)
    }

    /** Items of the style [name] in themes.xml. */
    private fun style(name: String): Map<String, String> {
        val styles = parse(res.resolve("values/themes.xml")).getElementsByTagName("style")
        val style = (0 until styles.length).map { styles.item(it) as Element }.first { it.getAttribute("name") == name }
        val items = style.getElementsByTagName("item")
        return (0 until items.length).map { items.item(it) as Element }.associate { it.getAttribute("name") to it.textContent.trim() }
    }

    @Test
    fun keySymbolsAreAtLeast20spAndKeysAtLeast48dpHigh() {
        val key = style("KeyButton")
        assertTrue(key["android:layout_height"]!!.removeSuffix("dp").toFloat() >= 48f)
        // The gap between keys is an inset (inside the touch area), never an outer margin.
        assertTrue("marge externe sur les touches", key.keys.none { it.startsWith("android:layout_margin") })
        assertEquals("@style/TextAppearance.Dictee.Key", key["android:textAppearance"])
        assertTrue(style("TextAppearance.Dictee.Key")["android:textSize"]!!.removeSuffix("sp").toFloat() >= 20f)
    }

    @Test
    fun keyboardButtonsAreAtLeast48dpHigh() {
        val layout = res.resolve("layout/keyboard_view.xml").readText()
        for (id in listOf("model", "cancel_wait", "help", "settings")) {
            val tag = Regex("<[A-Za-z.]+\\s[^>]*android:id=\"@\\+id/$id\"[^>]*>").find(layout)?.value ?: error("$id absent du clavier")
            val h = Regex("android:layout_height=\"(\\d+)dp\"").find(tag)?.groupValues?.get(1)?.toInt() ?: error("$id sans hauteur en dp")
            assertTrue("$id : $h dp < 48 dp", h >= 48)
        }
    }

    @Test
    fun everyPaletteColourHasADarkValue() {
        for (name in light.keys.filter { it.startsWith("md_") }) assertTrue("values-night/colors.xml doit définir $name", colors("values-night").containsKey(name))
    }
}
