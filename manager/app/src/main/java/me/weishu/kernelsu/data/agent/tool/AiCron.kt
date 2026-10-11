package me.weishu.kernelsu.data.agent.tool

import java.util.Calendar

/**
 * A parsed five field cron expression (`minute hour day-of-month month day-of-week`).
 *
 * The client parses it itself instead of handing the string to `crontab`, so an expression the user
 * approves on screen is exactly the schedule that runs, and an expression this parser does not
 * understand is refused rather than passed on half checked.
 */
data class AiCronSchedule(
    val minutes: Set<Int>,
    val hours: Set<Int>,
    val daysOfMonth: Set<Int>,
    val months: Set<Int>,
    val daysOfWeek: Set<Int>,
    val domRestricted: Boolean,
    val dowRestricted: Boolean,
) {
    /** True when the expression fires in the minute that contains [epochMillis], in local time. */
    fun matches(epochMillis: Long): Boolean {
        val c = Calendar.getInstance()
        c.timeInMillis = epochMillis
        if (c.get(Calendar.MINUTE) !in minutes) return false
        if (c.get(Calendar.HOUR_OF_DAY) !in hours) return false
        if (c.get(Calendar.MONTH) + 1 !in months) return false
        val domHit = c.get(Calendar.DAY_OF_MONTH) in daysOfMonth
        // Calendar: SUNDAY is 1, so this yields the cron numbering where Sunday is 0.
        val dowHit = (c.get(Calendar.DAY_OF_WEEK) - 1) in daysOfWeek
        return when {
            domRestricted && dowRestricted -> domHit || dowHit
            domRestricted -> domHit
            dowRestricted -> dowHit
            else -> true
        }
    }
}

object AiCron {

    const val MIN_INTERVAL_SECONDS = 60

    /** null when the expression is a valid five field cron, otherwise the reason it is not. */
    fun validate(expression: String): String? {
        val fields = expression.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (fields.size != 5) return "cron 表达式必须是 5 段（分 时 日 月 周）"
        if (parse(expression) == null) return "cron 表达式含非法字段"
        return null
    }

    fun parse(expression: String): AiCronSchedule? {
        val fields = expression.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (fields.size != 5) return null
        val minutes = expand(fields[0], 0, 59) ?: return null
        val hours = expand(fields[1], 0, 23) ?: return null
        val daysOfMonth = expand(fields[2], 1, 31) ?: return null
        val months = expand(fields[3], 1, 12) ?: return null
        val rawDow = expand(fields[4], 0, 7) ?: return null
        // cron accepts both 0 and 7 for Sunday; normalise to 0 so matches() has one representation.
        val daysOfWeek = rawDow.map { if (it == 7) 0 else it }.toSet()
        return AiCronSchedule(
            minutes = minutes,
            hours = hours,
            daysOfMonth = daysOfMonth,
            months = months,
            daysOfWeek = daysOfWeek,
            domRestricted = fields[2].trim() != "*",
            dowRestricted = fields[4].trim() != "*",
        )
    }

    /**
     * One cron field: a bare value, a range, a slash step on either of them, or a comma list of
     * those. The two characters that end this comment on purpose cannot appear in the body, so the
     * step form is spelled out in prose instead. Anything unparsable yields null.
     */
    private fun expand(field: String, min: Int, max: Int): Set<Int>? {
        val out = LinkedHashSet<Int>()
        if (field.isBlank()) return null
        for (part in field.split(',')) {
            val body = part.trim()
            if (body.isEmpty()) return null
            val stepSplit = body.split('/')
            if (stepSplit.size > 2) return null
            val step = if (stepSplit.size == 2) stepSplit[1].toIntOrNull() ?: return null else 1
            if (step <= 0) return null
            val rangePart = stepSplit[0].trim()
            val lo: Int
            val hi: Int
            when {
                rangePart == "*" -> {
                    lo = min
                    hi = max
                }
                rangePart.contains('-') -> {
                    val bits = rangePart.split('-')
                    if (bits.size != 2) return null
                    lo = bits[0].trim().toIntOrNull() ?: return null
                    hi = bits[1].trim().toIntOrNull() ?: return null
                }
                else -> {
                    val v = rangePart.toIntOrNull() ?: return null
                    lo = v
                    hi = v
                }
            }
            if (lo < min || hi > max || lo > hi) return null
            var v = lo
            while (v <= hi) {
                out.add(v)
                v += step
            }
        }
        return if (out.isEmpty()) null else out
    }
}
