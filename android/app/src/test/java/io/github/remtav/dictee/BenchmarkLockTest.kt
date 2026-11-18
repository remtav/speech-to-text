package io.github.remtav.dictee

import io.github.remtav.dictee.stt.BenchmarkLock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Issue #66, points 3 and 4: one guard, with a token, for the three measuring screens. */
class BenchmarkLockTest {
    @Test
    fun aScreenThatDoesNotOwnTheLockCannotGiveTheKeyboardBack() {
        val lock = BenchmarkLock()
        // A « Comparer » or reading-test thread that outlived its screen: its claim was already given back once.
        val old = lock.tryClaim("comparer")!!
        lock.release(old)
        val quick = lock.tryClaim("test rapide")!!
        // Its late finally runs in the middle of the quick test.
        assertFalse(lock.release(old))
        assertTrue("the quick test still holds the keyboard", lock.held)
        assertTrue(lock.release(quick))
        assertFalse(lock.held)
        // Released once: a second release (a late finally) changes nothing.
        assertFalse(lock.release(quick))
    }

    @Test
    fun aSecondScreenCannotStartWhileOneMeasures() {
        val lock = BenchmarkLock()
        val reading = lock.tryClaim("test de lecture")!!
        assertNull(lock.tryClaim("comparer"))
        assertNull(lock.tryClaim("test rapide"))
        lock.release(reading)
        val compare = lock.tryClaim("comparer")
        assertNotNull(compare)
        assertEquals("comparer", compare!!.holder)
    }

    @Test
    fun noScreenStartsWhileADecodeLingersButTheKeyboardIsFree() {
        val lock = BenchmarkLock()
        val claim = lock.tryClaim("test rapide")!!
        val decode = lock.beginLingering()
        lock.release(claim)
        assertFalse("the keyboard gets the engine back at once", lock.held)
        assertTrue(lock.busy)
        assertNull("« Comparer » would load a second big model", lock.tryClaim("comparer"))
        assertNull(lock.tryClaim("test de lecture"))
        decode.end()
        assertFalse(lock.busy)
        assertNotNull(lock.tryClaim("comparer"))
    }

    @Test
    fun theEndOfOneDecodeDoesNotHideAnother() {
        val lock = BenchmarkLock()
        val first = lock.beginLingering()
        val second = lock.beginLingering()
        first.end()
        assertTrue(lock.lingering)
        // Ending the same decode twice does not hide the other one either.
        first.end()
        assertTrue(lock.lingering)
        assertNull(lock.tryClaim("test rapide"))
        second.end()
        assertFalse(lock.lingering)
    }

    @Test
    fun onlyOneOfManyScreensRacingGetsTheLock() {
        val lock = BenchmarkLock()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val winners = AtomicInteger()
        repeat(64) { n ->
            pool.execute {
                start.await()
                if (lock.tryClaim("écran $n") != null) winners.incrementAndGet()
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertTrue("${winners.get()} screens measured together", winners.get() == 1)
    }
}
