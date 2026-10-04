package me.weishu.kernelsu.data.agent

/**
 * Fail closed gate for everything the assistant proposes to do with the file system or with root.
 *
 * Rules (see design/ai-console-spec.md sections 5 and 6):
 *  - the access scope is checked first: a path outside the granted scope is denied outright, it is
 *    never downgraded to a mere confirmation;
 *  - commands the client cannot reason about (shell metacharacters, globs, relative paths) are never
 *    silent;
 *  - anything unknown is STRICT, never silent.
 *
 * Granting or revoking root for an app does not touch the file system at all, so it stays available
 * even when the file scope is NONE; that is deliberate, see the user requirement behind it.
 */
object AiPathGuard {

    const val DATA_ADB_ROOT = "/data/adb"
    const val MAX_COMMAND_LENGTH = 4096
    const val MAX_SEARCH_PATTERN_LENGTH = 200
    const val BATCH_STRICT_THRESHOLD = 3

    /** Commands that neither read the file tree nor change state; usable even with file access off. */
    private val DEVICE_INFO_COMMANDS = setOf("id", "uname", "uptime", "getprop", "getenforce", "whoami")

    /** Read only file commands; silent while every path they mention is inside the granted scope. */
    private val READ_ONLY_FILE_COMMANDS = setOf(
        "ls", "cat", "head", "tail", "df", "du", "stat", "find", "grep", "wc", "readlink", "realpath", "file",
    )

    /**
     * Flags that turn the otherwise read only `find` into a command that changes files or runs other
     * programs. Any of them sends the action to the red gate instead of the silent read only path.
     */
    private val FIND_WRITE_FLAGS = listOf(
        "-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprint0", "-fprintf", "-fls",
    )

    /** Always the red gate, whatever the scope happens to be. */
    private val STRICT_COMMANDS = setOf(
        "rm", "rmdir", "dd", "mkfs", "flash", "reboot", "shutdown", "setenforce", "chmod", "chown",
        "insmod", "rmmod", "mount", "umount", "cp", "mv", "ln", "tee", "sh", "su", "toybox", "busybox",
    )

    /**
     * The read only whitelist behind safe_exec_shell: token prefixes matched from the start of the
     * command. These inspect device, package and log state and never change anything; everything else
     * is refused, so the model cannot turn this into a general root shell.
     */
    private val SAFE_EXEC_PREFIXES: List<List<String>> = listOf(
        listOf("pm", "list"),
        listOf("pm", "path"),
        listOf("pm", "dump"),
        listOf("cmd", "package", "list"),
        listOf("cmd", "package", "query-activities"),
        listOf("cmd", "package", "query-services"),
        listOf("cmd", "package", "query-receivers"),
        listOf("cmd", "package", "query-intent"),
        listOf("cmd", "package", "path"),
        listOf("cmd", "package", "resolve-activity"),
        listOf("cmd", "package", "resolve-service"),
        listOf("cmd", "package", "dump"),
        listOf("dumpsys", "-l"),
        listOf("dumpsys", "package"),
        listOf("dumpsys", "activity"),
        listOf("dumpsys", "meminfo"),
        listOf("dumpsys", "battery"),
        listOf("dumpsys", "netstats"),
        listOf("dumpsys", "wifi"),
        listOf("dumpsys", "connectivity"),
        listOf("dumpsys", "mount"),
        listOf("logcat", "-d"),
        listOf("logcat", "-t"),
        listOf("getprop"),
        listOf("ps"),
        listOf("id"),
        listOf("uname"),
        listOf("uptime"),
        listOf("getenforce"),
        listOf("df"),
        listOf("ip", "addr"),
        listOf("ip", "route"),
        listOf("netstat"),
        listOf("ksud", "module", "list"),
        listOf("ksud", "su", "list"),
    )

    /** Characters that make the client unable to predict what a command will touch. */
    private val METACHARS: List<Char> = listOf(
        '>', '<', '|', ';', '&', '*', '?', 96.toChar(), 10.toChar(), 13.toChar(), 92.toChar(),
    )

    /** Collapses duplicate slashes and resolves dot segments; never returns a relative path. */
    fun normalize(path: String): String {
        var p = path.trim().trim('"').trim(39.toChar())
        if (p.isEmpty()) return p
        p = p.replace(Regex("/+"), "/")
        val out = ArrayList<String>()
        for (segment in p.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(segment)
            }
        }
        return "/" + out.joinToString("/")
    }

    /** The weakest scope that already contains the path. */
    fun requiredScope(path: String): AiAccessScope {
        val n = normalize(path)
        return if (n == DATA_ADB_ROOT || n.startsWith(DATA_ADB_ROOT + "/")) {
            AiAccessScope.DATA_ADB
        } else {
            AiAccessScope.ROOT_FS
        }
    }

    fun isInside(path: String, scope: AiAccessScope): Boolean = when (requiredScope(path)) {
        AiAccessScope.NONE -> true
        AiAccessScope.DATA_ADB -> scope.coversDataAdb
        AiAccessScope.ROOT_FS -> scope.coversRoot
    }

    /** Every absolute path a command mentions, normalized. Bare relative names need no scope. */
    fun extractPaths(command: String): List<String> {
        val out = ArrayList<String>()
        for (token in command.split(' ', 10.toChar())) {
            val t = token.trim().trim('"').trim(39.toChar())
            if (t.isEmpty()) continue
            val value = if (t.contains('=')) t.substringAfter('=') else t
            when {
                value.startsWith("/") -> out.add(normalize(value))
                value.contains('/') -> out.add(normalize("/" + value))
                value == "." || value == ".." -> out.add("/")
            }
        }
        return out.distinct()
    }

    fun decideReadFile(path: String, scope: AiAccessScope): AiGuardDecision {
        if (path.isBlank()) return AiGuardDecision.Deny("empty path")
        if (!scope.canReadFiles) return AiGuardDecision.Deny("file access is disabled")
        if (!isInside(path, scope)) {
            return AiGuardDecision.Deny("path outside the granted scope: " + normalize(path))
        }
        return AiGuardDecision.Allow(AiSafetyTier.SILENT)
    }

    /** Listing is read only and only reveals what exists, so it never needs a confirmation. */
    fun decideListDir(path: String, scope: AiAccessScope): AiGuardDecision {
        if (path.isBlank()) return AiGuardDecision.Deny("empty path")
        if (!scope.canReadFiles) return AiGuardDecision.Deny("file access is disabled")
        if (!isInside(path, scope)) {
            return AiGuardDecision.Deny("path outside the granted scope: " + normalize(path))
        }
        return AiGuardDecision.Allow(AiSafetyTier.SILENT)
    }

    /**
     * Metadata, windows and literal search are all pure reads, so they share the read_file scope gate.
     * The pattern may not carry shell or quoting characters: it is passed to grep as a literal.
     */
    fun decideSearchInFile(path: String, pattern: String, scope: AiAccessScope): AiGuardDecision {
        val base = decideReadFile(path, scope)
        if (base is AiGuardDecision.Deny) return base
        val p = pattern.trim()
        if (p.isEmpty()) return AiGuardDecision.Deny("empty search pattern")
        if (p.length > MAX_SEARCH_PATTERN_LENGTH) return AiGuardDecision.Deny("search pattern too long")
        if (p.any { METACHARS.contains(it) || it == 39.toChar() || it == 34.toChar() }) {
            return AiGuardDecision.Deny("search pattern must be a plain literal without quotes or shell characters")
        }
        return AiGuardDecision.Allow(AiSafetyTier.SILENT)
    }

    fun decideWrite(path: String, scope: AiAccessScope, batchSize: Int = 1): AiGuardDecision {
        if (path.isBlank()) return AiGuardDecision.Deny("empty path")
        if (!scope.canReadFiles) return AiGuardDecision.Deny("file access is disabled")
        if (!isInside(path, scope)) {
            return AiGuardDecision.Deny("path outside the granted scope: " + normalize(path))
        }
        return AiGuardDecision.Allow(escalate(AiSafetyTier.LIGHT, batchSize))
    }

    fun decideDelete(path: String, scope: AiAccessScope, batchSize: Int = 1): AiGuardDecision {
        if (path.isBlank()) return AiGuardDecision.Deny("empty path")
        if (!scope.canReadFiles) return AiGuardDecision.Deny("file access is disabled")
        if (!isInside(path, scope)) {
            return AiGuardDecision.Deny("path outside the granted scope: " + normalize(path))
        }
        return AiGuardDecision.Allow(AiSafetyTier.STRICT)
    }

    fun decideRunCommand(
        command: String,
        scope: AiAccessScope,
        batchSize: Int = 1,
        allowShell: Boolean = true,
    ): AiGuardDecision {
        if (!allowShell) return AiGuardDecision.Deny("shell execution is disabled")
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return AiGuardDecision.Deny("empty command")
        if (trimmed.length > MAX_COMMAND_LENGTH) return AiGuardDecision.Deny("command too long")

        val tokens = trimmed.split(' ', 10.toChar()).filter { it.isNotBlank() }
        val head = tokens.firstOrNull()?.lowercase() ?: return AiGuardDecision.Deny("empty command")
        val hasMetachars = trimmed.any { METACHARS.contains(it) }
        val paths = extractPaths(trimmed)
        val findWrites = head == "find" && tokens.any { token ->
            val flag = token.lowercase()
            FIND_WRITE_FLAGS.any { it == flag || flag.startsWith(it + "=") }
        }

        for (path in paths) {
            if (!isInside(path, scope)) {
                return AiGuardDecision.Deny("command touches a path outside the granted scope: " + path)
            }
        }
        if (!scope.canReadFiles && paths.isNotEmpty()) {
            return AiGuardDecision.Deny("file access is disabled")
        }
        if (!scope.canReadFiles && head in READ_ONLY_FILE_COMMANDS) {
            return AiGuardDecision.Deny("file access is disabled")
        }
        if (head in READ_ONLY_FILE_COMMANDS && paths.isEmpty() && !scope.coversRoot) {
            return AiGuardDecision.Deny("command reads the file tree without a path inside the granted scope")
        }

        val tier = when {
            head in STRICT_COMMANDS -> AiSafetyTier.STRICT
            hasMetachars -> AiSafetyTier.STRICT
            findWrites -> AiSafetyTier.STRICT
            head == "pm" -> if (tokens.getOrNull(1)?.lowercase() == "list") AiSafetyTier.SILENT else AiSafetyTier.STRICT
            head == "ksud" -> ksudTier(tokens)
            head in READ_ONLY_FILE_COMMANDS -> AiSafetyTier.SILENT
            head in DEVICE_INFO_COMMANDS -> AiSafetyTier.SILENT
            else -> AiSafetyTier.STRICT
        }
        return AiGuardDecision.Allow(escalate(tier, batchSize))
    }

    /**
     * The whitelisted query channel: one simple command from [SAFE_EXEC_PREFIXES], no metacharacters,
     * no paths outside the scope, and always confirmed by the user before it runs.
     */
    fun decideSafeExec(
        command: String,
        scope: AiAccessScope,
        batchSize: Int = 1,
        allowShell: Boolean = true,
    ): AiGuardDecision {
        if (!allowShell) return AiGuardDecision.Deny("shell execution is disabled")
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return AiGuardDecision.Deny("empty command")
        if (trimmed.length > MAX_COMMAND_LENGTH) return AiGuardDecision.Deny("command too long")
        val quoted = trimmed.any { METACHARS.contains(it) || it == 39.toChar() || it == 34.toChar() }
        if (quoted) return AiGuardDecision.Deny("command uses shell metacharacters")
        val tokens = trimmed.split(' ', 10.toChar()).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return AiGuardDecision.Deny("empty command")
        if (!isWhitelisted(tokens)) {
            return AiGuardDecision.Deny("command is not in the read-only whitelist")
        }
        for (path in extractPaths(trimmed)) {
            if (!isInside(path, scope)) {
                return AiGuardDecision.Deny("command touches a path outside the granted scope: " + path)
            }
        }
        return AiGuardDecision.Allow(escalate(AiSafetyTier.LIGHT, batchSize))
    }

    /** Reading who holds root changes nothing, so it is always allowed and needs no confirmation. */
    fun decideRootAppList(): AiGuardDecision = AiGuardDecision.Allow(AiSafetyTier.SILENT)

    private fun isWhitelisted(tokens: List<String>): Boolean {
        for (prefix in SAFE_EXEC_PREFIXES) {
            if (tokens.size < prefix.size) continue
            var hit = true
            for (i in prefix.indices) {
                if (tokens[i].lowercase() != prefix[i]) {
                    hit = false
                    break
                }
            }
            if (hit) return true
        }
        return false
    }

    /** The single gate every proposed action goes through. */
    fun decide(
        action: AiAction,
        scope: AiAccessScope,
        batchSize: Int = 1,
        allowShell: Boolean = true,
    ): AiGuardDecision = when (action) {
        is AiAction.ReadFile -> decideReadFile(action.path, scope)
        is AiAction.GetFileMetadata -> decideReadFile(action.path, scope)
        is AiAction.ReadFileChunk -> decideReadFile(action.path, scope)
        is AiAction.SearchInFile -> decideSearchInFile(action.path, action.pattern, scope)
        is AiAction.ListDir -> decideListDir(action.path, scope)
        is AiAction.RunCommand -> decideRunCommand(action.command, scope, batchSize, allowShell)
        is AiAction.SafeExec -> decideSafeExec(action.command, scope, batchSize, allowShell)
        is AiAction.RootAppList -> decideRootAppList()
        is AiAction.MoveToTrash -> decideDelete(action.path, scope, batchSize)
        is AiAction.GrantSu, is AiAction.RevokeSu -> AiGuardDecision.Allow(escalate(AiSafetyTier.LIGHT, batchSize))
        is AiAction.DisableModule, is AiAction.EnableModule -> AiGuardDecision.Allow(escalate(AiSafetyTier.LIGHT, batchSize))
        is AiAction.InstallModule -> AiGuardDecision.Allow(escalate(AiSafetyTier.LIGHT, batchSize))
        // The model authors this payload itself, including a customize.sh that ksud runs as root:
        // no scope or batch exemption, every single one gets the red dialog.
        is AiAction.MakeModule -> AiGuardDecision.Allow(AiSafetyTier.STRICT)
        is AiAction.Unknown -> AiGuardDecision.Allow(AiSafetyTier.STRICT)
    }

    private fun escalate(tier: AiSafetyTier, batchSize: Int): AiSafetyTier =
        if (batchSize >= BATCH_STRICT_THRESHOLD) AiSafetyTier.STRICT else tier

    private fun ksudTier(tokens: List<String>): AiSafetyTier {
        val sub = tokens.getOrNull(1)?.lowercase() ?: return AiSafetyTier.STRICT
        if (sub != "module" && sub != "su") return AiSafetyTier.STRICT
        val verb = tokens.getOrNull(2)?.lowercase() ?: return AiSafetyTier.STRICT
        return when (verb) {
            "list" -> AiSafetyTier.SILENT
            "enable", "disable", "install", "uninstall", "action" -> AiSafetyTier.LIGHT
            else -> AiSafetyTier.STRICT
        }
    }
}
