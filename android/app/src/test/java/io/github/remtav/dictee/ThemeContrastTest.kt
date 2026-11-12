package io.github.remtav.dictee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Kalmia theme (issue #15, step 1.5). Reads the colours and the theme from the resource files
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
        Triple("colorOnSurface", "colorSurfaceContainer", "texte des cartes, des touches, des zones de saisie"),
        Triple("colorOnSurfaceVariant", "colorSurface", "texte secondaire, étiquettes, état du clavier"),
        Triple("colorOnSurfaceVariant", "colorSurfaceContainer", "texte secondaire dans les cartes"),
        Triple("colorPrimary", "colorSurface", "titres de section, badge « Recommandé », liens"),
        Triple("colorPrimary", "colorSurfaceContainer", "badge « Recommandé » dans les cartes"),
        Triple("colorOnPrimary", "colorPrimary", "coche, interrupteur et sélection activés"),
        Triple("colorOnPrimaryContainer", "colorPrimaryContainer", "boutons d'action, micro au repos"),
        Triple("colorOnTertiaryContainer", "colorTertiaryContainer", "micro à l'écoute (fleur)"),
        Triple("colorTertiary", "colorSurface", "mots surlignés du test de lecture (lot 4), fleur sur papier"),
        Triple("colorTertiary", "colorSurfaceContainer", "mots surlignés dans une carte (lot 4), fleur sur papier 2"),
        Triple("colorError", "colorSurface", "erreur du clavier et des écrans (avec texte et icône)"),
        Triple("colorError", "colorSurfaceContainer", "avertissement dans une carte (avec texte et icône)"),
        Triple("colorOnSecondaryContainer", "colorSecondaryContainer", "éléments sélectionnés des composants Material"),
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
    fun controlOutlineHasAtLeast3ToOneOnPaperAndOnPaper2() {
        // Borders of keys and inputs are functional graphics: WCAG 1.4.11 asks 3:1.
        for ((name, palette) in listOf("clair" to light, "sombre" to dark)) {
            for (bg in listOf("colorSurface", "colorSurfaceContainer")) {
                val r = ratio(resolve(palette, "colorOutline"), resolve(palette, bg))
                assertTrue("$name : colorOutline sur $bg = %.2f:1".format(r), r >= 3.0)
            }
        }
    }

    @Test
    fun errorIsTheMaterialRedAndNeverTheFlowerPink() {
        assertEquals("#BA1A1A", resolve(light, "colorError").uppercase())
        assertEquals("#FFB4AB", resolve(dark, "colorError").uppercase())
        for (palette in listOf(light, dark)) {
            assertNotEquals(resolve(palette, "colorTertiary"), resolve(palette, "colorError"))
            assertNotEquals(resolve(palette, "colorOnErrorContainer"), resolve(palette, "colorTertiary"))
        }
    }

    @Test
    fun paletteIsTheKalmiaOneFromTheSource() {
        // Values read on 2026-11-12 in kalmia.ca (index.html) and design/marque/tokens.css of the Kalmia.ca repository.
        val expectedLight = mapOf(
            "kalmia_paper" to "#F5F1E8", "kalmia_paper_2" to "#EDE7DA", "kalmia_ink" to "#23261F",
            "kalmia_muted" to "#5B5E54", "kalmia_rule" to "#CFC8B8", "kalmia_green" to "#3F5E45",
            "kalmia_button" to "#8FAE93", "kalmia_on_button" to "#23261F",
            "kalmia_flower" to "#A9446A", "kalmia_on_flower" to "#FFFFFF",
        )
        val expectedDark = mapOf(
            "kalmia_paper" to "#13160F", "kalmia_paper_2" to "#1B1F17", "kalmia_ink" to "#ECE7DA",
            "kalmia_muted" to "#A9AB9B", "kalmia_rule" to "#3A3E33", "kalmia_green" to "#8DB596",
            "kalmia_button" to "#8DB596", "kalmia_on_button" to "#13160F",
            "kalmia_flower" to "#DE85A7", "kalmia_on_flower" to "#13160F",
        )
        for ((k, v) in expectedLight) assertEquals("clair $k", v, light[k]!!.uppercase())
        for ((k, v) in expectedDark) assertEquals("sombre $k", v, dark[k]!!.uppercase())
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
            assertTrue("$role doit renvoyer à la palette, pas à une valeur écrite ici : $ref", ref!!.startsWith("@color/kalmia_"))
            assertTrue("$role : couleur absente en clair", light.containsKey(ref.removePrefix("@color/")))
        }
    }

    @Test
    fun everyPaletteColourHasADarkValue() {
        for (name in light.keys) assertTrue("values-night/colors.xml doit définir $name", colors("values-night").containsKey(name))
    }
}
