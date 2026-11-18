package com.impecabel.tapedeck.core.model

import com.impecabel.tapedeck.core.matcher.RequestMatcher
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class Recording(
    entries: List<RecordedExchange> = emptyList()
) {
    private val _entries = CopyOnWriteArrayList<RecordedExchange>(entries)
    val entries: List<RecordedExchange> get() = _entries

    fun add(exchange: RecordedExchange) {
        _entries.add(exchange)
    }

    /**
     * The entry that best matches [incoming] across the whole recording (highest [RequestMatcher.score]), and the
     * earliest recorded one among equally good matches; null when none matches.
     */
    fun find(incoming: RecordedRequest, matcher: RequestMatcher): RecordedExchange? {
        var best: RecordedExchange? = null
        var bestScore = 0
        for (recorded in _entries) {
            val score = matcher.score(recorded.request, incoming)
            if (score > bestScore) {
                best = recorded
                bestScore = score
            }
        }
        return best
    }

    fun size(): Int = _entries.size

    fun isEmpty(): Boolean = _entries.isEmpty()

    fun clear() {
        _entries.clear()
    }

    fun saveToHar(file: File) {
        val harEntries = _entries.map { it.toHarEntry() }
        HarCodec.write(file, harEntries)
    }

    companion object {
        fun loadFromHar(file: File): Recording {
            val harEntries = HarCodec.read(file)
            return Recording(harEntries.map { it.toRecordedExchange() })
        }
    }
}
