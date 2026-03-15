package com.medusalabs.aiken.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AikenStdlibCatalogTest {
    @Test
    fun parsesCompatibilityRulesFromStdlibReadme() {
        val rules =
            AikenStdlibCatalog.parseCompatibilityRules(
                """
                ## Compatibility
                
                stdlib's version(s) | aiken's version | Plutus version
                --- | --- | ---
                [`>= 3.0.0`](https://example/v3) | `>= v1.1.17` | `V3`
                [`>= 2.1.0 && < 3.0.0`](https://example/v2) | `>= v1.1.3` | `V3`
                [`>= 2.0.0 && < 2.1.0`](https://example/v2.0.0) | `v1.1.1`, `v1.1.2` | `V3`
                [`>= 1.9.0 && < 2.0.0`](https://example/v1.9.0) | `v1.0.28-alpha`, `v1.0.29-alpha` | `V2`
                """.trimIndent()
            )

        assertEquals(3, rules.size)
        assertEquals("V3", rules.first().plutusVersion)
    }

    @Test
    fun filtersStdlibTagsUsingCompatibilityMatrix() {
        val catalog =
            AikenStdlibCatalog.Catalog(
                tags = listOf("v3.0.0", "v2.2.1", "v2.1.0", "v2.0.0"),
                compatibilityRules =
                    AikenStdlibCatalog.parseCompatibilityRules(
                        """
                        ## Compatibility
                        
                        stdlib's version(s) | aiken's version | Plutus version
                        --- | --- | ---
                        [`>= 3.0.0`](https://example/v3) | `>= v1.1.17` | `V3`
                        [`>= 2.1.0 && < 3.0.0`](https://example/v2) | `>= v1.1.3` | `V3`
                        [`>= 2.0.0 && < 2.1.0`](https://example/v2.0.0) | `v1.1.1`, `v1.1.2` | `V3`
                        [`>= 1.9.0 && < 2.0.0`](https://example/v1.9.0) | `v1.0.28-alpha`, `v1.0.29-alpha` | `V2`
                        """.trimIndent()
                    )
            )

        assertEquals(
            listOf("v2.2.1", "v2.1.0"),
            catalog.compatibleTagsFor("1.1.3")
        )
        assertEquals(
            listOf("v2.0.0"),
            catalog.compatibleTagsFor("1.1.2")
        )
        assertEquals(
            listOf("v3.0.0"),
            catalog.compatibleTagsFor("1.1.21")
        )
        assertEquals(emptyList<String>(), catalog.compatibleTagsFor("1.0.29-alpha"))
        assertEquals(null, catalog.plutusVersionFor("v1.9.1"))
        assertEquals("v3.0.0", catalog.recommendedTagFor("1.1.21"))
        assertTrue(catalog.compatibleTagsFor(null).contains("v2.0.0"))
    }

    @Test
    fun parsesBundledSnapshotFormat() {
        val catalog =
            AikenStdlibCatalog.parseBundledCatalog(
                """
                # snapshot
                tag=2.2.1
                tag=v2
                tag=v2.2.1
                tag=3.0.0
                tag=v3.0.0
                rule=>= 3.0.0|>= v1.1.17|V3
                rule=>= 2.1.0 && < 3.0.0|>= v1.1.3|V3
                """.trimIndent()
            )

        assertEquals(listOf("v3.0.0", "v2.2.1"), catalog.tags)
        assertEquals(listOf("v2.2.1"), catalog.compatibleTagsFor("1.1.16"))
        assertEquals(listOf("v3.0.0"), catalog.compatibleTagsFor("1.1.21"))
    }

    @Test
    fun loadsBundledCatalogWithoutExtrasFallback() {
        val emptyDir = Files.createTempDirectory("stdlib-catalog-fallback")
        val catalog = AikenStdlibCatalog.loadCatalogBlocking(emptyDir)

        assertTrue(catalog.tags.isNotEmpty())
        assertEquals("V3", catalog.plutusVersionFor("v3.0.0"))
        assertEquals(listOf("v2.0.0"), catalog.compatibleTagsFor("1.1.1"))
        assertEquals(listOf("v2.0.0"), catalog.compatibleTagsFor("1.1.2"))
        assertEquals(emptyList<String>(), catalog.compatibleTagsFor("1.0.29-alpha"))
    }
}
