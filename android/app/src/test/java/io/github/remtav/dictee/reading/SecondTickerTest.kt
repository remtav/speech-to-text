package io.github.remtav.dictee.reading

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SecondTickerTest {
    @Test fun `ten buffers per second give one call per second`() {
        val seen = mutableListOf<Int>()
        val ticker = SecondTicker { seen += it }
        for (i in 1..35) ticker.update(i / 10f) // 3.5 s of 100 ms buffers
        assertEquals(listOf(0, 1, 2, 3), seen)
    }

    @Test fun `reset starts counting again`() {
        val seen = mutableListOf<Int>()
        val ticker = SecondTicker { seen += it }
        ticker.update(1.2f); ticker.reset(); ticker.update(0.1f)
        assertEquals(listOf(1, 0), seen)
    }

    /** The progress callbacks of the model must not rebuild the whole screen (issue #20). */
    @Test fun `progress callbacks of the model do not call changed`() {
        val src = File(ReadingTestSupport.mainDir, "java/io/github/remtav/dictee/ui/ReadingTestModel.kt").readText()
        val recording = src.substringAfter("onProgress = {").substringBefore("} catch")
        val scoring = src.substringAfter("progress = { p ->").substringBefore("cancelled =")
        assertEquals(false, recording.contains("changed()"))
        assertEquals(false, scoring.contains("changed()"))
    }
}
