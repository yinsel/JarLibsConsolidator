package org.le1a.jarlibsconsolidator

// Frozen 1.5.1 baseline from ed5ae3d8c4252c8e0297ee082db54857a418ed11; benchmark-only.

/** Single-segment edge wildcards intentionally follow the user's custom prefix/suffix convention. */
internal class PreScanOptimizationFilter(whitelist: String = "", blacklist: String = "") {
    private val allowed = rules(whitelist)
    private val denied = rules(blacklist)

    fun accepts(internalName: String): Boolean {
        val packageName = internalName.substringBeforeLast('/', "").replace('/', '.')
        val className = internalName.substringAfterLast('/')
        return (allowed.isEmpty() || allowed.any { it(packageName, className) }) &&
                denied.none { it(packageName, className) }
    }

    private fun rules(text: String): List<(String, String) -> Boolean> =
        text.split(Regex("[,，\r\n]+")).map(String::trim).filter(String::isNotEmpty).map { rule ->
            when {
                rule == "*" -> { _, _ -> true }
                '.' in rule -> {
                    val pattern = glob(rule)
                    val parent = if (rule.endsWith(".*")) rule.dropLast(2) else null
                    val predicate: (String, String) -> Boolean = { pkg, _ -> pattern.matches(pkg) || pkg == parent }
                    predicate
                }
                '*' !in rule && '?' !in rule -> { pkg, cls ->
                    pkg.split('.').any { it.contains(rule) } || cls.contains(rule)
                }
                else -> {
                    val matcher: (String) -> Boolean = when {
                        rule.startsWith('*') && rule.endsWith('*') && rule.count { it == '*' } == 2 && '?' !in rule ->
                            { segment -> segment.contains(rule.substring(1, rule.length - 1)) }
                        rule.startsWith('*') && rule.count { it == '*' } == 1 && '?' !in rule ->
                            { segment -> segment.startsWith(rule.drop(1)) }
                        rule.endsWith('*') && rule.count { it == '*' } == 1 && '?' !in rule ->
                            { segment -> segment.endsWith(rule.dropLast(1)) }
                        else -> { val pattern = glob(rule); { segment -> pattern.matches(segment) } }
                    }
                    { pkg: String, _: String -> pkg.isNotEmpty() && pkg.split('.').any(matcher) }
                }
            }
        }

    private fun glob(rule: String): Regex = Regex(rule.map {
        when (it) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(it.toString()) }
    }.joinToString(""))
}
