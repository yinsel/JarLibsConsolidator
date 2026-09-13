package org.le1a.jarlibsconsolidator

/** Package results are reused within one export; class-name keywords are still checked for each class. */
internal class ExportFilter(whitelist: String = "", blacklist: String = "") {
    private data class PackageName(val name: String, val segments: List<String>)
    private data class Rule(val packageMatch: (PackageName) -> Boolean, val classKeyword: String? = null)
    private val allowed = rules(whitelist)
    private val denied = rules(blacklist)
    private val allowedKeywords = allowed.mapNotNull { it.classKeyword }
    private val deniedKeywords = denied.mapNotNull { it.classKeyword }
    private val packageDecisions = object : LinkedHashMap<String, Pair<Boolean, Boolean>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Boolean, Boolean>>?): Boolean = size > 2048
    }

    fun accepts(internalName: String): Boolean {
        if (allowed.isEmpty() && denied.isEmpty()) return true
        val rawPackage = internalName.substringBeforeLast('/', "")
        val decision = packageDecisions.getOrPut(rawPackage) {
            val pkg = PackageName(rawPackage.replace('/', '.'), rawPackage.split('/'))
            (allowed.isEmpty() || allowed.any { it.packageMatch(pkg) }) to denied.any { it.packageMatch(pkg) }
        }
        if (decision.second) return false
        val className = internalName.substringAfterLast('/')
        return (decision.first || allowedKeywords.any { className.contains(it) }) &&
                deniedKeywords.none { className.contains(it) }
    }

    private fun rules(text: String): List<Rule> =
        text.split(Regex("[,，\r\n]+")).map(String::trim).filter(String::isNotEmpty).map { rule ->
            when {
                rule == "*" -> Rule({ true })
                '.' in rule -> {
                    val pattern = glob(rule)
                    val parent = if (rule.endsWith(".*")) rule.dropLast(2) else null
                    Rule({ pattern.matches(it.name) || it.name == parent })
                }
                '*' !in rule && '?' !in rule -> Rule({ pkg -> pkg.segments.any { it.contains(rule) } }, rule)
                else -> {
                    val matcher: (String) -> Boolean = when {
                        rule.startsWith('*') && rule.endsWith('*') && rule.count { it == '*' } == 2 && '?' !in rule -> {
                            val value = rule.substring(1, rule.length - 1);
                            { segment -> segment.contains(value) }
                        }
                        rule.startsWith('*') && rule.count { it == '*' } == 1 && '?' !in rule -> {
                            val value = rule.drop(1);
                            { segment -> segment.startsWith(value) }
                        }
                        rule.endsWith('*') && rule.count { it == '*' } == 1 && '?' !in rule -> {
                            val value = rule.dropLast(1);
                            { segment -> segment.endsWith(value) }
                        }
                        else -> { val pattern = glob(rule); { segment -> pattern.matches(segment) } }
                    }
                    Rule({ pkg -> pkg.name.isNotEmpty() && pkg.segments.any(matcher) })
                }
            }
        }

    private fun glob(rule: String): Regex = Regex(rule.map {
        when (it) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(it.toString()) }
    }.joinToString(""))
}
