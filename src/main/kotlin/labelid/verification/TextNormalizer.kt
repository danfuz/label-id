package labelid.verification

object TextNormalizer {
    fun normalizeLoose(value: String): String =
        value.lowercase()
            .replace('\u2018', '\'')
            .replace('\u2019', '\'')
            .replace('\u201C', '"')
            .replace('\u201D', '"')
            .replace('\u00A0', ' ')
            .replace(Regex("&"), " and ")
            .replace(Regex("[^a-z0-9%./']+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun compact(value: String): String =
        normalizeLoose(value)
            .replace(Regex("[^a-z0-9]+"), "")

    fun containsLoose(haystack: String, needle: String): Boolean {
        val compactHaystack = compact(haystack)
        val compactNeedle = compact(needle)
        return compactNeedle.isNotBlank() && compactHaystack.contains(compactNeedle)
    }

    fun tokens(value: String): List<String> =
        normalizeLoose(value)
            .split(' ')
            .filter { it.isNotBlank() }

    fun bestCompactWindowSimilarity(expected: String, actual: String): Double {
        val expectedCompact = compact(expected)
        val actualCompact = compact(actual)
        if (expectedCompact.isBlank() || actualCompact.isBlank()) return 0.0
        if (actualCompact.contains(expectedCompact)) return 1.0
        if (actualCompact.length < expectedCompact.length) {
            return similarity(expectedCompact, actualCompact)
        }

        val windowSize = expectedCompact.length
        var best = 0.0
        for (start in 0..(actualCompact.length - windowSize)) {
            val window = actualCompact.substring(start, start + windowSize)
            best = maxOf(best, similarity(expectedCompact, window))
            if (best >= 0.98) return best
        }
        return best
    }

    fun similarity(left: String, right: String): Double {
        if (left.isEmpty() && right.isEmpty()) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val distance = levenshtein(left, right)
        return 1.0 - distance.toDouble() / maxOf(left.length, right.length)
    }

    private fun levenshtein(left: String, right: String): Int {
        val previous = IntArray(right.length + 1) { it }
        val current = IntArray(right.length + 1)

        for (i in left.indices) {
            current[0] = i + 1
            for (j in right.indices) {
                val insertCost = current[j] + 1
                val deleteCost = previous[j + 1] + 1
                val replaceCost = previous[j] + if (left[i] == right[j]) 0 else 1
                current[j + 1] = minOf(insertCost, deleteCost, replaceCost)
            }
            for (j in previous.indices) {
                previous[j] = current[j]
            }
        }
        return previous[right.length]
    }
}
