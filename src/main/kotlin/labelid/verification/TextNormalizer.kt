package labelid.verification

object TextNormalizer {
    private const val SHORT_TEXT_MAX_LINE_SPAN = 8
    private const val SHORT_TEXT_TOKEN_SPAN_MULTIPLIER = 7

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

    fun containsOrderedTokenWindow(haystack: String, needle: String): Boolean {
        val expectedTokens = matchTokens(needle)
        if (expectedTokens.size < 2) return false

        val actualTokens = indexedMatchTokens(haystack)
        if (actualTokens.isEmpty()) return false

        return if (expectedTokens.size <= 4) {
            containsCompleteShortMatch(actualTokens, expectedTokens)
        } else {
            containsLongCoverageMatch(actualTokens, expectedTokens)
        }
    }

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

    private fun containsCompleteShortMatch(
        actualTokens: List<IndexedToken>,
        expectedTokens: List<String>,
    ): Boolean {
        for (startIndex in actualTokens.indices) {
            if (!tokenMatches(actualTokens[startIndex].value, expectedTokens.first())) continue

            val matchedTokens = mutableListOf(actualTokens[startIndex])
            var expectedIndex = 1
            for (actualIndex in (startIndex + 1) until actualTokens.size) {
                if (tokenMatches(actualTokens[actualIndex].value, expectedTokens[expectedIndex])) {
                    matchedTokens.add(actualTokens[actualIndex])
                    expectedIndex++
                    if (expectedIndex == expectedTokens.size) break
                }
            }

            if (
                expectedIndex == expectedTokens.size &&
                isStrictShortWindow(matchedTokens, expectedTokens.size) &&
                hasEnoughAdjacentEvidence(actualTokens, expectedTokens)
            ) {
                return true
            }
        }
        return false
    }

    private fun containsLongCoverageMatch(
        actualTokens: List<IndexedToken>,
        expectedTokens: List<String>,
    ): Boolean {
        val requiredMatches = kotlin.math.ceil(expectedTokens.size * 0.90).toInt()
        for (startIndex in actualTokens.indices) {
            val matchedTokens = mutableListOf<IndexedToken>()
            var expectedIndex = 0
            for (actualIndex in startIndex until actualTokens.size) {
                while (
                    expectedIndex < expectedTokens.size &&
                    !tokenMatches(actualTokens[actualIndex].value, expectedTokens[expectedIndex])
                ) {
                    expectedIndex++
                }

                if (expectedIndex < expectedTokens.size) {
                    matchedTokens.add(actualTokens[actualIndex])
                    expectedIndex++
                    if (expectedIndex == expectedTokens.size) break
                }
            }

            if (
                matchedTokens.size >= requiredMatches &&
                isStrictLongWindow(matchedTokens, expectedTokens.size)
            ) {
                return true
            }
        }
        return false
    }

    private fun isStrictShortWindow(
        matchedTokens: List<IndexedToken>,
        expectedTokenCount: Int,
    ): Boolean {
        val tokenSpan = matchedTokens.last().tokenIndex - matchedTokens.first().tokenIndex + 1
        val lineSpan = matchedTokens.last().lineIndex - matchedTokens.first().lineIndex + 1
        return tokenSpan <= expectedTokenCount * SHORT_TEXT_TOKEN_SPAN_MULTIPLIER &&
            lineSpan <= SHORT_TEXT_MAX_LINE_SPAN
    }

    private fun isStrictLongWindow(
        matchedTokens: List<IndexedToken>,
        expectedTokenCount: Int,
    ): Boolean {
        val tokenSpan = matchedTokens.last().tokenIndex - matchedTokens.first().tokenIndex + 1
        val lineSpan = matchedTokens.last().lineIndex - matchedTokens.first().lineIndex + 1
        return tokenSpan <= expectedTokenCount * 3 &&
            lineSpan <= expectedTokenCount + 3
    }

    private fun hasEnoughAdjacentEvidence(
        actualTokens: List<IndexedToken>,
        expectedTokens: List<String>,
    ): Boolean {
        if (expectedTokens.size == 2) return true

        return expectedTokens.windowed(size = 2).any { expectedPair ->
            actualTokens.windowed(size = 2).any { actualPair ->
                tokenMatches(actualPair[0].value, expectedPair[0]) &&
                    tokenMatches(actualPair[1].value, expectedPair[1])
            }
        }
    }

    private fun indexedMatchTokens(value: String): List<IndexedToken> {
        var tokenIndex = 0
        return buildList {
            value.lines().forEachIndexed { lineIndex, line ->
                matchTokens(line).forEach { token ->
                    add(IndexedToken(value = token, lineIndex = lineIndex, tokenIndex = tokenIndex))
                    tokenIndex++
                }
            }
        }
    }

    private fun matchTokens(value: String): List<String> =
        tokens(value)
            .map(::compact)
            .filter { it.isNotBlank() && (it.length > 1 || it.any { char -> char.isDigit() }) }

    private fun tokenMatches(actual: String, expected: String): Boolean =
        actual == expected || singularPluralEquivalent(actual, expected)

    private fun singularPluralEquivalent(left: String, right: String): Boolean =
        (left.length > 3 && left.endsWith("s") && left.dropLast(1) == right) ||
            (right.length > 3 && right.endsWith("s") && right.dropLast(1) == left)

    private data class IndexedToken(
        val value: String,
        val lineIndex: Int,
        val tokenIndex: Int,
    )
}
