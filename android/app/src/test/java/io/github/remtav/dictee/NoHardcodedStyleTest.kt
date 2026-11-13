package io.github.remtav.dictee

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CI guard of issue #15: no colour and no font written by hand outside the palette and the theme.
 * Colours live in `res/values/colors.xml` and `res/values-night/colors.xml` (and are used through
 * theme attributes or `@color/...`); text uses the named text styles of the theme, which point at the
 * Material defaults (system fonts). The guard walks the sources, so it runs in the existing unit-test job.
 * It also checks that no font is embedded or downloadable (the app must not depend on an online service to display).
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
            "RGB integer literal" to Regex("\\b0x[0-9A-Fa-f]{6}\\b"),
            "Typeface built in code" to Regex("\\bTypeface\\s*\\.\\s*Builder\\b"),
            "system text appearance" to Regex("android\\.R\\.style\\.TextAppearance"),
            "font family by name" to Regex("\\bsetTypeface\\s*\\(|\\bfontFamily\\s*="),
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
            val dir = f.parentFile.name
            if (dir.startsWith("layout")) {
                // Any colour, tint, background or font attribute (android: or app:) must come from the theme or the palette.
                Regex("(android|app):([A-Za-z]*(Color|Tint|tint|background|Background|fontFamily|typeface))=\"([^\"]*)\"").findAll(text).forEach { m ->
                    val v = m.groupValues[4]
                    val ok = v.startsWith("?") || v.startsWith("@") && !v.startsWith("@android:color/")
                    if (!ok) problems += "${relative(f)} : ${m.value}"
                }
            }
            // Framework colours bypass the palette; system text appearances bring the system font.
            // A vector drawable may fill with @android:color/white only if its root is tinted from the theme.
            val tintedDrawable = dir.startsWith("drawable") && Regex("<vector[^>]*android:tint=\"\\?").containsMatchIn(text)
            if (!tintedDrawable && text.contains("@android:color/")) problems += "${relative(f)} : couleur du système « @android:color/ »"
            Regex("@android:style/TextAppearance|\\?android:(attr/)?textAppearance").findAll(text).forEach {
                problems += "${relative(f)} : apparence de texte du système « ${it.value} »"
            }
        }
        assertTrue("couleur en dur dans les ressources :\n" + problems.joinToString("\n"), problems.isEmpty())
        // the manifest too
        assertTrue(!hexColour.containsMatchIn(stripXmlComments(main.resolve("AndroidManifest.xml").readText())))
    }

    @Test
    fun noFontIsEmbeddedOrNamedInResources() {
        assertTrue("res/font ne doit pas exister : polices du système seulement", !res.resolve("font").exists())
        val problems = ArrayList<String>()
        for (f in files(res, "xml")) {
            val text = stripXmlComments(f.readText())
            Regex("(android:)?fontFamily\"?[=>]").findAll(text).forEach { problems += "${relative(f)} : fontFamily" }
            if (text.contains("@font/")) problems += "${relative(f)} : @font/"
        }
        for (f in files(main, "ttf") + files(main, "otf")) problems += "${relative(f)} : fichier de police embarqué"
        assertTrue("police embarquée ou nommée :\n" + problems.joinToString("\n"), problems.isEmpty())
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
}
