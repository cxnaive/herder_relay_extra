package dev.herdr.companion

/** A local display label, never a relay address, routing identity, or credential. */
internal object ComputerName {
    const val MAX_LENGTH = 48

    fun validate(value: String): String {
        val name = value.trim()
        require(name.isNotEmpty()) { "请输入电脑名称" }
        require(name.length <= MAX_LENGTH) { "电脑名称最多 $MAX_LENGTH 个字符" }
        require(name.none { it.isISOControl() || it == '\u2028' || it == '\u2029' }) { "电脑名称不能包含换行或控制字符" }
        return name
    }
}
