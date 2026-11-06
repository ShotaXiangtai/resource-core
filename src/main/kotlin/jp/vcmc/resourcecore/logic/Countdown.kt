package jp.vcmc.resourcecore.logic

/** カウントダウンの告知 1 回分。message が null のときは messages.yml の既定文を使う。 */
data class CountdownStep(val seconds: Int, val message: String? = null)

/**
 * 実時間ベースのカウントダウン。ティックが遅れても実行時刻がずれないよう、
 * ティック数ではなく開始時刻からの経過時間で判定する。
 */
class CountdownTracker(steps: List<CountdownStep>, private val startedAtMillis: Long) {
    private val ordered = steps.sortedByDescending { it.seconds }
    private var nextIndex = 0

    val durationSeconds: Int = ordered.firstOrNull()?.seconds ?: 0

    data class Poll(val announce: CountdownStep?, val remainingSeconds: Int, val finished: Boolean)

    fun poll(nowMillis: Long): Poll {
        val remainingMillis = remainingMillis(nowMillis)
        val remaining = toSeconds(remainingMillis)

        // サーバーが止まって複数の告知時刻を過ぎた場合は、最後の 1 件だけ告知する。
        var due: CountdownStep? = null
        while (nextIndex < ordered.size && remaining <= ordered[nextIndex].seconds) {
            due = ordered[nextIndex]
            nextIndex++
        }
        return Poll(due, remaining, remainingMillis <= 0)
    }

    fun remainingSeconds(nowMillis: Long): Int = toSeconds(remainingMillis(nowMillis))

    private fun remainingMillis(nowMillis: Long): Long =
        durationSeconds * 1000L - (nowMillis - startedAtMillis).coerceAtLeast(0)

    private fun toSeconds(millis: Long): Int = if (millis <= 0) 0 else ((millis + 999) / 1000).toInt()
}

/** 秒数を「1分30秒」のような表記にする。単位の文字列は messages.yml で変更できる。 */
fun formatDuration(totalSeconds: Int, hourUnit: String, minuteUnit: String, secondUnit: String, separator: String = ""): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    val parts = buildList {
        if (h > 0) add("$h$hourUnit")
        if (m > 0) add("$m$minuteUnit")
        if (s > 0 || isEmpty()) add("$s$secondUnit")
    }
    return parts.joinToString(separator)
}
