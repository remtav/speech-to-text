package io.github.remtav.dictee.ime

import io.github.remtav.dictee.dictation.TextPipeline
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #81: while a dictation is still typing its text, the person may touch the field. The rest of the
 * dictation goes right after what was already dictated, wherever the cursor is, and the person's cursor stays
 * where they put it.
 */
class ImeTextSinkTest {
    private val pipeline = TextPipeline()

    private fun sinkFor(ic: FakeInputConnection) = ImeTextSink(ic, ic.selStart, ic.selEnd)

    /** The system tells the keyboard about the selection changes, after the edits. */
    private fun FakeInputConnection.deliverTo(sink: ImeTextSink) {
        while (pendingUpdates.isNotEmpty()) {
            val (start, end) = pendingUpdates.removeFirst()
            sink.onUpdateSelection(start, end)
        }
    }

    private fun ImeTextSink.say(ic: FakeInputConnection, text: String, deliver: Boolean = true) {
        pipeline.applyFinal(text, null, this)
        if (deliver) ic.deliverTo(this)
    }

    @Test fun untouchedFieldFollowsTheDictation() {
        val ic = FakeInputConnection()
        val sink = sinkFor(ic)
        sink.say(ic, "bonjour.")
        sink.say(ic, "comment ça va?")
        assertEquals("Bonjour. Comment ça va?", ic.text)
        assertEquals(ic.text.length, ic.selStart)
    }

    @Test fun touchingTheStartKeepsTheDictationAtTheEnd() {
        val ic = FakeInputConnection()
        val sink = sinkFor(ic)
        sink.say(ic, "Bonjour.")
        ic.tap(0)
        ic.deliverTo(sink)
        sink.say(ic, "comment ça va?")
        assertEquals("Bonjour. Comment ça va?", ic.text)
        assertEquals("the person's cursor stays where they put it", 0, ic.selStart)
    }

    @Test fun spacingAndCapitalsAreReadAtTheEndOfTheDictation() {
        val ic = FakeInputConnection()
        val sink = sinkFor(ic)
        sink.say(ic, "Nous avons mangé")
        ic.tap(4)
        ic.deliverTo(sink)
        sink.say(ic, "Et puis nous sommes partis")
        assertEquals("Nous avons mangé et puis nous sommes partis", ic.text)
        assertEquals(4, ic.selStart)
    }

    @Test fun selectionUpdatesArrivingLateAreNotTakenForATouch() {
        val ic = FakeInputConnection()
        val sink = sinkFor(ic)
        sink.say(ic, "Un.", deliver = false)
        sink.say(ic, "deux.", deliver = false)
        ic.tap(0)
        ic.deliverTo(sink)
        sink.say(ic, "trois.")
        assertEquals("Un. Deux. Trois.", ic.text)
        assertEquals(0, ic.selStart)
        // And when the field is not touched, late updates do not leave the cursor behind.
        val ic2 = FakeInputConnection()
        val sink2 = sinkFor(ic2)
        sink2.say(ic2, "Un.", deliver = false)
        sink2.say(ic2, "deux.", deliver = false)
        ic2.deliverTo(sink2)
        sink2.say(ic2, "trois.")
        assertEquals("Un. Deux. Trois.", ic2.text)
        assertEquals(ic2.text.length, ic2.selStart)
    }

    @Test fun touchingTheFieldWhileTheProvisionalTextShows() {
        val ic = FakeInputConnection("Avant.")
        val sink = sinkFor(ic)
        pipeline.preview("bonjour", sink)
        ic.deliverTo(sink)
        assertEquals("Avant. Bonjour", ic.text)
        ic.tap(0)
        ic.deliverTo(sink)
        pipeline.preview("bonjour tout", sink)
        ic.deliverTo(sink)
        assertEquals("Avant. Bonjour tout", ic.text)
        assertEquals(0, ic.selStart)
        sink.say(ic, "bonjour tout le monde.")
        assertEquals("Avant. Bonjour tout le monde.", ic.text)
        assertEquals("", ic.composing)
        assertEquals(0, ic.selStart)
    }

    @Test fun textAddedBeforeTheDictationMovesItsEnd() {
        val ic = FakeInputConnection()
        val sink = sinkFor(ic)
        sink.say(ic, "Bonjour.")
        ic.tap(0)
        ic.deliverTo(sink)
        ic.userReplace(0, 0, "Allo. ")
        ic.deliverTo(sink)
        sink.say(ic, "merci.")
        assertEquals("Allo. Bonjour. Merci.", ic.text)
        assertEquals(6, ic.selStart)
    }

    @Test fun textRemovedBeforeTheDictationMovesItsEnd() {
        val ic = FakeInputConnection("Début. ")
        val sink = sinkFor(ic)
        sink.say(ic, "Bonjour.")
        ic.userReplace(0, 7, "")
        ic.deliverTo(sink)
        sink.say(ic, "merci.")
        assertEquals("Bonjour. Merci.", ic.text)
        assertEquals(0, ic.selStart)
    }

    @Test fun aCursorAfterTheDictationMovesWithTheText() {
        val ic = FakeInputConnection(" fin", cursor = 0)
        val sink = sinkFor(ic)
        sink.say(ic, "un")
        assertEquals("Un fin", ic.text)
        ic.tap(6)
        ic.deliverTo(sink)
        sink.say(ic, "deux")
        assertEquals("Un deux fin", ic.text)
        assertEquals(11, ic.selStart)
    }

    @Test fun voiceCommandsActOnTheEndOfTheDictation() {
        val ic = FakeInputConnection()
        val sink = sinkFor(ic)
        sink.say(ic, "Un deux trois")
        ic.tap(0)
        ic.deliverTo(sink)
        sink.say(ic, "Delete last word.")
        assertEquals("Un deux", ic.text)
        sink.say(ic, "Nouvelle ligne.")
        sink.say(ic, "quatre")
        assertEquals("Un deux\nQuatre", ic.text)
        assertEquals(0, ic.selStart)
    }

    @Test fun aNewConnectionToTheSameFieldKeepsTheEnd() {
        val ic = FakeInputConnection()
        val sink = sinkFor(ic)
        sink.say(ic, "Bonjour.")
        ic.tap(0)
        ic.deliverTo(sink)
        val again = ImeTextSink(ic, ic.selStart, ic.selEnd, previous = sink)
        again.say(ic, "merci.")
        assertEquals("Bonjour. Merci.", ic.text)
        assertEquals(0, ic.selStart)
    }

    @Test fun aNewDictationStartsAtTheCursor() {
        val ic = FakeInputConnection()
        val sink = sinkFor(ic)
        sink.say(ic, "Bonjour.")
        ic.tap(0)
        ic.deliverTo(sink)
        sink.beginDictation()
        sink.say(ic, "Salut.")
        assertEquals("Salut.Bonjour.", ic.text)
    }

    @Test fun dictatedTextDeletedByThePersonContinuesAtTheCursor() {
        val ic = FakeInputConnection()
        val sink = sinkFor(ic)
        sink.say(ic, "Bonjour.")
        ic.userReplace(0, 8, "")
        ic.deliverTo(sink)
        sink.say(ic, "merci.")
        assertEquals("Merci.", ic.text)
    }

    @Test fun aSelectionIsReplacedByTheFirstWords() {
        val ic = FakeInputConnection("un deux trois")
        ic.tap(3, 7)
        ic.pendingUpdates.clear()
        val sink = sinkFor(ic)
        sink.say(ic, "quatre")
        assertEquals("un quatre trois", ic.text)
    }
}
