package io.github.tapedeck.core.model

import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

object HarCodec {
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    fun encode(root: HarRoot): String = json.encodeToString(HarRoot.serializer(), root)

    fun encode(entries: List<HarEntry>, creatorVersion: String = "0.1.0"): String {
        val root = HarRoot(
            log = HarLog(
                version = "1.2",
                creator = HarCreator(version = creatorVersion),
                entries = entries
            )
        )
        return encode(root)
    }

    fun decode(jsonString: String): HarRoot = json.decodeFromString(HarRoot.serializer(), jsonString)

    fun write(file: File, entries: List<HarEntry>) {
        file.parentFile?.mkdirs()
        file.writeText(encode(entries))
    }

    fun read(file: File): List<HarEntry> {
        if (!file.exists()) return emptyList()
        return decode(file.readText()).log.entries
    }
}
