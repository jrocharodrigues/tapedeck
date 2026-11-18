package io.github.tapedeck.core.model

import io.github.tapedeck.core.matcher.RequestMatcher
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

    fun find(incoming: RecordedRequest, matcher: RequestMatcher): RecordedExchange? {
        return _entries.firstOrNull { recorded ->
            matcher.matches(recorded.request, incoming)
        }
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
