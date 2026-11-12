package io.github.remtav.dictee

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CI guard of issue #15, step 1.5: no colour and no font written by hand outside the palette and the
 * theme. Colours live in `res/values/colors.xml` and `res/values-night/colors.xml` (and are used through
 * theme attributes or `@color/...`), fonts live in `res/font` and are used through the named text styles
 * of the theme. The guard walks the sources, so it runs in the existing unit-test job.
 * It also checks that no downloadable font is declared (the app must not depend on an online service to display).
 */
class NoHardcodedStyleTest {
    private val main = File(System.getProperty("dictee.assetsDir")!!).parentFile
    private val res = main.resolve("res")

    private fun files(root: File, ext: String) = root.walkTopDown().filter { it.isFile && it.extension == ext }.toList()

    private fun relative(f: File) = f.relativeTo(main).path

    private fun stripKotlinComments(src: String): String =
        src.replace(Regex("/\\*[\\s\\S]*?\\*/"), "").lines().joinToString("\n") { it.replace(Regex("(^|\\s)//.*$"), "") }

    private fun stripXmlComments(src: String): String = src.replace(Regex("<!--[\\s\\S]*?-->"), "")

    private val hexColour = Regex("#[0-9A-Fa-f]{8}\\b|#[0-9A-Fa-f]{6}\\b|#[0-9A-Fa-f]{4}\\b|#[0-9A-Fa-f]{3}\\b")

    @Test
    fun kotlinSourcesHaveNoColourOrFontWrittenByHand() {
        val forbidden = listOf(
            "hexadecimal colour" to hexColour,
            "Color.parseColor/rgb/argb/valueOf" to Regex("\\bColor\\s*\\.\\s*(parseColor|rgb|argb|valueOf|HSVToColor)\\b"),
            "named android.graphics.Color constant" to Regex("\\bColor\\s*\\.\\s*(RED|GREEN|BLUE|BLACK|WHITE|GRAY|GREY|CYAN|MAGENTA|YELLOW|DKGRAY|LTGRAY|TRANSPARENT)\\b"),
            "ARGB integer literal" to Regex("\\b0x[0-9A-Fa-f]{8}\\b"),
            "Typeface built in code" to Regex("\\bTypeface\\s*\\.\\s*(create|createFromAsset|createFromFile|DEFAULT|DEFAULT_BOLD|SANS_SERIF|SERIF|MONOSPACE)\\b"),
            "android.R.color framework colour" to Regex("android\\.R\\.color\\."),
        )
        val problems = ArrayList<String>()
        for (f in files(main.resolve("java"), "kt")) {
            val text = stripKotlinComments(f.readText())
            for ((what, re) in forbidden) re.findAll(text).forEach { problems += "${relative(f)} : $what « ${it.value} »" }
        }
        assertTrue("couleur ou police en dur dans Kotlin :\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun layoutsAndResourcesHaveNoHexColourExceptThePalette() {
        val problems = ArrayList<String>()
        for (f in files(res, "xml")) {
            if (f.name == "colors.xml") continue // the palette: the only place for a hexadecimal colour
            val text = stripXmlComments(f.readText())
            hexColour.findAll(text).forEach { problems += "${relative(f)} : couleur en dur « ${it.value} »" }
            if (f.parentFile.name.startsWith("layout")) {
                Regex("android:(textColor|background|tint|fontFamily|typeface)=\"([^\"]*)\"").findAll(text).forEach { m ->
                    val v = m.groupValues[2]
                    val ok = v.startsWith("?") || v.startsWith("@") && !v.startsWith("@android:color/")
                    if (!ok) problems += "${relative(f)} : ${m.value}"
                }
            }
        }
        assertTrue("couleur en dur dans les ressources :\n" + problems.joinToString("\n"), problems.isEmpty())
        // the manifest too
        assertTrue(!hexColour.containsMatchIn(stripXmlComments(main.resolve("AndroidManifest.xml").readText())))
    }

    @Test
    fun fontsAreAlwaysReferencedAsEmbeddedFontResources() {
        val problems = ArrayList<String>()
        for (f in files(res, "xml")) {
            val text = stripXmlComments(f.readText())
            Regex("android:fontFamily\">?\"?([^<\"]*)").findAll(text).forEach { }
            Regex("name=\"(android:)?fontFamily\">([^<]*)<").findAll(text).forEach { m ->
                if (!m.groupValues[2].trim().startsWith("@font/") && !m.groupValues[2].trim().startsWith("?")) {
                    problems += "${relative(f)} : fontFamily « ${m.groupValues[2]} » ne vient pas de res/font"
                }
            }
            Regex("android:fontFamily=\"([^\"]*)\"").findAll(text).forEach { m ->
                if (!m.groupValues[1].startsWith("@font/") && !m.groupValues[1].startsWith("?")) {
                    problems += "${relative(f)} : fontFamily « ${m.groupValues[1]} » ne vient pas de res/font"
                }
            }
        }
        assertTrue("police hors de res/font :\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun noDownloadableFontIsDeclared() {
        val tokens = listOf(
            "fontProviderAuthority", "fontProviderPackage", "fontProviderQuery", "fontProviderCerts",
            "com.google.android.gms.fonts", "preloaded_fonts", "FontRequest", "FontsContractCompat",
            "DownloadableFontsCompat", "androidx.core.provider",
        )
        val scanned = files(main, "kt") + files(main, "xml") + listOf(main.parentFile.resolve("build.gradle.kts"))
        val problems = ArrayList<String>()
        for (f in scanned.filter { it.exists() }) {
            val text = f.readText()
            for (t in tokens) if (text.contains(t)) problems += "${f.name} : $t"
        }
        assertTrue("police téléchargeable déclarée :\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun embeddedFontFamiliesPointAtFilesShippedWithTheirLicence() {
        val fontDir = res.resolve("font")
        val ttf = fontDir.listFiles()!!.filter { it.extension == "ttf" }.map { it.nameWithoutExtension }.toSet()
        assertTrue("polices attendues dans res/font : $ttf", ttf.containsAll(
            listOf("newsreader_vf", "newsreader_italic_vf", "ibm_plex_mono_regular", "ibm_plex_mono_medium"),
        ))
        for (family in fontDir.listFiles()!!.filter { it.extension == "xml" }) {
            Regex("android:font=\"@font/([a-z0-9_]+)\"").findAll(family.readText()).forEach {
                assertTrue("${family.name} renvoie à @font/${it.groupValues[1]}, absent de res/font", it.groupValues[1] in ttf)
            }
        }
        for (licence in listOf("OFL-Newsreader.txt", "OFL-IBMPlexMono.txt")) {
            val f = main.resolve("assets/licences/$licence")
            assertTrue("licence manquante : $licence", f.isFile)
            assertTrue("$licence n'est pas la licence OFL", f.readText().contains("SIL OPEN FONT LICENSE"))
        }
    }
}
