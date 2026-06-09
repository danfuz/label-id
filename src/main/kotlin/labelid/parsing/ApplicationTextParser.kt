package labelid.parsing

import labelid.domain.ExpectedLabelData

class ApplicationTextParser {
    fun parse(rawText: String): ExpectedLabelData {
        val collected = mutableMapOf<FieldTarget, MutableList<String>>()
        val ignored = linkedMapOf<String, String>()
        val lines = rawText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val parsed = parseFieldLine(line)

            when {
                parsed != null -> {
                    val value = parsed.value.ifBlank {
                        lines.getOrNull(index + 1)
                            ?.takeUnless { looksLikeFieldLine(it) }
                            .orEmpty()
                    }
                    collect(parsed.spec, value, collected, ignored)
                    index += if (parsed.value.isBlank() && value.isNotBlank()) 2 else 1
                }

                isFieldLabelOnly(line) && index + 1 < lines.size && !looksLikeFieldLine(lines[index + 1]) -> {
                    val spec = fieldSpecs.first { it.matchesLabel(line) }
                    collect(spec, lines[index + 1], collected, ignored)
                    index += 2
                }

                else -> index += 1
            }
        }

        return ExpectedLabelData(
            rawText = rawText,
            brandName = collected.singleValue(FieldTarget.BRAND_NAME),
            fancifulName = collected.singleValue(FieldTarget.FANCIFUL_NAME),
            dbaTradeName = collected.singleValue(FieldTarget.DBA_TRADE_NAME),
            classType = collected.singleValue(FieldTarget.CLASS_TYPE),
            netContents = collected.values(FieldTarget.NET_CONTENTS),
            alcoholContent = collected.singleValue(FieldTarget.ALCOHOL_CONTENT),
            wineVintage = collected.singleValue(FieldTarget.WINE_VINTAGE),
            grapeVarietals = collected.values(FieldTarget.GRAPE_VARIETALS),
            wineAppellation = collected.singleValue(FieldTarget.WINE_APPELLATION),
            ignoredFields = ignored,
        )
    }

    private fun collect(
        spec: FieldSpec,
        rawValue: String,
        collected: MutableMap<FieldTarget, MutableList<String>>,
        ignored: MutableMap<String, String>,
    ) {
        val value = sanitizeValue(rawValue)
        if (value.isBlank() || value.equals("n/a", ignoreCase = true)) return

        if (spec.target == FieldTarget.IGNORED) {
            ignored[spec.canonicalName] = value
            return
        }

        val values = if (spec.repeated) splitRepeatedValue(value) else listOf(value)
        values
            .filter { it.isNotBlank() }
            .forEach { collected.getOrPut(spec.target) { mutableListOf() }.add(it) }
    }

    private fun parseFieldLine(line: String): ParsedField? {
        for (spec in fieldSpecs) {
            for (alias in spec.aliases.sortedByDescending { it.length }) {
                val pattern = Regex(
                    pattern = "^\\s*${Regex.escape(alias)}\\s*(?:[:=\\-]|\\s{2,}|\\s+)\\s*(.*)$",
                    options = setOf(RegexOption.IGNORE_CASE),
                )
                val match = pattern.find(line) ?: continue
                return ParsedField(spec, match.groupValues[1].trim())
            }
        }
        return null
    }

    private fun looksLikeFieldLine(line: String): Boolean =
        parseFieldLine(line) != null || isFieldLabelOnly(line)

    private fun isFieldLabelOnly(line: String): Boolean =
        fieldSpecs.any { it.matchesLabel(line) }

    private fun sanitizeValue(value: String): String =
        value.trim()
            .trimStart('-', '>', ':')
            .trim()
            .removeSurrounding("\"")
            .trim()

    private fun splitRepeatedValue(value: String): List<String> =
        value.split(';', ',')
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun Map<FieldTarget, List<String>>.singleValue(target: FieldTarget): String? =
        values(target).firstOrNull()

    private fun Map<FieldTarget, List<String>>.values(target: FieldTarget): List<String> =
        get(target).orEmpty().distinct()

    private data class ParsedField(
        val spec: FieldSpec,
        val value: String,
    )

    private data class FieldSpec(
        val canonicalName: String,
        val target: FieldTarget,
        val aliases: List<String>,
        val repeated: Boolean = false,
    ) {
        fun matchesLabel(line: String): Boolean {
            val normalizedLine = normalizeLabel(line)
            return aliases.any { normalizeLabel(it) == normalizedLine }
        }
    }

    private enum class FieldTarget {
        BRAND_NAME,
        FANCIFUL_NAME,
        DBA_TRADE_NAME,
        CLASS_TYPE,
        NET_CONTENTS,
        ALCOHOL_CONTENT,
        WINE_VINTAGE,
        GRAPE_VARIETALS,
        WINE_APPELLATION,
        IGNORED,
    }

    private companion object {
        private val fieldSpecs = listOf(
            FieldSpec("Brand Name", FieldTarget.BRAND_NAME, listOf("brand name")),
            FieldSpec("Fanciful Name", FieldTarget.FANCIFUL_NAME, listOf("fanciful name")),
            FieldSpec(
                "DBA/Trade Name",
                FieldTarget.DBA_TRADE_NAME,
                listOf("dba/trade name", "dba trade name", "trade name", "approved dba", "dba"),
            ),
            FieldSpec(
                "Class/Type",
                FieldTarget.CLASS_TYPE,
                listOf("product class/type", "class/type", "class type", "type designation", "class designation"),
            ),
            FieldSpec("Net Contents", FieldTarget.NET_CONTENTS, listOf("net contents", "net content"), repeated = true),
            FieldSpec(
                "Alcohol Content",
                FieldTarget.ALCOHOL_CONTENT,
                listOf("alcohol content", "alc/vol", "alc vol", "abv"),
            ),
            FieldSpec("Wine Vintage", FieldTarget.WINE_VINTAGE, listOf("wine vintage", "vintage date", "vintage")),
            FieldSpec(
                "Grape Varietals",
                FieldTarget.GRAPE_VARIETALS,
                listOf("grape varietal(s)", "grape varietals", "grape varietal"),
                repeated = true,
            ),
            FieldSpec("Wine Appellation", FieldTarget.WINE_APPELLATION, listOf("wine appellation", "appellation")),
            FieldSpec("Serial Number", FieldTarget.IGNORED, listOf("serial number", "serial no")),
            FieldSpec("Type of Product", FieldTarget.IGNORED, listOf("type of product", "product type")),
            FieldSpec("Source of Product", FieldTarget.IGNORED, listOf("source of product", "source")),
            FieldSpec(
                "Permit",
                FieldTarget.IGNORED,
                listOf("plant registry/basic permit/brewer's no", "plant registry", "basic permit", "brewer's no"),
            ),
            FieldSpec("Formula", FieldTarget.IGNORED, listOf("ttb formula id", "formula id", "formula")),
            FieldSpec("Phone Number", FieldTarget.IGNORED, listOf("phone number", "phone")),
            FieldSpec("Email Address", FieldTarget.IGNORED, listOf("email address", "email")),
            FieldSpec("Notes to Specialist", FieldTarget.IGNORED, listOf("notes to specialist", "notes")),
        ).sortedByDescending { spec -> spec.aliases.maxOf { it.length } }

        private fun normalizeLabel(value: String): String =
            value.lowercase()
                .replace(Regex("\\([^)]*\\)"), "")
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
    }
}
