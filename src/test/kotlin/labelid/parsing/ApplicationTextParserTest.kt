package labelid.parsing

import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTextParserTest {
    private val parser = ApplicationTextParser()

    @Test
    fun parsesKnownColaFieldsFromPastedText() {
        val parsed = parser.parse(
            """
            Type of Product: Distilled Spirits
            Source of Product: Domestic
            Serial Number: 26-001
            DBA/Trade Name: Old Tom Distillery
            Brand Name: STONE'S THROW
            Fanciful Name: Barrel Select
            Net Contents: 750 mL
            Alcohol Content: 45% Alc./Vol. (90 Proof)
            """.trimIndent(),
        )

        assertEquals("STONE'S THROW", parsed.brandName)
        assertEquals("Barrel Select", parsed.fancifulName)
        assertEquals("Old Tom Distillery", parsed.dbaTradeName)
        assertEquals(listOf("750 mL"), parsed.netContents)
        assertEquals("45% Alc./Vol. (90 Proof)", parsed.alcoholContent)
        assertEquals("26-001", parsed.ignoredFields["Serial Number"])
    }

    @Test
    fun parsesFieldLabelOnOneLineAndValueOnNext() {
        val parsed = parser.parse(
            """
            Brand Name
            OLD TOM DISTILLERY
            Net Contents
            750 mL
            """.trimIndent(),
        )

        assertEquals("OLD TOM DISTILLERY", parsed.brandName)
        assertEquals(listOf("750 mL"), parsed.netContents)
    }

    @Test
    fun splitsRepeatedValues() {
        val parsed = parser.parse(
            """
            Net Contents: 375 mL, 750 mL
            Grape Varietal(s): Cabernet Sauvignon; Merlot
            """.trimIndent(),
        )

        assertEquals(listOf("375 mL", "750 mL"), parsed.netContents)
        assertEquals(listOf("Cabernet Sauvignon", "Merlot"), parsed.grapeVarietals)
    }
}
