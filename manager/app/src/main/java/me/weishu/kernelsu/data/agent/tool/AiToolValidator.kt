package me.weishu.kernelsu.data.agent.tool

import me.weishu.kernelsu.data.agent.AiAccessScope
import me.weishu.kernelsu.data.agent.AiPathGuard
import org.json.JSONArray

sealed interface AiToolValidation {
    data class Ok(val args: Map<String, Any?>) : AiToolValidation
    data class Invalid(val reason: String) : AiToolValidation
}

/**
 * Strict parameter validation for the structured tools: unknown parameters are refused instead of
 * ignored, every declared rule is enforced, and a path parameter is additionally checked against the
 * user's granted scope before the handler ever sees it.
 */
object AiToolValidator {

    private class Invalid(message: String) : Exception(message)

    fun validate(
        spec: AiToolSpec,
        raw: Map<String, Any?>,
        scope: AiAccessScope,
        allowShell: Boolean,
    ): AiToolValidation {
        for (key in raw.keys) {
            if (spec.param(key) == null) return AiToolValidation.Invalid("未知参数：" + key)
        }
        if (spec.needsShell && !allowShell) {
            return AiToolValidation.Invalid("当前会话未开启 shell 权限，工具 " + spec.name + " 不可用")
        }
        val out = LinkedHashMap<String, Any?>()
        try {
            for (param in spec.params) {
                val value = raw[param.name]
                if (value == null) {
                    if (param.required) throw Invalid("缺少必填参数：" + param.name)
                    continue
                }
                out[param.name] = coerce(param, value, scope)
            }
        } catch (e: Invalid) {
            return AiToolValidation.Invalid(e.message ?: "参数非法")
        }
        return AiToolValidation.Ok(out)
    }

    private fun coerce(param: AiToolParam, value: Any?, scope: AiAccessScope): Any = when (param.type) {
        AiParamType.STRING -> string(param, value)
        AiParamType.INT -> int(param, value)
        AiParamType.BOOL -> bool(param, value)
        AiParamType.ENUM -> enum(param, value)
        AiParamType.STRING_LIST -> stringList(param, value)
        AiParamType.PATH -> path(param, value, scope)
        AiParamType.PATH_LIST -> pathList(param, value, scope)
    }

    private fun reject(param: AiToolParam, why: String): Nothing =
        throw Invalid("参数 " + param.name + " " + why)

    private fun string(param: AiToolParam, value: Any?): String {
        val s = when (value) {
            is String -> value
            is Number, is Boolean -> value.toString()
            else -> reject(param, "必须是字符串")
        }
        if (param.maxLength > 0 && s.length > param.maxLength) reject(param, "超过长度上限 " + param.maxLength)
        param.pattern?.let { if (!Regex(it).matches(s)) reject(param, "格式不合法") }
        return s
    }

    private fun int(param: AiToolParam, value: Any?): Int {
        val n = when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull() ?: reject(param, "必须是整数")
            else -> reject(param, "必须是整数")
        }
        param.min?.let { if (n < it) reject(param, "小于下限 " + it) }
        param.max?.let { if (n > it) reject(param, "大于上限 " + it) }
        return n
    }

    private fun bool(param: AiToolParam, value: Any?): Boolean = when (value) {
        is Boolean -> value
        is String -> when (value.trim().lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> reject(param, "必须是 true 或 false")
        }
        else -> reject(param, "必须是 true 或 false")
    }

    private fun enum(param: AiToolParam, value: Any?): String {
        if (param.values.isEmpty()) reject(param, "没有可取值")
        val raw = when (value) {
            is String -> value
            is Number, is Boolean -> value.toString()
            else -> reject(param, "必须是字符串")
        }
        val exact = param.values.firstOrNull { it == raw }
        if (exact != null) return exact
        val lower = raw.trim().lowercase()
        return param.values.firstOrNull { it.lowercase() == lower }
            ?: reject(param, "必须是 " + param.values.joinToString("/"))
    }

    private fun stringList(param: AiToolParam, value: Any?): List<String> {
        val items: List<Any?> = when (value) {
            is JSONArray -> (0 until value.length()).map { value.opt(it) }
            is List<*> -> value
            is Array<*> -> value.toList()
            else -> reject(param, "必须是字符串数组")
        }
        if (param.maxItems > 0 && items.size > param.maxItems) reject(param, "元素超过 " + param.maxItems + " 个")
        return items.map { item ->
            when (item) {
                null -> reject(param, "数组元素不能为空")
                is String -> item
                is Number, is Boolean -> item.toString()
                else -> reject(param, "数组元素必须是字符串")
            }
        }
    }

    private fun path(param: AiToolParam, value: Any?, scope: AiAccessScope): String {
        val raw = when (value) {
            is String -> value
            else -> reject(param, "必须是路径字符串")
        }
        return checkPath(param, raw, scope)
    }

    private fun pathList(param: AiToolParam, value: Any?, scope: AiAccessScope): List<String> {
        val items: List<Any?> = when (value) {
            is JSONArray -> (0 until value.length()).map { value.opt(it) }
            is List<*> -> value
            is Array<*> -> value.toList()
            else -> reject(param, "必须是路径数组")
        }
        if (param.maxItems > 0 && items.size > param.maxItems) reject(param, "元素超过 " + param.maxItems + " 个")
        return items.map { item ->
            val raw = when (item) {
                null -> reject(param, "数组元素不能为空")
                is String -> item
                else -> reject(param, "数组元素必须是路径字符串")
            }
            checkPath(param, raw, scope)
        }
    }

    private fun checkPath(param: AiToolParam, raw: String, scope: AiAccessScope): String {
        val p = raw.trim()
        if (AiToolPatterns.pathRejected(p)) reject(param, "必须是规范的绝对路径（不允许 .. 段与控制字符）")
        val required = AiPathGuard.requiredScope(p)
        val allowed = when (required) {
            AiAccessScope.NONE -> true
            AiAccessScope.DATA_ADB -> scope.coversDataAdb
            AiAccessScope.ROOT_FS -> scope.coversRoot
        }
        if (!allowed) reject(param, "超出当前授权范围：" + AiPathGuard.normalize(p))
        if (param.pathScope == AiAccessScope.DATA_ADB && required != AiAccessScope.DATA_ADB) {
            reject(param, "必须位于 " + AiPathGuard.DATA_ADB_ROOT + " 之内")
        }
        return AiPathGuard.normalize(p)
    }
}
