package labelid.domain

import java.nio.file.Path

data class ImageInput(
    val path: Path,
    val originalName: String = path.fileName.toString(),
)

data class ImageText(
    val text: String,
    val engine: String,
    val confidence: Double? = null,
    val diagnostics: List<String> = emptyList(),
)

data class ExpectedLabelData(
    val rawText: String,
    val brandName: String? = null,
    val fancifulName: String? = null,
    val dbaTradeName: String? = null,
    val classType: String? = null,
    val netContents: List<String> = emptyList(),
    val alcoholContent: String? = null,
    val wineVintage: String? = null,
    val grapeVarietals: List<String> = emptyList(),
    val wineAppellation: String? = null,
    val ignoredFields: Map<String, String> = emptyMap(),
) {
    fun comparableFields(): List<ExpectedField> = buildList {
        addText("Brand Name", brandName)
        addText("Fanciful Name", fancifulName)
        addText("DBA/Trade Name", dbaTradeName)
        addText("Class/Type", classType)
        netContents.forEach { add(ExpectedField("Net Contents", it, FieldKind.NET_CONTENTS)) }
        alcoholContent?.let { add(ExpectedField("Alcohol Content", it, FieldKind.ALCOHOL_CONTENT)) }
        addText("Wine Vintage", wineVintage)
        grapeVarietals.forEach { add(ExpectedField("Grape Varietal", it, FieldKind.TEXT)) }
        addText("Wine Appellation", wineAppellation)
    }

    fun hasComparableFields(): Boolean = comparableFields().isNotEmpty()

    private fun MutableList<ExpectedField>.addText(name: String, value: String?) {
        val cleanValue = value?.trim()
        if (!cleanValue.isNullOrBlank()) {
            add(ExpectedField(name, cleanValue, FieldKind.TEXT))
        }
    }
}

data class ExpectedField(
    val name: String,
    val value: String,
    val kind: FieldKind,
)

enum class FieldKind {
    TEXT,
    ALCOHOL_CONTENT,
    NET_CONTENTS,
}

enum class VerificationStatus {
    PASS,
    REVIEW,
    FAIL,
}

enum class CheckStatus {
    PASS,
    REVIEW,
    FAIL,
    NOT_ASSESSED,
}

data class FieldCheck(
    val fieldName: String,
    val expected: String,
    val observed: String?,
    val status: CheckStatus,
    val message: String,
)

data class VerificationReport(
    val status: VerificationStatus,
    val expected: ExpectedLabelData,
    val imageText: ImageText,
    val checks: List<FieldCheck>,
    val elapsedMillis: Long,
)
