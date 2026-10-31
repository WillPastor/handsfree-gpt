package com.handsfreegpt

/** What a spoken command asks for. */
enum class Command { SEND, STOP, CANCEL }

/**
 * Spots a spoken command at the end of what you said, e.g. "…what's the forecast, send it".
 * Pure logic, fed with the speech recognizer's running transcript, so it can be unit-tested.
 *
 * A command only counts when it is the last thing said: a partial result arms it and it fires once
 * no further words have arrived for [holdMs]; a finished segment fires straight away. So
 * "send it to my boss" never fires. It fires at most once until [reset].
 */
class CommandMatcher(phrases: Map<Command, List<String>>, private val holdMs: Long = 700) {

    class Match(val command: Command, val phrase: String)

    // Longest first, so a more specific phrase wins when one is a suffix of another.
    private val phrases: List<Pair<Command, String>> = phrases
        .flatMap { (command, list) -> list.map { command to normalize(it) } }
        .filter { it.second.isNotEmpty() }
        .sortedByDescending { it.second.length }

    private var pending: Match? = null
    private var pendingSince = 0L
    private var fired = false

    /** Feeds the latest transcript of the current utterance; returns a command to act on now, if any. */
    fun update(text: String, final: Boolean, nowMs: Long): Match? {
        if (fired) return null
        val match = match(text)
        if (match == null) {
            pending = null // more words followed: it wasn't the end
            return null
        }
        if (final) return fire(match)
        // Recognizers resend and revise partial results; only new words after the phrase reset it.
        if (pending?.phrase != match.phrase) {
            pending = match
            pendingSince = nowMs
        }
        return null
    }

    /** Call regularly; fires an armed command once nothing new has been said for [holdMs]. */
    fun tick(nowMs: Long): Match? {
        val armed = pending ?: return null
        if (fired || nowMs - pendingSince < holdMs) return null
        return fire(armed)
    }

    fun reset() {
        pending = null
        fired = false
    }

    private fun fire(match: Match): Match {
        fired = true
        pending = null
        return match
    }

    private fun match(text: String): Match? {
        val t = normalize(text)
        for ((command, phrase) in phrases) {
            if (t == phrase || t.endsWith(" $phrase")) return Match(command, phrase)
        }
        return null
    }

    companion object {
        private val NON_WORD = Regex("[^\\p{L}\\p{N}']+")

        /** Lowercase words separated by single spaces; punctuation ignored, apostrophes kept. */
        fun normalize(s: String): String =
            s.lowercase().replace('’', '\'').replace(NON_WORD, " ").trim()

        /**
         * Removes a trailing spoken command from ChatGPT's transcript, whatever its case or punctuation:
         * "What's the forecast? Send it." → "What's the forecast?". Unchanged if it doesn't end with it.
         */
        fun stripTrailingCommand(text: String, phrase: String): String {
            val words = normalize(phrase).split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) return text
            val body = words.joinToString("[^\\p{L}\\p{N}']+") { Regex.escape(it) }
            val trailing = Regex("(?iu)(^|[^\\p{L}\\p{N}'])$body[^\\p{L}\\p{N}']*$")
            val m = trailing.find(text.replace('’', '\'')) ?: return text
            return text.substring(0, m.range.first + m.groupValues[1].length).trimEnd(' ', ',', ';', ':', '-', '–', '—')
        }
    }
}
