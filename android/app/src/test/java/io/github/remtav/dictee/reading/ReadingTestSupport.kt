package io.github.remtav.dictee.reading

import java.io.File

/** Reads the app's assets and sources from the JVM tests (same property as the other host tests). */
object ReadingTestSupport {
    val assetsDir: File = File(System.getProperty("dictee.assetsDir") ?: "src/main/assets")
    val mainDir: File get() = assetsDir.parentFile
    val androidDir: File get() = mainDir.parentFile.parentFile.parentFile

    fun readAsset(path: String): String = File(assetsDir, path).readText()

    val material: ReadingMaterial by lazy { ReadingMaterial.load(readAsset = ::readAsset) }
}
