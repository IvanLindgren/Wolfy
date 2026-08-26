package com.wolfy.data

import com.wolfy.ffi.WolfyCore
import kotlin.uuid.Uuid

/**
 * Собирает источник исследования тем же парсером, которым читает книга.
 * Две проходки намеренны: сначала точный SHA, затем отправка. Ни байты всего
 * файла, ни одна гигантская строка в памяти не появляются.
 */
class ResearchSourceBuilder(private val core: WolfyCore) {
    suspend fun upload(bookId: String, handle: Long, chapterCount: Int, api: WolfyApi): ResearchStartResult {
        if (chapterCount <= 0) return ResearchStartResult.Failed("В книге нет текста для исследования.")
        val source = fingerprint(handle, chapterCount)
        val started = api.startResearch(bookId, source.sha256, Uuid.random().toString())
        val status = (started as? ResearchStartResult.Ready)?.value ?: return started
        if (status.stage == "ready" || status.stage != "awaiting_source") return started

        var index = 0
        val chunk = StringBuilder(CHUNK_CHARS)
        suspend fun sendChunk(): Boolean {
            if (chunk.isEmpty()) return true
            val bytes = chunk.toString().encodeToByteArray()
            chunk.clear()
            val digest = IncrementalSha256().apply { update(bytes) }.hex()
            val current = index++
            // Сервер уже записал эти куски до обрыва. SHA источника совпал
            // на старте, значит можно безопасно продолжить со следующего.
            if (current < status.uploadedChunks) return true
            return api.uploadResearchChunk(bookId, status.analysisId, current, digest, bytes)
        }
        for (chapterIndex in 0 until chapterCount) {
            val chapter = core.readChapter(handle, chapterIndex)
            val text = chapter.plainText()
            val entry = sourceEntry(chapterIndex, chapter.title, text)
            var offset = 0
            while (offset < entry.length) {
                val space = CHUNK_CHARS - chunk.length
                if (space == 0 && !sendChunk()) return ResearchStartResult.Failed("Не удалось передать текст книги.")
                var end = minOf(entry.length, offset + (CHUNK_CHARS - chunk.length))
                // UTF-16 хранит некоторые символы двумя code units. Разрез
                // между ними дал бы другой SHA в первом и втором проходе.
                if (end < entry.length && end > offset && entry[end - 1].isHighSurrogate() && entry[end].isLowSurrogate()) end--
                if (end == offset) {
                    if (!sendChunk()) return ResearchStartResult.Failed("Не удалось передать текст книги.")
                    continue
                }
                chunk.append(entry, offset, end)
                offset = end
            }
        }
        if (!sendChunk()) return ResearchStartResult.Failed("Не удалось передать текст книги.")
        return api.completeResearch(
            bookId, status.analysisId,
            ResearchSourceComplete(index, source.sha256, source.chars, source.words, source.chapters),
        )
    }

    private fun fingerprint(handle: Long, chapterCount: Int): SourceFingerprint {
        val digest = IncrementalSha256()
        var chars = 0L
        var words = 0L
        for (index in 0 until chapterCount) {
            val chapter = core.readChapter(handle, index)
            val entry = sourceEntry(index, chapter.title, chapter.plainText())
            digest.update(entry.encodeToByteArray())
            chars += entry.length
            words += entry.splitToSequence(Regex("\\s+")).count { it.isNotBlank() }
        }
        return SourceFingerprint(digest.hex(), chars, words, chapterCount)
    }

    private fun sourceEntry(index: Int, title: String?, text: String): String =
        "\n\n[chapter:${index + 1}; title:${title.orEmpty().trim()}]\n$text\n"

    private data class SourceFingerprint(val sha256: String, val chars: Long, val words: Long, val chapters: Int)

    private companion object { const val CHUNK_CHARS = 12_000 }
}

/** Platform SHA-256 keeps the common source builder free of JVM APIs. */
expect class IncrementalSha256() {
    fun update(bytes: ByteArray)
    fun hex(): String
}
