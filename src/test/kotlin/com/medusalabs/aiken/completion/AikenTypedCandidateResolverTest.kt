package com.medusalabs.aiken.completion

import com.intellij.psi.PsiElement
import com.medusalabs.aiken.AikenPlatformTestCase
import org.junit.Test

class AikenTypedCandidateResolverTest : AikenPlatformTestCase() {
    private val pipeResolver =
        object : AikenTypedCandidateResolver.Resolver {
            override fun parseBindingTypeAt(
                text: String,
                declarationOffset: Int,
                bindingName: String,
                anchor: PsiElement,
                visitedDeclarationOffsets: MutableSet<Int>
            ): String? = error("parseBindingTypeAt is not used in these tests")

            override fun matchesExpectedTypes(candidateType: String, expectedTypes: Set<String>): Boolean =
                AikenTypeDirectedCompletionSupport.matchesExpectedTypes(candidateType, expectedTypes)

            override fun normalizeTypeText(text: String): String =
                text.replace(Regex("\\s+"), " ").trim()

            override fun inferFunctionReturnType(
                anchor: PsiElement,
                functionName: String,
                modulePath: String?
            ): String? = null

            override fun resolveFunctionSignature(
                anchor: PsiElement,
                functionName: String,
                modulePath: String?
            ): String? = null
        }
    private val expectedTypeResolver =
        object : AikenTypedCandidateResolver.Resolver {
            override fun parseBindingTypeAt(
                text: String,
                declarationOffset: Int,
                bindingName: String,
                anchor: PsiElement,
                visitedDeclarationOffsets: MutableSet<Int>
            ): String? {
                val nameEnd = declarationOffset + bindingName.length
                if (nameEnd >= text.length || text.getOrNull(nameEnd) != ':') return null
                val typeStart = nameEnd + 1
                val typeEnd = text.indexOf('=', typeStart).takeIf { it >= 0 } ?: return null
                return text.substring(typeStart, typeEnd).trim()
            }

            override fun matchesExpectedTypes(candidateType: String, expectedTypes: Set<String>): Boolean =
                AikenTypeDirectedCompletionSupport.matchesExpectedTypes(candidateType, expectedTypes)

            override fun normalizeTypeText(text: String): String =
                text.replace(Regex("\\s+"), " ").trim()

            override fun inferFunctionReturnType(
                anchor: PsiElement,
                functionName: String,
                modulePath: String?
            ): String? = null

            override fun resolveFunctionSignature(
                anchor: PsiElement,
                functionName: String,
                modulePath: String?
            ): String? = null
        }

    @Test
    fun collectsVisiblePipeFunctionsFromCurrentFile() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val anchor =
            configureAnchor(
                "main.ak",
                """
                fn keep(items: List<Int>, limit: Int) -> List<Int> {
                  items
                }

                fn main() {
                  [1] |> ke<caret>
                }
                """.trimIndent()
            )

        val suggestions =
            AikenTypedCandidateResolver.collectVisiblePipeFunctions(
                anchor = anchor,
                expectedType = profile("List<Int>"),
                excludedNames = emptySet(),
                resolver = pipeResolver
            )

        assertEquals(listOf("keep"), suggestions.map { it.lookupText })
    }

    @Test
    fun collectsQualifiedPipeFunctionsFromImportedModuleAlias() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/math.ak",
            """
            pub fn filter(items: List<Int>, predicate: fn(Int) -> Bool) -> List<Int> {
              items
            }

            fn hidden(items: List<Int>, predicate: fn(Int) -> Bool) -> List<Int> {
              items
            }
            """.trimIndent()
        )
        val anchor =
            configureAnchor(
                "main.ak",
                """
                use math as ops

                fn main() {
                  [1] |> ops.fi<caret>
                }
                """.trimIndent()
            )

        val suggestions =
            AikenTypedCandidateResolver.collectQualifiedPipeFunctions(
                anchor = anchor,
                expectedType = profile("List<Int>"),
                qualifier = null,
                excludedNames = emptySet(),
                resolver = pipeResolver
            )

        assertEquals(listOf("ops.filter"), suggestions.map { it.lookupText })
    }

    @Test
    fun collectsExpectedTypeCandidatesInStableSourceOrder() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/visible.ak",
            """
            pub const visible_items: List<Int> = []

            pub fn visible_list() -> List<Int> {
              []
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "lib/remote.ak",
            """
            pub const remote_items: List<Int> = []

            pub fn remote_list() -> List<Int> {
              []
            }
            """.trimIndent()
        )
        val anchor =
            configureAnchor(
                "main.ak",
                """
                use visible.{visible_items, visible_list}

                const default_items: List<Int> = []

                fn local_list() -> List<Int> {
                  []
                }

                fn main() {
                  let local_items: List<Int> = []
                  lo<caret>
                }
                """.trimIndent()
            )

        val candidates =
            AikenTypedCandidateResolver.collectCandidatesForExpectedType(
                anchor = anchor,
                expectedType = profile("List<Int>"),
                extraCandidates = listOf(
                    AikenTypedCompletionCandidate(
                        name = "provided",
                        type = "List<Int>",
                        kind = CompletionSymbolKind.IDENTIFIER
                    )
                ),
                excludedNames = emptySet(),
                resolver = expectedTypeResolver
            )

        assertEquals(
            listOf(
                "provided",
                "local_items",
                "default_items",
                "visible_items",
                "local_list",
                "visible_list",
                "remote_items",
                "remote_list",
                "[]"
            ),
            candidates.map(::candidateLabel)
        )

        val remoteConst =
            candidates.first { candidateLabel(it) == "remote_items" } as AikenTypedExpectedTypeCandidate.Identifier
        assertEquals(AikenTypedCandidateOrigin.UNIMPORTED, remoteConst.origin)
        assertEquals("remote", remoteConst.modulePath)
        assertEquals(AikenTypedCandidateAutoImportMode.SYMBOL, remoteConst.autoImportMode)

        val remoteFunction =
            candidates.first { candidateLabel(it) == "remote_list" } as AikenTypedExpectedTypeCandidate.Function
        assertEquals(AikenTypedCandidateOrigin.UNIMPORTED, remoteFunction.origin)
        assertEquals("remote", remoteFunction.modulePath)
        assertEquals(AikenTypedCandidateAutoImportMode.SYMBOL, remoteFunction.autoImportMode)
    }

    @Test
    fun collectsRecordSiblingFieldsAsDedicatedContextSource() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val anchor =
            configureAnchor(
                "main.ak",
                """
                pub type Credential {
                  VerificationKey
                }

                pub type Qwe {
                  Qwe1 { primary: Credential, backup: Credential }
                }

                fn main() {
                  let q = Qwe1 { primary: b<caret> }
                }
                """.trimIndent()
            )

        val candidates =
            AikenTypedCandidateResolver.collectCandidatesForExpectedType(
                anchor = anchor,
                expectedType = profile("Credential"),
                extraCandidates = emptyList(),
                context =
                    AikenTypedCandidateContext.RecordFieldValue(
                        siblingFields =
                            listOf(
                                AikenTypedCandidateContext.RecordFieldValue.SiblingField("backup", "Credential"),
                                AikenTypedCandidateContext.RecordFieldValue.SiblingField("ignored", "Int")
                            )
                    ),
                excludedNames = emptySet(),
                resolver = expectedTypeResolver
            )

        val backupCandidate =
            candidates.firstOrNull { candidateLabel(it) == "backup" } as? AikenTypedExpectedTypeCandidate.Identifier
                ?: error("Expected backup sibling field candidate, got ${candidates.map(::candidateLabel)}")

        assertEquals(AikenTypedCandidateSource.RECORD_SIBLING_FIELD, backupCandidate.source)
        assertEquals(CompletionSymbolKind.FIELD, backupCandidate.kind)
        assertFalse(candidates.any { candidateLabel(it) == "ignored" })
    }

    @Test
    fun resolvesSameFileUntypedConstsThroughDedicatedConstHook() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val anchor =
            configureAnchor(
                "main.ak",
                """
                pub type Input {
                  amount: Int,
                }

                pub type Transaction {
                  inputs: List<Input>,
                }

                const placeholder = Transaction { inputs: [] }

                fn main() {
                  pla<caret>
                }
                """.trimIndent()
            )

        var dedicatedConstCalls = 0
        val resolver =
            object : AikenTypedCandidateResolver.Resolver {
                override fun parseBindingTypeAt(
                    text: String,
                    declarationOffset: Int,
                    bindingName: String,
                    anchor: PsiElement,
                    visitedDeclarationOffsets: MutableSet<Int>
                ): String? = error("Same-file consts should use dedicated const type resolution")

                override fun matchesExpectedTypes(candidateType: String, expectedTypes: Set<String>): Boolean =
                    AikenTypeDirectedCompletionSupport.matchesExpectedTypes(candidateType, expectedTypes)

                override fun normalizeTypeText(text: String): String =
                    text.replace(Regex("\\s+"), " ").trim()

                override fun resolveSameFileConstType(
                    text: String,
                    declarationOffset: Int,
                    constName: String,
                    anchor: PsiElement
                ): String? {
                    dedicatedConstCalls++
                    return if (constName == "placeholder") "Transaction" else null
                }

                override fun inferFunctionReturnType(
                    anchor: PsiElement,
                    functionName: String,
                    modulePath: String?
                ): String? = null

                override fun resolveFunctionSignature(
                    anchor: PsiElement,
                    functionName: String,
                    modulePath: String?
                ): String? = null
            }

        val candidates =
            AikenTypedCandidateResolver.collectVisibleTypedConsts(
                anchor = anchor,
                expectedType = profile("Transaction"),
                excludedNames = emptySet(),
                resolver = resolver
            )

        assertEquals(1, dedicatedConstCalls)
        assertEquals(listOf("placeholder"), candidates.map { it.name })
        assertEquals(AikenTypedCandidateOrigin.LOCAL, candidates.single().origin)
        assertEquals(AikenTypedCandidateSource.CONST, candidates.single().source)
    }

    @Test
    fun collectsPipeCandidatesInStableSourceOrder() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/visible.ak",
            """
            pub fn visible_pipe(items: List<Int>) -> List<Int> {
              items
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "lib/qualified.ak",
            """
            pub fn qualified_pipe(items: List<Int>) -> List<Int> {
              items
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "lib/remote.ak",
            """
            pub fn remote_pipe(items: List<Int>) -> List<Int> {
              items
            }
            """.trimIndent()
        )
        val anchor =
            configureAnchor(
                "main.ak",
                """
                use visible.{visible_pipe}
                use qualified as ops

                fn local_pipe(items: List<Int>) -> List<Int> {
                  items
                }

                fn main() {
                  [1] |> lo<caret>
                }
                """.trimIndent()
            )

        val candidates =
            AikenTypedCandidateResolver.collectPipeCandidatesForInputType(
                anchor = anchor,
                inputType = profile("List<Int>"),
                qualifier = null,
                excludedNames = emptySet(),
                resolver = pipeResolver
            )

        val labels = candidates.map(::candidateLabel)
        assertEquals(listOf("local_pipe", "visible_pipe"), labels.take(2))
        assertTrue(labels.toString(), labels.contains("qualified_pipe"))
        assertTrue(labels.toString(), labels.contains("remote_pipe"))

        val qualifiedUnimported =
            candidates.first { candidateLabel(it) == "qualified_pipe" } as AikenTypedExpectedTypeCandidate.PipeFunction
        assertEquals(AikenTypedCandidateOrigin.UNIMPORTED, qualifiedUnimported.origin)
        assertEquals("qualified", qualifiedUnimported.modulePath)
        assertEquals(AikenTypedCandidateAutoImportMode.SYMBOL, qualifiedUnimported.autoImportMode)

        val remote =
            candidates.first { candidateLabel(it) == "remote_pipe" } as AikenTypedExpectedTypeCandidate.PipeFunction
        assertEquals(AikenTypedCandidateOrigin.UNIMPORTED, remote.origin)
        assertEquals("remote", remote.modulePath)
        assertEquals(AikenTypedCandidateAutoImportMode.SYMBOL, remote.autoImportMode)
    }

    private fun candidateLabel(candidate: AikenTypedExpectedTypeCandidate): String =
        when (candidate) {
            is AikenTypedExpectedTypeCandidate.Identifier -> candidate.name
            is AikenTypedExpectedTypeCandidate.Function -> candidate.name
            is AikenTypedExpectedTypeCandidate.PipeFunction -> candidate.lookupText
            is AikenTypedExpectedTypeCandidate.Constructible -> candidate.name
            AikenTypedExpectedTypeCandidate.ListLiteral -> "[]"
            AikenTypedExpectedTypeCandidate.OptionSome -> "Some()"
        }

    private fun configureAnchor(path: String, source: String): PsiElement =
        findElementAtCaret(myFixture.configureByText(path, source))
            ?: error("Expected anchor at caret")

    private fun profile(type: String): AikenExpectedTypeProfile =
        AikenExpectedTypeProfile(
            primaryType = type,
            compatibleTypes = linkedMapOf(type to 0)
        )
}
