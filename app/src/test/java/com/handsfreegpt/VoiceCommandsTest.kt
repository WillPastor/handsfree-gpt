package com.handsfreegpt

import com.handsfreegpt.CommandMatcher.Companion.stripTrailingCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandsTest {

    private fun matcher() = CommandMatcher(
        mapOf(
            Command.SEND to listOf("send it", "send message"),
            Command.STOP to listOf("stop dictation"),
            Command.CANCEL to listOf("cancel that"),
        ),
        holdMs = 700,
    )

    @Test
    fun commandAtTheEnd_firesOnceNothingMoreIsSaid() {
        val m = matcher()
        assertNull(m.update("what's the weather tomorrow send it", final = false, nowMs = 0))
        assertNull(m.tick(500))
        assertEquals(Command.SEND, m.tick(700)?.command)
    }

    @Test
    fun commandInTheMiddleOfASentence_neverFires() {
        val m = matcher()
        assertNull(m.update("please send it", final = false, nowMs = 0))
        assertNull(m.update("please send it to my boss", final = false, nowMs = 300))
        assertNull(m.tick(5_000))
    }

    @Test
    fun finishedSegment_firesImmediately() {
        val m = matcher()
        assertEquals("send it", m.update("ok send it", final = true, nowMs = 0)?.phrase)
    }

    @Test
    fun caseAndPunctuationAreIgnored() {
        val m = matcher()
        assertEquals(Command.SEND, m.update("Hello there, Send It.", final = true, nowMs = 0)?.command)
    }

    @Test
    fun revisedPartialsDoNotRestartTheHold() {
        val m = matcher()
        m.update("helo there send it", final = false, nowMs = 0)
        m.update("hello there send it", final = false, nowMs = 600) // recognizer fixed an earlier word
        assertEquals(Command.SEND, m.tick(700)?.command)
    }

    @Test
    fun theLastCommandSaidWins() {
        val m = matcher()
        assertEquals(Command.CANCEL, m.update("send it no wait cancel that", final = true, nowMs = 0)?.command)
    }

    @Test
    fun firesOnlyOnceUntilReset() {
        val m = matcher()
        m.update("send it", final = true, nowMs = 0)
        assertNull(m.update("send message", final = true, nowMs = 100))
        m.reset()
        assertEquals(Command.SEND, m.update("send message", final = true, nowMs = 200)?.command)
    }

    @Test
    fun stripTrailingCommand_removesTheCommandAndItsPunctuation() {
        assertEquals("Hello there", stripTrailingCommand("Hello there, send it.", "send it"))
        assertEquals("What's the forecast?", stripTrailingCommand("What's the forecast? Send it.", "send it"))
        assertEquals("hello", stripTrailingCommand("hello SEND   it!", "send it"))
        assertEquals("", stripTrailingCommand("Send it.", "send it"))
        assertEquals("Résumé", stripTrailingCommand("Résumé, envoie-le.", "envoie le"))
    }

    @Test
    fun stripTrailingCommand_leavesOtherTextAlone() {
        assertEquals("I'll send it to you.", stripTrailingCommand("I'll send it to you.", "send it"))
        assertEquals("resend it", stripTrailingCommand("resend it", "send it"))
    }
}
