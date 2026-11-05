package dev.herdr.companion

import org.json.JSONObject

/** Parse an argument line, not a shell command: no expansion or execution. */
object AgentLaunchArguments {
    const val CAPABILITY = "agent_launch_args_v1"
    private const val MAX_ARGS = 64
    private const val MAX_BYTES = 8192

    fun supported(config: JSONObject?): Boolean = config?.optJSONArray("capabilities")?.let { values ->
        (0 until values.length()).any { values.optString(it) == CAPABILITY }
    } == true

    fun defaultArgs(defaults: JSONObject?, profile: String): List<String> {
        val values = defaults?.optJSONArray(profile) ?: return emptyList()
        if (values.length() > MAX_ARGS) return emptyList()
        val args = (0 until values.length()).map { values.opt(it) }
        if (args.any { it !is String }) return emptyList()
        val strings = args.map { it as String }
        if (strings.any { it.any(Char::isISOControl) } || strings.sumOf { it.toByteArray(Charsets.UTF_8).size } > MAX_BYTES) return emptyList()
        return strings
    }

    fun defaultLabel(defaults: JSONObject?, profile: String): String {
        val args = defaultArgs(defaults, profile)
        if (args.isEmpty()) return ""
        val command = args.joinToString(" ") { value ->
            if (value.matches(Regex("[a-zA-Z0-9_./:=+-]+"))) value else "'" + value.replace("'", "'\\''") + "'"
        }
        return "电脑端默认附加（无需手填）：\n$command\n下面仅填写额外参数；同名配置以你填写的为准。"
    }

    fun parse(line: String): List<String> {
        require(line.toByteArray(Charsets.UTF_8).size <= MAX_BYTES * 2) { "启动参数输入过长" }
        require(line.none { it.isISOControl() && it != '\t' }) { "启动参数不能包含换行或控制字符" }
        val args = mutableListOf<String>()
        val word = StringBuilder()
        var quote: Char? = null
        var started = false
        var bytes = 0
        var i = 0
        fun finish() {
            if (!started) return
            val value = word.toString()
            require(value.none { it.isISOControl() }) { "单个启动参数不能包含控制字符" }
            bytes += value.toByteArray(Charsets.UTF_8).size
            require(bytes <= MAX_BYTES) { "启动参数最多 8192 字节（UTF-8）" }
            require(args.size < MAX_ARGS) { "启动参数最多 64 项" }
            args += value
            word.clear()
            started = false
        }
        while (i < line.length) {
            val c = line[i++]
            when {
                c == quote -> { quote = null; started = true }
                quote == '\'' -> { word.append(c); started = true }
                c == '\\' -> {
                    require(i < line.length) { "启动参数末尾的反斜杠需要转义字符" }
                    val next = line[i]
                    if (quote == '"' && next !in "\\\"\$`") word.append(c)
                    else { word.append(next); i++ }
                    started = true
                }
                quote == null && (c == '\'' || c == '"') -> { quote = c; started = true }
                quote == null && (c == ' ' || c == '\t') -> finish()
                else -> { word.append(c); started = true }
            }
        }
        require(quote == null) { "启动参数的引号未闭合" }
        finish()
        return args
    }
}
