package com.zelbuilder.app.data

object LogCleaner {

    private const val MAX_LINES = 500

    private val timestamp = Regex("""^\d{4}-\d{2}-\d{2}T[\d:.]+Z\s?""")
    private val errorLine = Regex("""^(e: file:///|error:|What went wrong:)|\.java:\d+: error:""")
    private val editableExt = setOf(
        "kt", "java", "kts", "gradle", "xml", "properties", "toml", "json", "html", "js", "css"
    )

    private fun clean(line: String): String =
        timestamp.replace(line.trimStart('\uFEFF'), "").trim()

    /** Keeps only compiler / Gradle error lines (max 500). */
    fun extractOnlyErrors(rawLogs: String): String {
        val out = ArrayList<String>()
        val lines = rawLogs.lines()
        var i = 0
        while (i < lines.size && out.size < MAX_LINES) {
            val line = clean(lines[i])
            if (errorLine.containsMatchIn(line)) {
                out.add(line)
                if (line.startsWith("What went wrong:")) {
                    for (k in 1..2) {
                        if (i + k < lines.size) {
                            val next = clean(lines[i + k])
                            if (next.isNotEmpty()) out.add(next)
                        }
                    }
                    i += 2
                }
            }
            i++
        }
        return out.take(MAX_LINES).joinToString("\n")
    }

    /** Finds the project file that the first error points at. */
    fun guessFaultyFile(errorLog: String, filePaths: Collection<String>): String? {
        val editable = filePaths.filter { p ->
            !p.startsWith(".github/") && p.substringAfterLast('.', "") in editableExt
        }
        var best: String? = null
        var bestIndex = Int.MAX_VALUE
        for (p in editable) {
            var idx = errorLog.indexOf("/$p")
            if (idx < 0) idx = errorLog.indexOf(p)
            if (idx in 0 until bestIndex) {
                best = p
                bestIndex = idx
            }
        }
        return best
            ?: editable.firstOrNull { it == "app/build.gradle.kts" }
            ?: editable.firstOrNull { it == "app/build.gradle" }
            ?: editable.firstOrNull { it.endsWith("build.gradle.kts") || it.endsWith("build.gradle") }
    }
}
