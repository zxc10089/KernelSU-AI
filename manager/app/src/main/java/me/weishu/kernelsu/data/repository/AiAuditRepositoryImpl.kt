package me.weishu.kernelsu.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.agent.AiAuditEntry
import me.weishu.kernelsu.ksuApp
import java.io.File

/**
 * JSON-lines file under the app private directory (filesDir/ai_audit.jsonl).
 *
 * One entry per line keeps appends cheap and survives a truncated tail: a half written last line
 * simply fails to parse and is skipped on read. A Mutex serialises append/trim/read because the
 * assistant can record actions from several screens.
 */
class AiAuditRepositoryImpl : AiAuditRepository {

    private val file: File by lazy { File(ksuApp.filesDir, AiAuditRepository.FILE_NAME) }
    private val mutex = Mutex()

    override suspend fun append(entry: AiAuditEntry) = withContext(Dispatchers.IO) {
        mutex.withLock {
            file.parentFile?.mkdirs()
            file.appendText(entry.toJson() + "\n")
            trimIfNeeded()
        }
    }

    override suspend fun read(limit: Int): List<AiAuditEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.exists()) return@withLock emptyList()
            file.readLines()
                .asSequence()
                .mapNotNull { AiAuditEntry.fromJson(it) }
                .toList()
                .takeLast(limit)
                .reversed()
        }
    }

    /** Keeps the newest MAX_ENTRIES lines. */
    private fun trimIfNeeded() {
        val lines = file.readLines()
        if (lines.size <= AiAuditRepository.MAX_ENTRIES) return
        file.writeText(
            lines.takeLast(AiAuditRepository.MAX_ENTRIES).joinToString("\n", postfix = "\n")
        )
    }
}
