package labelid.verification

object AlcoholContentParser {
    private val percentPattern = Regex(
        pattern = """(?i)(\d+(?:\.\d+)?)\s*(?:%|percent)(?:\s*(?:alc\.?\s*/?\s*vol\.?|abv|alcohol\s+by\s+volume))?""",
    )

    fun parseExpected(value: String): Double? =
        percentPattern.find(TextNormalizer.normalizeCompatibility(value))?.groupValues?.get(1)?.toDoubleOrNull()

    fun parseAll(value: String): List<Double> =
        percentPattern.findAll(TextNormalizer.normalizeCompatibility(value))
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .toList()
}

object NetContentsParser {
    private val volumePattern = Regex(
        pattern = """(?i)(\d+(?:\.\d+)?)\s*(ml|milliliters?|millilitres?|l|liters?|litres?|fl\.?\s*oz\.?|fluid\s*ounces?)""",
    )

    fun parseExpected(value: String): Volume? =
        volumePattern.find(TextNormalizer.normalizeCompatibility(value))?.toVolume()

    fun parseAll(value: String): List<Volume> =
        volumePattern.findAll(TextNormalizer.normalizeCompatibility(value))
            .mapNotNull { it.toVolume() }
            .toList()

    private fun MatchResult.toVolume(): Volume? {
        val amount = groupValues[1].toDoubleOrNull() ?: return null
        val unit = groupValues[2].lowercase().replace(Regex("\\s+"), " ")
        val milliliters = when {
            unit == "ml" || unit.startsWith("milliliter") || unit.startsWith("millilitre") -> amount
            unit == "l" || unit.startsWith("liter") || unit.startsWith("litre") -> amount * 1000.0
            unit.contains("oz") || unit.startsWith("fluid") -> amount * 29.5735
            else -> return null
        }
        return Volume(milliliters)
    }
}

data class Volume(
    val milliliters: Double,
) {
    fun equivalentTo(other: Volume): Boolean =
        kotlin.math.abs(milliliters - other.milliliters) <= maxOf(1.0, milliliters * 0.002)
}
