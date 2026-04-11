package com.medusalabs.aiken.completion

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.medusalabs.aiken.AikenPlatformTestCase
import com.medusalabs.aiken.highlight.lexer.AikenLexing
import org.junit.Assert.assertEquals
import org.junit.Test

class AikenReferenceCompletionTest : AikenPlatformTestCase() {
    @Test
    fun suggestsTypedParameterAndLocalBindingForSingleCharacterPrefix() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            fn main(seed: Int, extra: Int) -> Int {
              let inner_value = seed + extra
              i<caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("inner_value"))
    }

    @Test
    fun suggestsSingleCharacterLocalBindingInOrdinaryCompletion() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            fn main() {
              let a = 1
              <caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("a"))
    }

    @Test
    fun suggestsImportedAliasesForSingleCharacterPrefix() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/math.ak",
            """
            pub fn add(left: Int, right: Int) -> Int {
              left
            }
            """.trimIndent()
        )
        val mainFile =
            myFixture.addFileToProject(
                "lib/main.ak",
                """
                use math.{add as sum}
                use math as math_utils

                fn main(seed: Int) -> Int {
                  s
                }
                """.trimIndent()
            )
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("\n  s") + 4)

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("sum"))
    }

    @Test
    fun suggestsModuleAndItemAliasesFromMixedImportForm() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/collection/list.ak",
            """
            pub fn count(items: List<a>) -> Int {
              0
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use aiken/collection/list.{count as my_count} as native_list

            fn main() {
              native_<caret>
            }
            """.trimIndent()
        )

        assertCompletionContainsOrAutoInserted("native_list", "native_list")

        myFixture.configureByText(
            "main.ak",
            """
            use aiken/collection/list.{count as my_count} as native_list

            fn main() {
              my_<caret>
            }
            """.trimIndent()
        )

        assertCompletionContainsOrAutoInserted("my_count", "my_count")
    }

    @Test
    fun suggestsSameFileTopLevelFunctionForSingleCharacterPrefix() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            fn helper(seed: Int) -> Int {
              seed
            }

            fn main(seed: Int) -> Int {
              h<caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.contains("helper"))
    }

    @Test
    fun suggestsUnimportedTypeInOrdinaryExpressionPositionAndAutoImportsIt() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/models.ak",
            """
            pub type Input {
              Input
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            fn main() {
              let input = Inp<caret>
            }
            """.trimIndent()
        )

        val directAnchor = findElementAtCaret(myFixture.file) ?: error("Expected anchor at caret")
        val directSuggestions =
            AikenReferenceVariants.unimportedExportsForPrefix(directAnchor, "Inp")
                .map { it.lookupString }
        assertTrue(directSuggestions.toString(), directSuggestions.contains("Input"))

        val suggestions = completionVariants()
        if (!suggestions.contains("Input")) {
            val autoInserted = myFixture.file.text
            assertTrue(autoInserted, autoInserted.contains("use models.{Input}"))
            assertTrue(autoInserted, autoInserted.contains("let input = Input"))
            return
        }

        val elements = myFixture.lookupElements ?: error("Expected completion items")
        val target = elements.firstOrNull { it.lookupString == "Input" } ?: error("Expected Input lookup")
        myFixture.lookup.currentItem = target
        myFixture.finishLookup('\n')

        val fileText = myFixture.file.text
        assertTrue(fileText, fileText.contains("use models.{Input}"))
        assertTrue(fileText, fileText.contains("let input = Input"))
    }

    @Test
    fun prefersImportedAndKeywordMatchesBeforeUnimportedOrdinaryTypes() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/transaction.ak",
            """
            pub type Transaction {
              Transaction
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/governance.ak",
            """
            pub type Treaty {
              Treaty
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use cardano/transaction.{Transaction}

            fn main() {
              let tx = T<caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val transactionIndex = suggestions.indexOf("Transaction")
        val trueIndex = suggestions.indexOf("True")
        val treatyIndex = suggestions.indexOf("Treaty")

        assertTrue("Expected Transaction in suggestions, got: $suggestions", transactionIndex >= 0)
        assertTrue("Expected True in suggestions, got: $suggestions", trueIndex >= 0)
        assertTrue("Expected Treaty in suggestions, got: $suggestions", treatyIndex >= 0)
        assertTrue("Expected imported Transaction above unrelated unimported Treaty, got: $suggestions", transactionIndex < treatyIndex)
        assertTrue("Expected keyword True above unrelated unimported Treaty, got: $suggestions", trueIndex < treatyIndex)
    }

    @Test
    fun prefersLocalThenImportedThenSameFileThenUnimportedOrdinaryMatches() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/time.ak",
            """
            pub fn from_now() -> Int {
              0
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "lib/frame.ak",
            """
            pub fn from_remote() -> Int {
              0
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "lib/frost.ak",
            """
            pub fn chill() -> Int {
              0
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use time.{from_now}

            const from_file: Int = 0

            fn main(seed: Int) -> Int {
              let from_scope = seed
              fro<caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val localIndex = suggestions.indexOf("from_scope")
        val importedIndex = suggestions.indexOf("from_now")
        val sameFileIndex = suggestions.indexOf("from_file")
        val unimportedIndex = suggestions.indexOf("from_remote")
        val moduleIndex = suggestions.indexOf("frost")

        assertTrue("Expected local binding in suggestions, got: $suggestions", localIndex >= 0)
        assertTrue("Expected imported symbol in suggestions, got: $suggestions", importedIndex >= 0)
        assertTrue("Expected same-file const in suggestions, got: $suggestions", sameFileIndex >= 0)
        assertTrue("Expected unimported symbol in suggestions, got: $suggestions", unimportedIndex >= 0)
        assertTrue("Expected unimported module in suggestions, got: $suggestions", moduleIndex >= 0)
        assertTrue("Expected local binding above imported symbol, got: $suggestions", localIndex < importedIndex)
        assertTrue("Expected imported symbol above same-file declaration, got: $suggestions", importedIndex < sameFileIndex)
        assertTrue("Expected same-file declaration above unimported symbol, got: $suggestions", sameFileIndex < unimportedIndex)
        assertTrue("Expected unimported symbol above bare module qualifier, got: $suggestions", unimportedIndex < moduleIndex)
    }

    @Test
    fun suggestsUnimportedFunctionInOrdinaryExpressionPositionAndAutoImportsIt() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/time.ak",
            """
            pub fn before() -> Bool {
              True
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            fn main() {
              let interval = befo<caret>
            }
            """.trimIndent()
        )

        val directAnchor = findElementAtCaret(myFixture.file) ?: error("Expected anchor at caret")
        val directSuggestions =
            AikenReferenceVariants.unimportedExportsForPrefix(directAnchor, "befo")
                .map { it.lookupString }
        assertTrue(directSuggestions.toString(), directSuggestions.contains("before"))

        val suggestions = completionVariants()
        if (!suggestions.contains("before")) {
            val autoInserted = myFixture.file.text
            assertTrue(autoInserted, autoInserted.contains("use time.{before}"))
            assertTrue(autoInserted, autoInserted.contains("let interval = before"))
            return
        }

        val elements = myFixture.lookupElements ?: error("Expected completion items")
        val target = elements.firstOrNull { it.lookupString == "before" } ?: error("Expected before lookup")
        myFixture.lookup.currentItem = target
        myFixture.finishLookup('\n')

        val fileText = myFixture.file.text
        assertTrue(fileText, fileText.contains("use time.{before}"))
        assertTrue(fileText, fileText.contains("let interval = before"))
    }

    @Test
    fun suggestsUnimportedModuleInOrdinaryExpressionPositionAndAutoImportsBareModule() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/list.ak",
            """
            pub fn map(items) {
              items
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            fn main() {
              let q = lis<caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        assertTrue(suggestions.toString(), suggestions.contains("list"))

        val target = myFixture.lookupElements?.firstOrNull { it.lookupString == "list" }
            ?: error("Expected list lookup, got $suggestions")
        myFixture.lookup.currentItem = target
        myFixture.finishLookup('\n')

        val fileText = myFixture.file.text
        assertTrue(fileText, fileText.contains("use aiken/list\n"))
        assertTrue(fileText, fileText.contains("let q = list."))
    }

    @Test
    fun suggestsQualifiedModuleMembersAfterBareModuleImportWithoutExplicitEntityAutoImport() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/list.ak",
            """
            pub fn map(items) {
              items
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use aiken/list

            fn main() {
              let q = list.ma<caret>
            }
            """.trimIndent()
        )

        acceptLookupOrAutoInserted("map", "let q = list.map")

        val fileText = myFixture.file.text
        assertTrue(fileText, fileText.contains("use aiken/list\n"))
        assertFalse(fileText, fileText.contains("use aiken/list.{map}"))
        assertTrue(fileText, fileText.contains("let q = list.map"))
    }

    @Test
    fun suggestsQualifiedModuleMembersFromUnimportedBareModuleAndAutoImportsModuleOnly() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/list.ak",
            """
            pub fn length(items) {
              0
            }

            pub fn filter(items, predicate) {
              items
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            fn main() {
              let q = list.<caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("length"))
        assertTrue(suggestions.toString(), suggestions.contains("filter"))

        val target = myFixture.lookupElements?.firstOrNull { it.lookupString == "length" }
            ?: error("Expected length lookup, got $suggestions")
        myFixture.lookup.currentItem = target
        myFixture.finishLookup('\n')

        val fileText = myFixture.file.text
        assertTrue(fileText, fileText.contains("use aiken/list\n"))
        assertFalse(fileText, fileText.contains("use aiken/list.{length}"))
        assertTrue(fileText, fileText.contains("let q = list.length"))
    }

    @Test
    fun suggestsQualifiedMembersFromNestedUnimportedBareModuleAndAutoImportsModuleOnly() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/math/rational.ak",
            """
            pub fn reduce(left, right) {
              left
            }

            pub fn zero() {
              0
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            fn main() {
              let q = rational.<caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("reduce"))
        assertTrue(suggestions.toString(), suggestions.contains("zero"))

        val target = myFixture.lookupElements?.firstOrNull { it.lookupString == "reduce" }
            ?: error("Expected reduce lookup, got $suggestions")
        myFixture.lookup.currentItem = target
        myFixture.finishLookup('\n')

        val fileText = myFixture.file.text
        assertTrue(fileText, fileText.contains("use aiken/math/rational\n"))
        assertFalse(fileText, fileText.contains("use aiken/math/rational.{reduce}"))
        assertTrue(fileText, fileText.contains("let q = rational.reduce"))
    }

    @Test
    fun suggestsQualifiedMembersFromNestedModulePathSugarAndRewritesToBareQualifier() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/math/rational.ak",
            """
            pub fn reduce(left, right) {
              left
            }

            pub fn zero() {
              0
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            fn main() {
              let q = math.rational.<caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("reduce"))
        assertTrue(suggestions.toString(), suggestions.contains("zero"))

        val target = myFixture.lookupElements?.firstOrNull { it.lookupString == "reduce" }
            ?: error("Expected reduce lookup, got $suggestions")
        myFixture.lookup.currentItem = target
        myFixture.finishLookup('\n')

        val fileText = myFixture.file.text
        assertTrue(fileText, fileText.contains("use aiken/math/rational\n"))
        assertFalse(fileText, fileText.contains("use aiken/math/rational.{reduce}"))
        assertTrue(fileText, fileText.contains("let q = rational.reduce"))
        assertFalse(fileText, fileText.contains("let q = math.rational.reduce"))
    }

    @Test
    fun suggestsSecondLevelModuleContinuationForNestedQualifiedPrefix() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/math/rational.ak",
            """
            pub fn reduce(left, right) {
              left
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            fn main() {
              let q = math.ra<caret>
            }
            """.trimIndent()
        )

        acceptLookupOrAutoInserted(
            expectedLookup = "rational",
            expectedInsertedSnippet = "let q = rational."
        )

        val fileText = myFixture.file.text
        assertTrue(fileText, fileText.contains("let q = rational."))
        assertTrue(fileText, fileText.contains("use aiken/math/rational\n"))
    }

    @Test
    fun suggestsSecondLevelModuleContinuationWhenParentModuleAlsoMatchesPrefix() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/math.ak",
            """
            pub fn ratio(value) {
              value
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/math/rational.ak",
            """
            pub fn reduce(left, right) {
              left
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            fn main() {
              let q = math.ra<caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        assertTrue(suggestions.toString(), suggestions.contains("ratio"))
        assertTrue(suggestions.toString(), suggestions.contains("rational"))
    }

    @Test
    fun rewritesNestedImportedModulePathSugarToCanonicalQualifierOnMemberInsert() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/math/rational.ak",
            """
            pub fn reduce(left, right) {
              left
            }

            pub fn zero() {
              0
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use aiken/math/rational

            fn main() {
              let q = math.rational.re<caret>
            }
            """.trimIndent()
        )

        acceptLookupOrAutoInserted(
            expectedLookup = "reduce",
            expectedInsertedSnippet = "let q = rational.reduce"
        )

        val fileText = myFixture.file.text
        assertTrue(fileText, fileText.contains("use aiken/math/rational\n"))
        assertTrue(fileText, fileText.contains("let q = rational.reduce"))
    }

    @Test
    fun rewritesNestedImportedModulePathSugarToImportedAliasOnMemberInsert() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/math/rational.ak",
            """
            pub fn reduce(left, right) {
              left
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use aiken/math/rational as rat

            fn main() {
              let q = math.rational.re<caret>
            }
            """.trimIndent()
        )

        acceptLookupOrAutoInserted(
            expectedLookup = "reduce",
            expectedInsertedSnippet = "let q = rat.reduce"
        )

        val fileText = myFixture.file.text
        assertTrue(fileText, fileText.contains("use aiken/math/rational as rat\n"))
        assertTrue(fileText, fileText.contains("let q = rat.reduce"))
    }

    @Test
    fun suggestsNestedBareModuleInOrdinaryExpressionPositionAndAutoImportsIt() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/math/rational.ak",
            """
            pub fn zero() {
              0
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            fn main() {
              let q = rat<caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        assertTrue(suggestions.toString(), suggestions.contains("rational"))

        val target = myFixture.lookupElements?.firstOrNull { it.lookupString == "rational" }
            ?: error("Expected rational lookup, got $suggestions")
        myFixture.lookup.currentItem = target
        myFixture.finishLookup('\n')

        val fileText = myFixture.file.text
        assertTrue(fileText, fileText.contains("use aiken/math/rational\n"))
        assertTrue(fileText, fileText.contains("let q = rational."))
    }

    @Test
    fun keepsAllUnimportedFunctionCandidatesWhenDifferentModulesExportSameName() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/rationals.ak",
            """
            pub fn reduce(value) {
              value
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "lib/collections.ak",
            """
            pub fn reduce(items, reducer, zero) {
              zero
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "lib/helpers.ak",
            """
            pub fn reducer(value) {
              value
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            fn main() {
              let qqq = redu<caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val reduceLookups = (myFixture.lookupElements ?: error("Expected completion items")).filter { it.lookupString == "reduce" }

        assertEquals("Expected both reduce variants, got: $suggestions", 2, suggestions.count { it == "reduce" })
        assertEquals("Expected two reduce lookups, got: $suggestions", 2, reduceLookups.size)
        assertTrue(reduceLookups.mapNotNull(::lookupTailText).any { it.contains("from rationals") })
        assertTrue(reduceLookups.mapNotNull(::lookupTailText).any { it.contains("from collections") })

        val target = reduceLookups.firstOrNull { lookupTailText(it)?.contains("from collections") == true }
            ?: error("Expected collections reduce lookup, got ${reduceLookups.mapNotNull(::lookupTailText)}")
        myFixture.lookup.currentItem = target
        myFixture.finishLookup('\n')

        val fileText = myFixture.file.text
        assertTrue(fileText, fileText.contains("use collections.{reduce}"))
        assertTrue(fileText, fileText.contains("let qqq = reduce"))
    }

    @Test
    fun keepsUnimportedConstAndFunctionCandidatesWhenLocalBindingHasSameName() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/numbers.ak",
            """
            pub const zero: Int = 0
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "lib/math_helpers.ak",
            """
            pub fn zero() {
              0
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            fn main(zero: Int) {
              let qqq = ze<caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val zeroLookups = (myFixture.lookupElements ?: error("Expected completion items")).filter { it.lookupString == "zero" }

        assertEquals("Expected local, const, and function zero variants, got: $suggestions", 3, suggestions.count { it == "zero" })
        assertEquals("Expected three zero lookups, got: $suggestions", 3, zeroLookups.size)
        assertTrue(zeroLookups.mapNotNull(::lookupTailText).any { it.contains("from numbers") })
        assertTrue(zeroLookups.mapNotNull(::lookupTailText).any { it.contains("from math_helpers") })

        val target = zeroLookups.firstOrNull { lookupTailText(it)?.contains("from numbers") == true }
            ?: error("Expected numbers zero lookup, got ${zeroLookups.mapNotNull(::lookupTailText)}")
        myFixture.lookup.currentItem = target
        myFixture.finishLookup('\n')

        val fileText = myFixture.file.text
        assertTrue(fileText, fileText.contains("use numbers.{zero}"))
        assertTrue(fileText, fileText.contains("let qqq = zero"))
    }

    @Test
    fun suggestsAlreadyImportedBareModuleNameInOrdinaryExpressionPosition() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/dict.ak",
            """
            pub fn empty() {
              todo
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use dict

            fn main() {
              let d = di<caret>
            }
            """.trimIndent()
        )

        assertCompletionContainsOrAutoInserted("dict", "let d = dict")
    }

    @Test
    fun onlySuggestsQualifiedModuleMembersForBareImportedModuleAccess() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/dict.ak",
            """
            pub fn empty() {
              todo
            }

            pub fn insert(key, value, into) {
              into
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "lib/other.ak",
            """
            pub fn noise() {
              todo
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use dict

            fn main() {
              let d = dict.<caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        assertTrue(suggestions.toString(), suggestions.contains("empty"))
        assertTrue(suggestions.toString(), suggestions.contains("insert"))
        assertFalse(suggestions.toString(), suggestions.contains("let"))
        assertFalse(suggestions.toString(), suggestions.contains("noise"))
    }

    @Test
    fun onlySuggestsValueProducingKeywordsInValueExpressionPositions() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            fn main() {
              let inps = <caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("if"))
        assertTrue(suggestions.toString(), suggestions.contains("when"))
        assertTrue(suggestions.toString(), suggestions.contains("fn"))
        assertTrue(suggestions.toString(), suggestions.contains("todo"))
        assertTrue(suggestions.toString(), suggestions.contains("fail"))
        assertTrue(suggestions.toString(), suggestions.contains("True"))
        assertTrue(suggestions.toString(), suggestions.contains("False"))

        val forbidden =
            setOf(
                "let",
                "trace",
                "use",
                "const",
                "expect",
                "test",
                "bench",
                "type",
                "opaque",
                "validator",
                "pub",
                "and",
                "or",
                "as",
                "via",
                "once",
                "else",
                "is"
            )
        for (keyword in forbidden) {
            assertFalse("Did not expect `$keyword` in suggestions: $suggestions", suggestions.contains(keyword))
        }
    }

    @Test
    fun doesNotSuggestGenericKeywordsInsideEmptyListLiteral() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            fn main() {
              let inps = [<caret>]
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val allKeywords = AikenLexing.keywords + setOf("True", "False")
        for (keyword in allKeywords) {
            assertFalse("Did not expect `$keyword` in list suggestions: $suggestions", suggestions.contains(keyword))
        }
    }

    @Test
    fun suggestsAllKeywordsAtStartOfBlockBody() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            validator placeholder {
              mint(_redeemer: Data, _policy_id: PolicyId, _self: Transaction) {
                <caret>
                todo @"mint logic goes here"
              }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val allKeywords = AikenLexing.keywords + setOf("True", "False")
        for (keyword in allKeywords) {
            assertTrue("Expected `$keyword` in block-body suggestions: $suggestions", suggestions.contains(keyword))
        }
    }

    @Test
    fun suggestsConstructibleFormsAfterSelectingSameFileTypeCompletion() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Output {
              Output
            }

            pub type Input {
              output: Output,
              index: Int,
            }

            fn main() {
              let input = Inp<caret>
            }
            """.trimIndent()
        )

        acceptLookupOrAutoInserted("Input", "let input = Input")

        val formSuggestions = completionVariants()
        assertTrue(formSuggestions.toString(), formSuggestions.contains("{}"))
        assertTrue(formSuggestions.toString(), formSuggestions.contains("()"))
        assertTrue(
            "Expected named constructible form before positional form. Suggestions were: $formSuggestions",
            formSuggestions.indexOf("{}") < formSuggestions.indexOf("()")
        )
    }

    @Test
    fun suggestsFieldNamesAfterChoosingNamedConstructibleForm() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Output {
              Output
            }

            pub type Input {
              output: Output,
              index: Int,
            }

            fn main() {
              let input = Inp<caret>
            }
            """.trimIndent()
        )

        acceptLookupOrAutoInserted("Input", "let input = Input")

        val formLookups = myFixture.completeBasic() ?: error("Expected constructible form suggestions")
        myFixture.lookup.currentItem = formLookups.firstOrNull { it.lookupString == "{}" } ?: error("Expected {} lookup")
        myFixture.finishLookup('\n')

        assertTrue(myFixture.file.text, myFixture.file.text.contains("let input = Input {}"))

        val fieldSuggestions = completionVariants()
        assertTrue(fieldSuggestions.toString(), fieldSuggestions.contains("output"))
        assertTrue(fieldSuggestions.toString(), fieldSuggestions.contains("index"))
    }

    @Test
    fun suggestsTypedArgumentsAfterChoosingPositionalConstructibleForm() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Output {
              Output
            }

            pub type Input {
              output: Output,
              index: Int,
            }

            fn main(existing_output: Output) {
              let input = Inp<caret>
            }
            """.trimIndent()
        )

        acceptLookupOrAutoInserted("Input", "let input = Input")

        val formLookups = myFixture.completeBasic() ?: error("Expected constructible form suggestions")
        myFixture.lookup.currentItem = formLookups.firstOrNull { it.lookupString == "()" } ?: error("Expected () lookup")
        myFixture.finishLookup('\n')

        assertTrue(myFixture.file.text, myFixture.file.text.contains("let input = Input()"))

        val argumentSuggestions = completionVariants()
        assertTrue(argumentSuggestions.toString(), argumentSuggestions.contains("existing_output"))
        assertTrue(argumentSuggestions.toString(), argumentSuggestions.contains("Output"))
    }

    @Test
    fun prefersScopedBindingsAndConstsBeforeFunctionsForGenericFunctionArguments() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/list.ak",
            """
            pub fn length(items: List<a>) -> Int {
              0
            }

            pub fn filter(items: List<a>, predicate: fn(a) -> Bool) -> List<a> {
              items
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use aiken/list

            pub type Input {
              amount: Int,
            }

            const input_seed: List<Input> = []

            fn input_from_seed() -> List<Input> {
              input_seed
            }

            fn main(input1: Input, input2: Input) {
              let inputs = [input1, input2]
              let q = list.length(inp<caret>)
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("inputs"))
        assertTrue(suggestions.toString(), suggestions.contains("input_seed"))
        assertTrue(suggestions.toString(), suggestions.contains("input_from_seed"))
        assertTrue(suggestions.toString(), suggestions.contains("filter"))
        assertTrue(
            "Expected matching binding to outrank typed const. Suggestions were: $suggestions",
            suggestions.indexOf("inputs") in 0 until suggestions.indexOf("input_seed")
        )
        assertTrue(
            "Expected typed const to outrank typed function. Suggestions were: $suggestions",
            suggestions.indexOf("input_seed") in 0 until suggestions.indexOf("input_from_seed")
        )
        assertTrue(
            "Expected scoped function to outrank unrelated unimported module functions. Suggestions were: $suggestions",
            suggestions.indexOf("input_from_seed") in 0 until suggestions.indexOf("filter")
        )
    }

    @Test
    fun doesNotLeakOrdinaryLexicalSuggestionsWhenTypedFunctionArgumentSuggestionsMatchPrefix() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/list.ak",
            """
            pub fn length(items: List<a>) -> Int {
              0
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use aiken/list

            pub type Input {
              amount: Int,
            }

            fn main(input1: Input, input2: Input) {
              let inputs = [input1, input2]
              let input_count = 2
              let input_bytes = "seed"
              let q = list.length(inp<caret>)
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue("Expected typed binding suggestion, got: $suggestions", suggestions.contains("inputs"))
        assertFalse("Unexpected wrong-type lexical leak: $suggestions", suggestions.contains("input_count"))
        assertFalse("Unexpected wrong-type lexical leak: $suggestions", suggestions.contains("input_bytes"))
    }

    @Test
    fun prefersScopedBindingsBeforeImportedModuleFunctionsInQualifiedArgumentCompletion() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/math/rational.ak",
            """
            pub opaque type Rational {
              Rational
            }

            pub const zero: Rational = Rational

            pub fn reduce(value: Rational) -> Rational {
              value
            }

            pub fn ratio(left: Int, right: Int) -> Rational {
              zero
            }

            pub fn pow(base: Rational, exponent: Int) -> Rational {
              base
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use aiken/math/rational

            fn main(fsr: Int) {
              let rat = rational.zero
              let qqq = rational.pow(<caret>, fsr)
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("rat"))
        assertTrue(suggestions.toString(), suggestions.contains("zero"))
        assertTrue(suggestions.toString(), suggestions.contains("reduce"))
        assertTrue(
            "Expected scoped binding to outrank imported module const. Suggestions were: $suggestions",
            suggestions.indexOf("rat") in 0 until suggestions.indexOf("zero")
        )
        assertTrue(
            "Expected scoped binding to outrank imported module functions. Suggestions were: $suggestions",
            suggestions.indexOf("rat") in 0 until suggestions.indexOf("reduce")
        )
    }

    @Test
    fun prefersNearestScopeBindingForTypedSuggestionsInsideWhenBranch() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Input {
              output: Int,
            }

            pub type Transaction {
              inputs: List<Input>,
            }

            fn main(tx: Transaction, input: Input) {
              when tx is {
                Transaction { inputs: [inp] } -> {
                  let Input { output: _ } = <caret>
                }
              }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue("Expected nearest-scope binding in suggestions, got: $suggestions", suggestions.contains("inp"))
        assertTrue("Expected outer binding in suggestions, got: $suggestions", suggestions.contains("input"))
        assertTrue(
            "Expected when-branch binding to outrank outer-scope binding. Suggestions were: $suggestions",
            suggestions.indexOf("inp") in 0 until suggestions.indexOf("input")
        )
    }

    @Test
    fun doesNotSuggestPrivateFunctionsFromOtherModulesInTypedCompletion() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/helpers.ak",
            """
            pub type Box {
              value: Int,
            }

            fn hidden_box() -> Box {
              Box { value: 0 }
            }

            pub fn shown_box() -> Box {
              Box { value: 1 }
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use helpers.{Box}

            fn main() {
              let value: Box = <caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue("Expected public function suggestion, got: $suggestions", suggestions.contains("shown_box"))
        assertFalse("Private function from another module leaked into suggestions: $suggestions", suggestions.contains("hidden_box"))
    }

    @Test
    fun doesNotSuggestPrivateConstsFromOtherModulesInTypedCompletion() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/helpers.ak",
            """
            pub type Box {
              value: Int,
            }

            const hidden_box_seed: Box = Box { value: 0 }
            pub const shown_box_seed: Box = Box { value: 1 }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use helpers.{Box}

            fn main() {
              let value: Box = <caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue("Expected public const suggestion, got: $suggestions", suggestions.contains("shown_box_seed"))
        assertFalse("Private const from another module leaked into suggestions: $suggestions", suggestions.contains("hidden_box_seed"))
    }

    @Test
    fun stillSuggestsPrivateSameFileTopLevelFunctionsInTypedCompletion() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Box {
              value: Int,
            }

            fn hidden_box() -> Box {
              Box { value: 0 }
            }

            fn main() {
              let value: Box = <caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue("Expected same-file private function suggestion, got: $suggestions", suggestions.contains("hidden_box"))
    }

    @Test
    fun prefersExpectPatternBindingBeforeImportedModuleFunctionsInQualifiedArgumentCompletion() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/math/rational.ak",
            """
            pub opaque type Rational {
              Rational
            }

            pub const zero: Rational = Rational

            pub fn new(left: Int, right: Int) -> Option<Rational> {
              Some(zero)
            }

            pub fn reduce(value: Rational) -> Rational {
              value
            }

            pub fn pow(base: Rational, exponent: Int) -> Rational {
              base
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use aiken/math/rational

            fn main(fsr: Int) {
              expect Some(rat) = rational.new(12, 12)
              let qqq = rational.pow(<caret>, fsr)
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("rat"))
        assertTrue(suggestions.toString(), suggestions.contains("zero"))
        assertTrue(suggestions.toString(), suggestions.contains("reduce"))
        assertTrue(
            "Expected expect-pattern binding to outrank imported module const. Suggestions were: $suggestions",
            suggestions.indexOf("rat") in 0 until suggestions.indexOf("zero")
        )
        assertTrue(
            "Expected expect-pattern binding to outrank imported module functions. Suggestions were: $suggestions",
            suggestions.indexOf("rat") in 0 until suggestions.indexOf("reduce")
        )
    }

    @Test
    fun suggestsBindingsFromRecordDestructuringExpectPatternInTypedArguments() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type User {
              User {
                age: Int,
                score: Int,
              }
            }

            fn load_user() -> User {
              User { age: 21, score: 100 }
            }

            fn consume_int(value: Int) -> Int {
              value
            }

            fn main() {
              expect User { age, score: _ } = load_user()
              let qqq = consume_int(<caret>)
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("age"))
    }

    @Test
    fun suggestsBindingsFromPositionalDestructuringExpectPatternInTypedArguments() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type PairInt {
              PairInt(Int, Int)
            }

            fn load_pair() -> PairInt {
              PairInt(1, 2)
            }

            fn consume_int(value: Int) -> Int {
              value
            }

            fn main() {
              expect PairInt(left, _) = load_pair()
              let qqq = consume_int(<caret>)
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("left"))
    }

    @Test
    fun suggestsBindingsFromListDestructuringExpectPatternInTypedArguments() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            fn consume_list(values: List<Int>) -> Int {
              0
            }

            fn main() {
              let values = [1, 2, 3]
              expect [head, ..tail] = values
              let qqq = consume_list(<caret>)
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("tail"))
    }

    @Test
    fun suggestsBindingsFromAsCaptureInExpectPatternInTypedArguments() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type PairInt {
              PairInt(Int, Int)
            }

            fn load_pair() -> PairInt {
              PairInt(1, 2)
            }

            fn consume_pair(value: PairInt) -> Int {
              0
            }

            fn main() {
              expect PairInt(_, _) as pair = load_pair()
              let qqq = consume_pair(<caret>)
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("pair"))
    }

    @Test
    fun suggestsBindingsFromDestructuredFunctionHeadParametersInTypedArguments() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Foo {
              Foo {
                a1: Int,
                a2: ByteArray,
              }
            }

            fn consume_int(value: Int) -> Int {
              value
            }

            fn main(Foo { a1, .. }: Foo) -> Int {
              consume_int(<caret>)
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("a1"))
    }

    @Test
    fun suggestsBindingsFromDestructuredLambdaHeadParametersInTypedArguments() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Foo {
              Foo {
                a1: Int,
                a2: ByteArray,
              }
            }

            fn consume_int(value: Int) -> Int {
              value
            }

            fn apply(value: Foo, mapper: fn(Foo) -> Int) -> Int {
              mapper(value)
            }

            fn main(value: Foo) -> Int {
              apply(value, fn(Foo { a1, .. }) { consume_int(<caret>) })
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("a1"))
    }

    @Test
    fun suggestsBindingsFromViaFuzzerTestParametersInTypedArguments() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/fuzz.ak",
            """
            pub fn int() -> Fuzzer<Int> {
              todo
            }

            pub fn bytearray() -> Fuzzer<ByteArray> {
              todo
            }

            pub fn both(left: Fuzzer<Int>, right: Fuzzer<ByteArray>) -> Fuzzer<(Int, ByteArray)> {
              todo
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use fuzz

            fn consume_int(value: Int) -> Int {
              value
            }

            test example_5((a, b) via fuzz.both(fuzz.int(), fuzz.bytearray())) {
              consume_int(<caret>)
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("a"))
        assertFalse(suggestions.toString(), suggestions.contains("b"))
    }

    @Test
    fun suggestsBindingsFromDestructuredValidatorHeadParametersInTypedArguments() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Foo {
              Foo {
                a0: Int,
                a1: ByteArray,
              }
            }

            fn consume_int(value: Int) -> Int {
              value
            }

            validator foo_3(Foo { a0, .. }: Foo) {
              mint(_redeemer: Data, _policy_id: PolicyId, _self: Transaction) {
                consume_int(<caret>)
              }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("a0"))
    }

    @Test
    fun suggestsLambdaParameterInsideBareExpectAssertion() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            use aiken/collection/list
            use cardano/transaction.{Input}

            fn consume_input(value: Input) -> Bool {
              True
            }

            fn main(inputs: List<Input>) -> Bool {
              expect list.any(inputs, fn(input) {
                consume_input(<caret>)
              })

              True
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("input"))
    }

    @Test
    fun doesNotLeakBindingsAfterBareExpectAssertion() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/list.ak",
            """
            pub fn any(values: List<Int>, predicate: fn(Int) -> Bool) -> Bool {
              True
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use list

            fn consume_int(value: Int) -> Int {
              value
            }

            fn main(values: List<Int>) -> Int {
              expect list.any(values, fn(value) { value > 0 })

              let answer = 1
              consume_int(<caret>)
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("answer"))
        assertFalse("Bare expect lambda binding leaked into outer scope: $suggestions", suggestions.contains("value"))
    }

    @Test
    fun suggestsOrdinaryCompletionOnRightHandSideOfMonadicLetBind() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            test example_0() {
              let roll <- make_<caret>
              True
            }

            fn make_roll() -> Fuzzer<Int> {
              todo
            }
            """.trimIndent()
        )

        assertCompletionContainsOrAutoInserted("make_roll", "let roll <- make_roll")
    }

    @Test
    fun suggestsBoundNameAfterMonadicLetBindInTypedArguments() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/fuzz.ak",
            """
            pub fn byte() -> Fuzzer<Int> {
              todo
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use fuzz

            fn consume_int(value: Int) -> Int {
              value
            }

            test example_1() {
              let roll <- fuzz.byte()
              consume_int(<caret>)
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("roll"))
    }

    @Test
    fun suggestsTupleBindingsAfterMonadicLetBindInTypedArguments() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/fuzz.ak",
            """
            pub fn int() -> Fuzzer<Int> {
              todo
            }

            pub fn bytearray() -> Fuzzer<ByteArray> {
              todo
            }

            pub fn both(left: Fuzzer<Int>, right: Fuzzer<ByteArray>) -> Fuzzer<(Int, ByteArray)> {
              todo
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use fuzz

            fn consume_int(value: Int) -> Int {
              value
            }

            test example_2() {
              let a, b <- fuzz.both(fuzz.int(), fuzz.bytearray())
              consume_int(<caret>)
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("a"))
        assertFalse("Expected tuple-style monadic bind to preserve element types: $suggestions", suggestions.contains("b"))
    }

    @Test
    fun suggestsDestructuredAndCapturedBindingsFromRecordAsPatternInExpect() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Foo {
              Foo {
                a0: Int,
                a1: Int,
              }
            }

            fn load_foo() -> Foo {
              Foo { a0: 1, a1: 2 }
            }

            fn consume_int(value: Int) -> Int {
              value
            }

            fn main() {
              expect Foo { a0, a1 } as foo = load_foo()
              let q = consume_int(<caret>)
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("a0"))
        assertTrue(suggestions.toString(), suggestions.contains("a1"))
        assertFalse(suggestions.toString(), suggestions.contains("foo"))
    }

    @Test
    fun suggestsCapturedBindingFromListAsPatternInWhenArm() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type MaybeInt {
              Some(Int)
              None
            }

            fn load_values() -> List<MaybeInt> {
              [Some(1)]
            }

            fn consume_maybe(value: MaybeInt) -> Int {
              0
            }

            fn main() {
              when load_values() is {
                [Some(_) as result, ..] -> {
                  let q = consume_maybe(<caret>)
                  q
                }
                _ -> 0
              }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("result"))
    }

    @Test
    fun suggestsDestructuredAndCapturedBindingsFromConstructorAsPatternInWhenArm() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type PairBox {
              PairBox(Int, Int)
            }

            fn load_pair() -> PairBox {
              PairBox(1, 2)
            }

            fn consume_pair(value: PairBox) -> Int {
              0
            }

            fn main() {
              when load_pair() is {
                PairBox(x1, _) as e -> {
                  let q = consume_pair(<caret>)
                  q
                }
                _ -> 0
              }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("e"))
        assertFalse(suggestions.toString(), suggestions.contains("x1"))
    }

    @Test
    fun suggestsBindingsFromWhenPatternInTypedArguments() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type PairInt {
              PairInt(Int, Int)
            }

            fn load_pair() -> PairInt {
              PairInt(1, 2)
            }

            fn consume_int(value: Int) -> Int {
              value
            }

            fn main() {
              when load_pair() is {
                PairInt(left, _) -> {
                  let q = consume_int(<caret>)
                  q
                }
                _ -> 0
              }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("left"))
    }

    @Test
    fun doesNotLeakWhenPatternBindingsAcrossArmsInTypedArguments() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type PairInt {
              PairInt(Int, Int)
            }

            fn load_pair() -> PairInt {
              PairInt(1, 2)
            }

            fn consume_int(value: Int) -> Int {
              value
            }

            fn main() {
              when load_pair() is {
                PairInt(left, _) -> left
                PairInt(_, right) -> consume_int(<caret>)
              }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("right"))
        assertFalse(suggestions.toString(), suggestions.contains("left"))
    }

    @Test
    fun suggestsListSpreadBindingsFromWhenPatternInTypedArguments() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            fn load_values() -> List<Int> {
              [1, 2, 3]
            }

            fn consume_list(values: List<Int>) -> Int {
              0
            }

            fn main() {
              when load_values() is {
                [head, ..tail] -> {
                  let q = consume_list(<caret>)
                  q
                }
                _ -> 0
              }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("tail"))
        assertFalse(suggestions.toString(), suggestions.contains("head"))
    }

    @Test
    fun suggestsTupleBindingsNestedInsideWhenPatternInTypedArguments() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            fn load_value() -> Option<(Int, ByteArray)> {
              Some((1, "abc"))
            }

            fn consume_bytes(value: ByteArray) -> Int {
              0
            }

            fn main() {
              when load_value() is {
                Some((s1, a)) -> {
                  let q = consume_bytes(<caret>)
                  q
                }
                _ -> 0
              }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("a"))
        assertFalse(suggestions.toString(), suggestions.contains("s1"))
    }

    @Test
    fun suggestsNamedRecordBindingsNestedInsideWhenPatternInTypedArguments() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type VoteAction {
              CreateVoteBatch { id: ByteArray, count: Int }
            }

            fn load_action() -> VoteAction {
              CreateVoteBatch { id: "abc", count: 1 }
            }

            fn consume_bytes(value: ByteArray) -> Int {
              0
            }

            fn main() {
              when load_action() is {
                CreateVoteBatch { id } -> {
                  let q = consume_bytes(<caret>)
                  q
                }
              }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("id"))
    }

    @Test
    fun prefersTypedCandidatesByScopeBeforeImportedAndUnimportedModuleCandidates() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/math/rational.ak",
            """
            pub opaque type Rational {
              Rational
            }

            pub const zero: Rational = Rational

            pub fn reduce(value: Rational) -> Rational {
              value
            }

            pub fn pow(base: Rational, exponent: Int) -> Rational {
              base
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "lib/demo/backup.ak",
            """
            use aiken/math/rational.{Rational, zero}

            pub const backup_zero: Rational = zero

            pub fn backup_make() -> Rational {
              zero
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use aiken/math/rational.{Rational, pow, reduce, zero}

            const local_zero: Rational = zero

            fn local_make() -> Rational {
              zero
            }

            fn main(fsr: Int) {
              let rat = zero
              let qqq = pow(<caret>, fsr)
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("rat"))
        assertTrue(suggestions.toString(), suggestions.contains("local_zero"))
        assertTrue(suggestions.toString(), suggestions.contains("local_make"))
        assertTrue(suggestions.toString(), suggestions.contains("zero"))
        assertTrue(suggestions.toString(), suggestions.contains("reduce"))
        assertTrue(suggestions.toString(), suggestions.contains("backup_zero"))
        assertTrue(suggestions.toString(), suggestions.contains("backup_make"))
        assertTrue(
            "Expected local binding to outrank same-file const. Suggestions were: $suggestions",
            suggestions.indexOf("rat") in 0 until suggestions.indexOf("local_zero")
        )
        assertTrue(
            "Expected same-file const to outrank same-file function. Suggestions were: $suggestions",
            suggestions.indexOf("local_zero") in 0 until suggestions.indexOf("local_make")
        )
        assertTrue(
            "Expected same-file function to outrank imported const. Suggestions were: $suggestions",
            suggestions.indexOf("local_make") in 0 until suggestions.indexOf("zero")
        )
        assertTrue(
            "Expected imported const to outrank imported function. Suggestions were: $suggestions",
            suggestions.indexOf("zero") in 0 until suggestions.indexOf("reduce")
        )
        assertTrue(
            "Expected imported function to outrank unimported const. Suggestions were: $suggestions",
            suggestions.indexOf("reduce") in 0 until suggestions.indexOf("backup_zero")
        )
        assertTrue(
            "Expected unimported const to outrank unimported function. Suggestions were: $suggestions",
            suggestions.indexOf("backup_zero") in 0 until suggestions.indexOf("backup_make")
        )
    }

    @Test
    fun prefersScopedBindingBeforeImportedFunctionsWhenArgumentPrefixMatchesBoth() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/math/rational.ak",
            """
            pub opaque type Rational {
              Rational
            }

            pub const zero: Rational = Rational

            pub fn reduce(value: Rational) -> Rational {
              value
            }

            pub fn ratio(left: Int, right: Int) -> Rational {
              zero
            }

            pub fn pow(base: Rational, exponent: Int) -> Rational {
              base
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use aiken/math/rational

            fn main(fsr: Int) {
              let rat = rational.zero
              let qqq = rational.pow(r<caret>, fsr)
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("rat"))
        assertTrue(suggestions.toString(), suggestions.contains("reduce"))
        assertTrue(suggestions.toString(), suggestions.contains("ratio"))
        assertTrue(
            "Expected scoped binding to stay above imported module functions for the same prefix. Suggestions were: $suggestions",
            suggestions.indexOf("rat") in 0 until suggestions.indexOf("reduce")
        )
        assertTrue(
            "Expected scoped binding to stay above imported module functions for the same prefix. Suggestions were: $suggestions",
            suggestions.indexOf("rat") in 0 until suggestions.indexOf("ratio")
        )
    }

    @Test
    fun suggestsPipeFunctionsWhoseFirstParameterMatchesLeftExpressionType() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/list.ak",
            """
            pub fn length(items: List<a>) -> Int {
              0
            }

            pub fn filter(items: List<a>, predicate: fn(a) -> Bool) -> List<a> {
              items
            }

            pub fn reverse(items: List<a>) -> List<a> {
              items
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use aiken/list

            pub type Input {
              amount: Int,
            }

            fn keep_first(items: List<Input>) -> Input {
              todo
            }

            fn unrelated(value: Int) -> Int {
              value
            }

            fn main(input1: Input, input2: Input) {
              let inputs = [input1, input2]
              let q = inputs |> <caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        assertTrue(suggestions.toString(), suggestions.contains("keep_first"))
        assertTrue(suggestions.toString(), suggestions.contains("length"))
        assertTrue(suggestions.toString(), suggestions.contains("filter"))
        assertFalse(suggestions.toString(), suggestions.contains("list.length"))
        assertFalse(suggestions.toString(), suggestions.contains("list.filter"))
        assertFalse(suggestions.toString(), suggestions.contains("unrelated"))
        assertFalse(suggestions.toString(), suggestions.contains("let"))
    }

    @Test
    fun suggestsOnlyUnqualifiedPipeCallsUntilQualifierIsExplicitlyTyped() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/list.ak",
            """
            pub fn reduce(items: List<Int>) -> Int {
              0
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use aiken/list

            fn main(inputs: List<Int>) {
              let len2: Int = inputs |> red<caret>
            }
            """.trimIndent()
        )

        val suggestions = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        if (suggestions.isNotEmpty()) {
            assertTrue(suggestions.toString(), suggestions.contains("reduce"))
            assertFalse(suggestions.toString(), suggestions.contains("list.reduce"))
            val reduceLookups = (myFixture.lookupElements ?: error("Expected completion items")).filter { it.lookupString == "reduce" }
            val target = reduceLookups.firstOrNull { lookupTailText(it)?.contains("from aiken/list") == true }
                ?: error("Expected auto-import reduce lookup, got ${reduceLookups.mapNotNull(::lookupTailText)}")
            myFixture.lookup.currentItem = target
            myFixture.finishLookup('\n')
        } else {
            assertTrue(myFixture.file.text, myFixture.file.text.contains("let len2: Int = inputs |> reduce"))
            assertFalse(myFixture.file.text, myFixture.file.text.contains("let len2: Int = inputs |> list.reduce"))
        }

        val fileText = myFixture.file.text
        assertTrue(fileText, fileText.contains("use aiken/list.{reduce}"))
        assertTrue(fileText, fileText.contains("let len2: Int = inputs |> reduce"))
    }

    @Test
    fun keepsImportedModuleSuggestionInTypedBindingInitializerWhenPrefixMatchesModuleName() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/list.ak",
            """
            pub fn map(items: List<a>, f: fn(a) -> b) -> List<b> {
              []
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use aiken/list

            fn main() {
              let len2: List<Bool> = lis<caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        if (suggestions.isNotEmpty()) {
            assertTrue(suggestions.toString(), suggestions.contains("list"))
            val listIndex = suggestions.indexOf("list")
            val reduceIndex = suggestions.indexOf("reduce")
            if (reduceIndex >= 0) {
                assertTrue(
                    "Expected module suggestion to outrank non-matching typed entries for prefix 'lis'. Suggestions were: $suggestions",
                    listIndex in 0 until reduceIndex
                )
            }
        } else {
            assertTrue(
                myFixture.file.text,
                myFixture.file.text.contains("let len2: List<Bool> = list")
            )
        }
    }

    @Test
    fun onlySuggestsMatchingQualifiedModuleMembersAfterPipe() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/list.ak",
            """
            pub fn length(items: List<a>) -> Int {
              0
            }

            pub fn filter(items: List<a>, predicate: fn(a) -> Bool) -> List<a> {
              items
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "lib/helpers.ak",
            """
            pub fn noise(value: Int) -> Int {
              value
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use aiken/list
            use helpers

            pub type Input {
              amount: Int,
            }

            fn main(input1: Input, input2: Input) {
              let inputs = [input1, input2]
              let q = inputs |> list.<caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        assertTrue(suggestions.toString(), suggestions.contains("length"))
        assertTrue(suggestions.toString(), suggestions.contains("filter"))
        assertFalse(suggestions.toString(), suggestions.contains("noise"))
        assertFalse(suggestions.toString(), suggestions.contains("let"))
    }

    @Test
    fun autoImportsConstructibleBeforeOfferingInvocationForms() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/models.ak",
            """
            pub type Output {
              Output
            }

            pub type Input {
              output: Output,
              index: Int,
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            fn main() {
              let input = Inp<caret>
            }
            """.trimIndent()
        )

        acceptLookupOrAutoInserted("Input", "let input = Input")

        val fileTextAfterImport = myFixture.file.text
        assertTrue(fileTextAfterImport, fileTextAfterImport.startsWith("use models.{Input}\n"))
        assertEquals(
            fileTextAfterImport,
            fileTextAfterImport.indexOf("let input = Input") + "let input = Input".length,
            myFixture.editor.caretModel.offset
        )

        val formSuggestions = completionVariants()
        assertTrue(formSuggestions.toString(), formSuggestions.contains("{}"))
        assertTrue(formSuggestions.toString(), formSuggestions.contains("()"))
    }

    @Test
    fun suggestsOnlyPositionalFormForPositionalOnlyConstructors() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Pair {
              Pair(Int, Int)
            }

            fn main() {
              let pair = Pa<caret>
            }
            """.trimIndent()
        )

        acceptLookupOrAutoInserted("Pair", "let pair = Pair")

        val formSuggestions = completionVariants()
        assertFalse(formSuggestions.toString(), formSuggestions.contains("{}"))
        assertTrue(formSuggestions.toString(), formSuggestions.contains("()"))
    }

    @Test
    fun completesSameFileTypeInTypedPosition() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Helper {
              Helper
            }

            fn main(seed: H<caret>) -> Helper {
              seed
            }
            """.trimIndent()
        )

        assertCompletionContainsOrAutoInserted(
            expectedLookup = "Helper",
            expectedInsertedSnippet = "fn main(seed: Helper) -> Helper {"
        )
    }

    @Test
    fun semanticVariantsIncludeSameFileTypeInTypedPosition() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val file =
            myFixture.configureByText(
                "main.ak",
                """
                pub type Helper {
                  Helper
                }

                fn main(seed: H<caret>) -> Helper {
                  seed
                }
                """.trimIndent()
            )

        val anchor = findElementAtCaret(file) ?: error("Anchor at caret not found")
        val variants =
            AikenReferenceVariants.forElement(anchor)
                .mapNotNull { it as? com.intellij.codeInsight.lookup.LookupElement }
                .map { it.lookupString }

        assertTrue(variants.toString(), variants.contains("Helper"))
    }

    @Test
    fun suggestsBuiltInTypeInVariableAndConstAnnotations() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            const always: Bo<caret> = True

            fn main() {
              let len2: In = 0
            }
            """.trimIndent()
        )

        assertCompletionContainsOrAutoInserted("Bool", "const always: Bool = True")

        myFixture.configureByText(
            "main.ak",
            """
            const always: Bool = True

            fn main() {
              let len2: In<caret> = 0
            }
            """.trimIndent()
        )

        assertCompletionContainsOrAutoInserted("Int", "let len2: Int = 0")
    }

    @Test
    fun suggestsBuiltInTypesInFunctionArgumentAnnotations() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            fn main(payload: By<caret>) -> Void {
              Void
            }
            """.trimIndent()
        )

        assertCompletionContainsOrAutoInserted("ByteArray", "fn main(payload: ByteArray) -> Void {")
    }

    @Test
    fun suggestsBuiltInTypesInConstructorFieldTypeAnnotations() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Boxed {
              Boxed {
                size: In<caret>,
              }
            }
            """.trimIndent()
        )

        assertCompletionContainsOrAutoInserted("Int", "size: Int")
    }

    @Test
    fun suggestsBuiltInTypesInSoftCastIfIs() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            fn main(value: Data) -> Int {
              if value is By<caret> {
                1
              } else {
                0
              }
            }
            """.trimIndent()
        )

        assertCompletionContainsOrAutoInserted("ByteArray", "if value is ByteArray {")
    }

    @Test
    fun narrowsVisibleBindingTypeInsideSoftCastIfThenBranch() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            fn main(value: Data) -> Int {
              if value is ByteArray {
                let narrowed: ByteArray = <caret>
                0
              } else {
                0
              }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue("Expected narrowed soft-cast binding, got: $suggestions", suggestions.contains("value"))
    }

    @Test
    fun doesNotNarrowVisibleBindingTypeInsideSoftCastIfElseBranch() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            fn main(value: Data) -> Int {
              if value is ByteArray {
                0
              } else {
                let narrowed: ByteArray = <caret>
                0
              }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertFalse("Unexpected else-branch soft-cast narrowing: $suggestions", suggestions.contains("value"))
    }

    @Test
    fun completesCallableLocalBindingInCallPosition() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            fn has_nft(value: Int, policy_id: ByteArray, asset_name: ByteArray) -> Bool {
              True
            }

            fn has_nft_strict(value: Int, policy_id: ByteArray, asset_name: ByteArray) -> Bool {
              False
            }

            fn main(strict_mode: Bool, value: Int, policy_id: ByteArray, asset_name: ByteArray) -> List<Int> {
              let check_function =
                when strict_mode is {
                  True -> has_nft_strict
                  False -> has_nft
                }

              when check_f<caret>(value, policy_id, asset_name) is {
                True -> [1]
                False -> []
              }
            }
            """.trimIndent()
        )

        assertCompletionContainsOrAutoInserted(
            expectedLookup = "check_function",
            expectedInsertedSnippet = "when check_function(value, policy_id, asset_name) is {"
        )
    }

    @Test
    fun suggestsUnimportedCrossFileFunctionForSingleCharacterPrefix() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/math.ak",
            """
            pub fn helper(seed: Int) -> Int {
              seed
            }
            """.trimIndent()
        )
        val mainFile =
            myFixture.addFileToProject(
                "lib/main.ak",
                """
                fn main(seed: Int) -> Int {
                  h
                }
                """.trimIndent()
            )
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("\n  h") + 4)

        val suggestions = completionVariants()

        if (!suggestions.contains("helper")) {
            val autoInserted = myFixture.file.text
            assertTrue(autoInserted, autoInserted.contains("use math.{helper}"))
            assertTrue(autoInserted, autoInserted.contains("\n  helper"))
            return
        }

        assertTrue(suggestions.toString(), suggestions.contains("helper"))
    }

    @Test
    fun suggestsQualifiedExportsThroughModuleAliasForSingleCharacterPrefix() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/math.ak",
            """
            pub fn add(left: Int, right: Int) -> Int {
              left
            }

            pub fn sub(left: Int, right: Int) -> Int {
              right
            }
            """.trimIndent()
        )
        val mainFile =
            myFixture.addFileToProject(
                "lib/main.ak",
                """
                use math as math_utils

                fn main(seed: Int) -> Int {
                  math_utils.a
                }
                """.trimIndent()
            )
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("math_utils.a") + "math_utils.a".length)

        assertCompletionContainsOrAutoInserted("add", "math_utils.add")
    }

    @Test
    fun suggestsQualifiedMembersThroughMixedImportModuleAlias() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/collection/list.ak",
            """
            pub fn count(items: List<a>) -> Int {
              0
            }

            pub fn map(items: List<a>, with: fn(a) -> b) -> List<b> {
              []
            }
            """.trimIndent()
        )
        val mainFile =
            myFixture.addFileToProject(
                "lib/main.ak",
                """
                use aiken/collection/list.{count as my_count} as native_list

                fn main() {
                  native_list.co
                }
                """.trimIndent()
            )
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)
        myFixture.editor.caretModel.moveToOffset(
            myFixture.file.text.indexOf("native_list.co") + "native_list.co".length
        )

        assertCompletionContainsOrAutoInserted("count", "native_list.count")
    }

    @Test
    fun suggestsQualifiedTypesThroughMixedImportModuleAliasInTypePosition() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/transaction.ak",
            """
            pub type OutputReference {
              OutputReference
            }

            pub type Input {
              output_reference: OutputReference,
            }

            pub type Transaction {
              inputs: List<Input>,
            }
            """.trimIndent()
        )
        val mainFile =
            myFixture.addFileToProject(
                "lib/main.ak",
                """
                use cardano/transaction.{Input, OutputReference, Transaction} as tx

                fn main(value: tx.Tr) {
                  value
                }
                """.trimIndent()
            )
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)
        myFixture.editor.caretModel.moveToOffset(
            myFixture.file.text.indexOf("tx.Tr") + "tx.Tr".length
        )

        assertCompletionContainsOrAutoInserted("Transaction", "fn main(value: tx.Transaction)")
    }

    @Test
    fun suggestsConstructorAliasImportInValuePosition() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/address.ak",
            """
            pub type Credential {
              VerificationKey(ByteArray)
              Script(ByteArray)
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use cardano/address.{VerificationKey as VerificationKeyConstructor}

            fn main() {
              let credential = VerificationKeyC<caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        if (!suggestions.contains("VerificationKeyConstructor")) {
            val fileText = myFixture.file.text
            assertTrue(fileText, fileText.contains("VerificationKeyConstructor"))
            return
        }

        assertTrue(suggestions.toString(), suggestions.contains("VerificationKeyConstructor"))
    }

    @Test
    fun suggestsConstructorAliasImportInPatternPosition() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/address.ak",
            """
            pub type Credential {
              VerificationKey(ByteArray)
              Script(ByteArray)
            }

            pub type Address {
              payment_credential: Credential,
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use cardano/address.{Address, VerificationKey as VerificationKeyConstructor}

            fn main(address: Address) {
              when address.payment_credential is {
                VerificationKeyC<caret>(thing) -> thing
                _ -> ""
              }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        if (!suggestions.contains("VerificationKeyConstructor")) {
            val fileText = myFixture.file.text
            assertTrue(fileText, fileText.contains("VerificationKeyConstructor"))
            return
        }

        assertTrue(suggestions.toString(), suggestions.contains("VerificationKeyConstructor"))
    }

    @Test
    fun suggestsValidatorHandlerNamesForValidatorNamespaceQualifier() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            validator foo_3 {
              mint(redeemer: Int, policy_id: ByteArray, self: ByteArray) {
                True
              }

              else(_) {
                fail
              }
            }

            fn main() {
              foo_3.mi<caret>
            }
            """.trimIndent()
        )

        assertCompletionContainsOrAutoInserted("mint", "foo_3.mint")
    }

    @Test
    fun suggestsTypedArgumentsForValidatorNamespaceCallFromCombinedValidatorAndHandlerSignature() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type OutputReference {
              OutputReference
            }

            pub type Transaction {
              Transaction
            }

            validator simple_oneshot(utxo_ref: OutputReference) {
              mint(_redeemer: Int, _policy_id: ByteArray, self: Transaction) {
                True
              }
            }

            fn main(utxo: OutputReference, tx: Transaction) {
              simple_oneshot.mint(<caret>, 0, "", tx)
            }
            """.trimIndent()
        )

        val firstArgSuggestions = completionVariants()

        assertTrue(firstArgSuggestions.toString(), firstArgSuggestions.contains("utxo"))
        assertFalse(firstArgSuggestions.toString(), firstArgSuggestions.contains("tx"))

        myFixture.configureByText(
            "main.ak",
            """
            pub type OutputReference {
              OutputReference
            }

            pub type Transaction {
              Transaction
            }

            validator simple_oneshot(utxo_ref: OutputReference) {
              mint(_redeemer: Int, _policy_id: ByteArray, self: Transaction) {
                True
              }
            }

            fn main(utxo: OutputReference, tx: Transaction) {
              simple_oneshot.mint(utxo, 0, "", <caret>)
            }
            """.trimIndent()
        )

        val lastArgSuggestions = completionVariants()

        assertTrue(lastArgSuggestions.toString(), lastArgSuggestions.contains("tx"))
        assertFalse(lastArgSuggestions.toString(), lastArgSuggestions.contains("utxo"))
    }

    @Test
    fun keepsRecordFieldValueCompletionWorkingWhenSiblingFieldUsesHoleSyntax() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Foo {
              i: Int,
              b: Bool,
            }

            fn main() {
              let builder = Foo { i: _, b: Tr<caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("True"))
        assertFalse(suggestions.toString(), suggestions.contains("0"))
    }

    @Test
    fun keepsPositionalConstructorArgumentCompletionWorkingAfterHoleSyntax() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Foo {
              Foo(Int, Bool)
            }

            fn main() {
              let builder = Foo(_, Tr<caret>)
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("True"))
        assertFalse(suggestions.toString(), suggestions.contains("0"))
    }

    @Test
    fun suggestsCallableConstBuiltFromHoleConstructorByAnnotatedFunctionType() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Foo {
              i: Int,
              b: Bool,
            }

            const give_i: fn(Int) -> Foo = Foo { i: _, b: True }

            fn apply(builder: fn(Int) -> Foo) -> Foo {
              builder(1)
            }

            fn main() -> Foo {
              apply(<caret>)
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("give_i"))
    }

    @Test
    fun completesImportedRecordTypeFieldsWithFieldTypeHint() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/models.ak",
            """
            pub type Credential {
              VerificationKey
              Script(ByteArray)
            }

            pub type Transaction {
              id: ByteArray,
              credential: Credential,
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use models.{Transaction}

            fn main() {
              let tx = Transaction { c<caret> }
            }
            """.trimIndent()
        )

        assertTrue(AikenRecordCompletionSupport.isRecordFieldNameContext(myFixture.file.text, myFixture.caretOffset))
        val directRecordSuggestions =
            AikenRecordCompletionSupport.recordSpecificVariants(
                myFixture.file.findElementAt((myFixture.caretOffset - 1).coerceAtLeast(0)),
                myFixture.caretOffset
            )
        assertNotNull(directRecordSuggestions)
        assertTrue(directRecordSuggestions!!.lookups.map { it.lookupString }.toString(), directRecordSuggestions.lookups.any { it.lookupString == "credential" })
        val directPresentationByLookup =
            directRecordSuggestions.lookups.associate { element ->
                val presentation = LookupElementPresentation()
                element.renderElement(presentation)
                element.lookupString to presentation.typeText
            }
        assertEquals("Credential", directPresentationByLookup["credential"])

        val suggestions = completionVariants()
        if (suggestions.contains("credential")) {
            val presentationByLookup = completionPresentations().associate { (lookup, typeText) -> lookup to typeText }
            assertEquals("Credential", presentationByLookup["credential"])

            val elements = myFixture.lookupElements ?: error("Expected completion items")
            val target = elements.firstOrNull { it.lookupString == "credential" } ?: error("Expected credential lookup")
            myFixture.lookup.currentItem = target
            myFixture.finishLookup('\n')
        } else {
            assertTrue(
                "Expected popup suggestion or auto-inserted field. Suggestions were: $suggestions. File text was:\n${myFixture.file.text}",
                myFixture.file.text.contains("let tx = Transaction { credential: ")
            )
        }

        myFixture.checkResult(
            """
            use models.{Transaction}

            fn main() {
              let tx = Transaction { credential:  }
            }
            """.trimIndent()
        )
    }

    @Test
    fun fieldInsertionLeavesCaretReadyForTypedValueCompletion() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/models.ak",
            """
            pub type Credential {
              VerificationKey
              Script(ByteArray)
            }

            pub type Transaction {
              certificates: List<Credential>,
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use models.{Transaction}

            fn main() {
              let tx = Transaction { c<caret> }
            }
            """.trimIndent()
        )

        val initialSuggestions = completionVariants()
        if (initialSuggestions.contains("certificates")) {
            val elements = myFixture.lookupElements ?: error("Expected completion items")
            val target = elements.firstOrNull { it.lookupString == "certificates" } ?: error("Expected certificates lookup")
            myFixture.lookup.currentItem = target
            myFixture.finishLookup('\n')
        } else {
            assertTrue(
                "Expected popup suggestion or auto-inserted field. Suggestions were: $initialSuggestions. File text was:\n${myFixture.file.text}",
                myFixture.file.text.contains("let tx = Transaction { certificates: ")
            )
        }

        myFixture.checkResult(
            """
            use models.{Transaction}

            fn main() {
              let tx = Transaction { certificates:  }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        assertTrue(suggestions.toString(), suggestions.contains("[]"))
    }

    @Test
    fun prefersFieldsOfRequiredTypeBeforeConstructorsInRecordValuePosition() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Credential {
              VerificationKey
              Script(ByteArray)
            }

            pub type Qwe {
              Qwe1 { primary: Credential, backup: Credential }
            }

            fn main() {
              let q = Qwe1 { primary: b<caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val presentationByLookup = completionPresentations().associate { (lookup, typeText) -> lookup to typeText }

        assertTrue(suggestions.toString(), suggestions.contains("backup"))
        assertTrue(suggestions.toString(), suggestions.contains("VerificationKey"))
        assertTrue(suggestions.toString(), suggestions.contains("Script"))
        assertEquals("Credential", presentationByLookup["backup"])
        assertEquals("Credential", presentationByLookup["VerificationKey"])
        assertTrue(
            "Expected matching field to outrank constructors. Suggestions were: $suggestions",
            suggestions.indexOf("backup") in 0 until suggestions.indexOf("VerificationKey")
        )
    }

    @Test
    fun completesFieldsForInlineConstructorDefinitions() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Credential {
              VerificationKey
              Script(ByteArray)
            }

            pub type Qwe { Qwe1 { f1: Int, f2: Credential } Qwe2 { qqq: Bool, fdas: ByteArray } }

            fn main() {
              let q = Qwe1 { f<caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val presentationByLookup = completionPresentations().associate { (lookup, typeText) -> lookup to typeText }

        assertTrue(suggestions.toString(), suggestions.contains("f1"))
        assertTrue(suggestions.toString(), suggestions.contains("f2"))
        assertEquals("Int", presentationByLookup["f1"])
        assertEquals("Credential", presentationByLookup["f2"])
    }

    @Test
    fun completesFieldsInsideLetRecordDestructuringPattern() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Credential {
              VerificationKey
              Script(ByteArray)
            }

            pub type Transaction {
              credential: Credential,
              reference_inputs: List<Int>,
            }

            fn main(tx: Transaction) {
              let Transaction { c<caret> } = tx
            }
            """.trimIndent()
        )

        assertTrue(AikenRecordCompletionSupport.isRecordFieldNameContext(myFixture.file.text, myFixture.caretOffset))
        val suggestions = completionVariants()
        assertTrue(suggestions.toString(), suggestions.contains("credential"))
    }

    @Test
    fun limitsDefaultSuggestionsInRecordPatternFieldValueToFieldBindingAndConstructors() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Credential {
              VerificationKey
              Script(ByteArray)
            }

            pub type Transaction {
              credential: Credential,
            }

            fn helper() -> Credential {
              VerificationKey
            }

            fn main(tx: Transaction) {
              let Transaction { credential: <caret> } = tx
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("credential"))
        assertTrue(suggestions.toString(), suggestions.contains("VerificationKey"))
        assertTrue(suggestions.toString(), suggestions.contains("Script"))
        assertFalse(suggestions.toString(), suggestions.contains("tx"))
        assertFalse(suggestions.toString(), suggestions.contains("helper"))
    }

    @Test
    fun keepsOnlyTypedMatchesInRecordPatternFieldValueWithPrefix() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Input {
              id: Int,
            }

            pub type Transaction {
              inputs: List<Input>,
            }

            fn main(tx: Transaction) {
              let inputs: List<Input> = []
              let input: Input = Input { id: 1 }
              let inp: Int = 42
              let rat: ByteArray = #"00"
              let fsr: Int = 1

              let Transaction { inputs: inp<caret> } = tx
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue("Expected typed list binding, got: $suggestions", suggestions.contains("inputs"))
        assertFalse("Unexpected non-matching Input binding leakage: $suggestions", suggestions.contains("input"))
        assertFalse("Unexpected non-matching Int binding leakage: $suggestions", suggestions.contains("inp"))
        assertFalse("Unexpected non-matching ByteArray binding leakage: $suggestions", suggestions.contains("rat"))
        assertFalse("Unexpected non-matching Int binding leakage: $suggestions", suggestions.contains("fsr"))
    }

    @Test
    fun suggestsTypedSourcesOnRightHandSideOfDestructuringLet() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Input {
              id: Int,
            }

            pub type Transaction {
              inputs: List<Input>,
            }

            const tx_const: Transaction = Transaction { inputs: [] }

            fn tx_from_fn() -> Transaction {
              tx_const
            }

            fn main() {
              let tx: Transaction = tx_const
              let input: Input = Input { id: 1 }
              let Transaction { inputs: local_inputs } = <caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue("Expected local Transaction binding, got: $suggestions", suggestions.contains("tx"))
        assertTrue("Expected same-file Transaction const, got: $suggestions", suggestions.contains("tx_const"))
        assertTrue("Expected same-file function returning Transaction, got: $suggestions", suggestions.contains("tx_from_fn"))
        assertFalse("Unexpected non-matching Input leakage on rhs: $suggestions", suggestions.contains("input"))
        assertTrue(
            "Expected local binding > const > function ranking. Suggestions were: $suggestions",
            suggestions.indexOf("tx") in 0 until suggestions.indexOf("tx_const") &&
                suggestions.indexOf("tx_const") in 0 until suggestions.indexOf("tx_from_fn")
        )
    }

    @Test
    fun doesNotSuggestRecordSpreadSourcesForTrailingPatternSpread() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Input {
              id: Int,
            }

            pub type Transaction {
              inputs: List<Input>,
              reference_inputs: List<Input>,
            }

            fn main(tx: Transaction) {
              let placeholder = tx
              let Transaction { inputs: inputs, ..<caret> } = tx
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        assertTrue("Trailing pattern spread should not trigger suggestions, got: $suggestions", suggestions.isEmpty())
    }

    @Test
    fun prefersTypedBindingsBeforeConstructorsInFunctionArgumentPosition() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Credential {
              VerificationKey
              Script(ByteArray)
            }

            fn consume(primary: Credential) -> Bool {
              True
            }

            fn main(backup: Credential) -> Bool {
              consume(b<caret>)
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val presentationByLookup = completionPresentations().associate { (lookup, typeText) -> lookup to typeText }

        assertTrue(suggestions.toString(), suggestions.contains("backup"))
        assertTrue(suggestions.toString(), suggestions.contains("VerificationKey"))
        assertTrue(suggestions.toString(), suggestions.contains("Script"))
        assertEquals("Credential", presentationByLookup["backup"])
        assertEquals("Credential", presentationByLookup["VerificationKey"])
        assertTrue(
            "Expected matching binding to outrank constructors. Suggestions were: $suggestions",
            suggestions.indexOf("backup") in 0 until suggestions.indexOf("VerificationKey")
        )
    }

    @Test
    fun suggestsImportedUnannotatedFunctionByInferredReturnType() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/math.ak",
            """
            pub fn choose(flag: Bool, left: Int, right: Int) {
              if flag {
                {
                  left
                }
              } else {
                when flag is {
                  True -> right
                  False -> left
                }
              }
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use math.{choose}

            pub type Container {
              value: Int,
            }

            fn main(flag: Bool, left: Int, right: Int) {
              let selected = Container { value: ch<caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val presentationByLookup = completionPresentations().associate { (lookup, typeText) -> lookup to typeText }

        assertTrue(suggestions.toString(), suggestions.contains("choose"))
        assertEquals("Int", presentationByLookup["choose"])
    }

    @Test
    fun suggestsImportedUnannotatedArithmeticFunctionByInferredReturnType() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/math.ak",
            """
            pub fn sum(left: Int, right: Int) {
              left + right
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use math.{sum}

            pub type Container {
              value: Int,
            }

            fn main(left: Int, right: Int) {
              let selected = Container { value: su<caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val presentationByLookup = completionPresentations().associate { (lookup, typeText) -> lookup to typeText }

        assertTrue(suggestions.toString(), suggestions.contains("sum"))
        assertEquals("Int", presentationByLookup["sum"])
    }

    @Test
    fun suggestsImportedUnannotatedFieldAccessFunctionByInferredReturnType() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/model.ak",
            """
            pub type User {
              age: Int,
            }

            pub fn age_of(user: User) {
              user.age
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use model.{User, age_of}

            pub type Container {
              value: Int,
            }

            fn main(user: User) {
              let selected = Container { value: ag<caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val presentationByLookup = completionPresentations().associate { (lookup, typeText) -> lookup to typeText }

        assertTrue(suggestions.toString(), suggestions.contains("age_of"))
        assertEquals("Int", presentationByLookup["age_of"])
    }

    @Test
    fun suggestsImportedUnannotatedPairFunctionByInferredReturnType() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/tuples.ak",
            """
            pub fn pair_source() {
              Pair(#"", 20)
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use tuples.{pair_source}

            pub type Container {
              value: Pair<ByteArray, Int>,
            }

            fn main() {
              let selected = Container { value: pa<caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val presentationByLookup = completionPresentations().associate { (lookup, typeText) -> lookup to typeText }

        assertTrue(suggestions.toString(), suggestions.contains("pair_source"))
        assertEquals("Pair<ByteArray, Int>", presentationByLookup["pair_source"])
    }

    @Test
    fun suggestsImportedUnannotatedTupleFunctionByInferredReturnType() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/tuples.ak",
            """
            pub fn tuple_source() {
              (1, @"")
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use tuples.{tuple_source}

            pub type Container {
              value: (Int, String),
            }

            fn main() {
              let selected = Container { value: tu<caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val presentationByLookup = completionPresentations().associate { (lookup, typeText) -> lookup to typeText }

        assertTrue(suggestions.toString(), suggestions.contains("tuple_source"))
        assertEquals("(Int, String)", presentationByLookup["tuple_source"])
    }

    @Test
    fun suggestsImportedUnannotatedNestedTupleFunctionByInferredReturnType() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/tuples.ak",
            """
            pub fn nested_source() {
              (1, (1, 1, 1))
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use tuples.{nested_source}

            pub type Container {
              value: (Int, (Int, Int, Int)),
            }

            fn main() {
              let selected = Container { value: ne<caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val presentationByLookup = completionPresentations().associate { (lookup, typeText) -> lookup to typeText }

        assertTrue(suggestions.toString(), suggestions.contains("nested_source"))
        assertEquals("(Int, (Int, Int, Int))", presentationByLookup["nested_source"])
    }

    @Test
    fun suggestsImportedUnannotatedTupleListFunctionByInferredReturnType() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/tuples.ak",
            """
            pub fn entries_source() {
              [(1, @"")]
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use tuples.{entries_source}

            pub type Container {
              value: List<(Int, String)>,
            }

            fn main() {
              let selected = Container { value: en<caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val presentationByLookup = completionPresentations().associate { (lookup, typeText) -> lookup to typeText }

        assertTrue(suggestions.toString(), suggestions.contains("entries_source"))
        assertEquals("List<(Int, String)>", presentationByLookup["entries_source"])
    }

    @Test
    fun suggestsUnannotatedFunctionReturningFunctionValueForFunctionTypedField() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Container {
              transform: fn(Int) -> Int,
            }

            fn increment(value: Int) {
              value + 1
            }

            fn choose_transform() {
              increment
            }

            fn main() {
              let selected = Container { transform: ch<caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val presentationByLookup = completionPresentations().associate { (lookup, typeText) -> lookup to typeText }

        assertTrue(suggestions.toString(), suggestions.contains("choose_transform"))
        assertEquals("fn(Int) -> Int", presentationByLookup["choose_transform"])
    }

    @Test
    fun suggestsQualifiedImportedFunctionValueInOrdinaryValuePosition() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/math.ak",
            """
            pub fn double(value: Int) {
              value + 1
            }
            """.trimIndent()
        )
        val mainFile =
            myFixture.addFileToProject(
                "lib/main.ak",
                """
                use math as ops

                fn main() {
                  let transform = ops.do
                }
                """.trimIndent()
            )
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)
        myFixture.editor.caretModel.moveToOffset(
            myFixture.file.text.indexOf("ops.do") + "ops.do".length
        )
        assertCompletionContainsOrAutoInserted("double", "ops.double")
    }

    @Test
    fun suggestsQualifiedImportedFunctionValueInFunctionTypedField() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/math.ak",
            """
            pub fn double(value: Int) {
              value + 1
            }
            """.trimIndent()
        )
        val mainFile =
            myFixture.addFileToProject(
                "lib/main.ak",
                """
                use math as ops

                pub type Container {
                  transform: fn(Int) -> Int,
                }

                fn main() {
                  let selected = Container { transform: ops.do }
                }
                """.trimIndent()
            )
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)
        myFixture.editor.caretModel.moveToOffset(
            myFixture.file.text.indexOf("ops.do") + "ops.do".length
        )
        assertCompletionContainsOrAutoInserted("double", "ops.double")
    }

    @Test
    fun suggestsListLiteralForRecordFieldExpectingListWhenNoTypedListBindingExists() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Credential {
              VerificationKey
              Script(ByteArray)
            }

            pub type Transaction {
              certificates: List<Credential>,
            }

            fn main() {
              let tx = Transaction { certificates: <caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val presentationByLookup = completionPresentations().associate { (lookup, typeText) -> lookup to typeText }

        assertTrue(suggestions.toString(), suggestions.contains("[]"))
        assertEquals("List<Credential>", presentationByLookup["[]"])
        assertTrue(
            "Expected list literal to outrank generic keyword suggestions. Suggestions were: $suggestions",
            suggestions.indexOf("[]") >= 0 && (suggestions.indexOf("let").let { it < 0 || suggestions.indexOf("[]") < it })
        )
    }

    @Test
    fun stillOffersListLiteralAsFallbackWhenTypedListBindingExists() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Input {
              amount: Int,
            }

            pub type Transaction {
              inputs: List<Input>,
            }

            fn main(input: Input) {
              let remembered = [input]
              let tx = Transaction { inputs: <caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("remembered"))
        assertTrue(suggestions.toString(), suggestions.contains("[]"))
        assertTrue(
            "Expected typed list binding to outrank list literal fallback. Suggestions were: $suggestions",
            suggestions.indexOf("remembered") in 0 until suggestions.indexOf("[]")
        )
    }

    @Test
    fun keepsMatchingBindingWhenItsNameMatchesCurrentRecordField() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Input {
              amount: Int,
            }

            pub type Transaction {
              inputs: List<Input>,
            }

            fn main(input1: Input, input2: Input) {
              let inputs = [input1, input2]
              let tx = Transaction { inputs: <caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("inputs"))
        assertTrue(suggestions.toString(), suggestions.contains("[]"))
        assertTrue(
            "Expected matching binding to outrank list literal fallback. Suggestions were: $suggestions",
            suggestions.indexOf("inputs") in 0 until suggestions.indexOf("[]")
        )
    }

    @Test
    fun suggestsTypedItemsInsideListLiteralForRecordFieldValues() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Credential {
              VerificationKey
              Script(ByteArray)
            }

            pub type Transaction {
              certificates: List<Credential>,
            }

            fn main(backup: Credential) {
              let tx = Transaction { certificates: [b<caret>] }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val presentationByLookup = completionPresentations().associate { (lookup, typeText) -> lookup to typeText }

        assertTrue(suggestions.toString(), suggestions.contains("backup"))
        assertTrue(suggestions.toString(), suggestions.contains("VerificationKey"))
        assertTrue(suggestions.toString(), suggestions.contains("Script"))
        assertEquals("Credential", presentationByLookup["backup"])
        assertEquals("Credential", presentationByLookup["VerificationKey"])
        assertTrue(
            "Expected matching binding to outrank constructors. Suggestions were: $suggestions",
            suggestions.indexOf("backup") in 0 until suggestions.indexOf("VerificationKey")
        )
    }

    @Test
    fun infersPlainListLiteralItemTypeFromExistingElements() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Input {
              amount: Int,
            }

            pub type Output {
              value: Int,
            }

            fn main(input: Input, input2: Input, output: Output) {
              let inps = [input2, input, <caret>]
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("input"))
        assertTrue(suggestions.toString(), suggestions.contains("input2"))
        assertFalse(suggestions.toString(), suggestions.contains("Output"))
        assertFalse(suggestions.toString(), suggestions.contains("let"))
        assertFalse(suggestions.toString(), suggestions.contains("use"))
        assertFalse(suggestions.toString(), suggestions.contains("True"))
        assertFalse(suggestions.toString(), suggestions.contains("False"))
    }

    @Test
    fun infersPlainListSpreadTypeFromExistingElements() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Input {
              amount: Int,
            }

            pub type Output {
              value: Int,
            }

            fn main(input: Input, output: Output) {
              let remembered = [input]
              let other_outputs = [output]
              let inps = [input, ..<caret>]
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("remembered"))
        assertFalse(suggestions.toString(), suggestions.contains("other_outputs"))
        assertFalse(suggestions.toString(), suggestions.contains("input"))
        assertFalse(suggestions.toString(), suggestions.contains("let"))
    }

    @Test
    fun suggestsListLiteralForImportedRecordFieldExpectingList() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/cardano/transaction.ak",
            """
            pub type Output {
              Output
            }

            pub type Transaction {
              outputs: List<Output>,
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use cardano/transaction.{Transaction}

            fn main() {
              let tx = Transaction { outputs: <caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val presentationByLookup = completionPresentations().associate { (lookup, typeText) -> lookup to typeText }

        assertTrue(suggestions.toString(), suggestions.contains("[]"))
        assertEquals("List<Output>", presentationByLookup["[]"])
    }

    @Test
    fun suggestsListLiteralForImportedBuildPackageRecordFieldExpectingList() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/transaction.ak",
            """
            pub type Output {
              Output
            }

            pub type Transaction {
              outputs: List<Output>,
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use cardano/transaction.{Transaction}

            fn main() {
              let tx = Transaction { outputs: <caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val presentationByLookup = completionPresentations().associate { (lookup, typeText) -> lookup to typeText }

        assertTrue(suggestions.toString(), suggestions.contains("[]"))
        assertEquals("List<Output>", presentationByLookup["[]"])
    }

    @Test
    fun suggestsOptionFallbacksBelowTypedBindingsButAboveGenericNoise() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Credential {
              VerificationKey
              Script(ByteArray)
            }

            pub type Transaction {
              certificate: Option<Credential>,
            }

            fn main(existing: Option<Credential>) {
              let tx = Transaction { certificate: <caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val presentationByLookup = completionPresentations().associate { (lookup, typeText) -> lookup to typeText }

        assertTrue(suggestions.toString(), suggestions.contains("existing"))
        assertTrue(suggestions.toString(), suggestions.contains("Some()"))
        assertTrue(suggestions.toString(), suggestions.contains("None"))
        assertEquals("Option<Credential>", presentationByLookup["existing"])
        assertEquals("Option<Credential>", presentationByLookup["Some()"])
        assertEquals("Option<Credential>", presentationByLookup["None"])
        assertTrue(
            "Expected matching Option binding to outrank Option fallbacks. Suggestions were: $suggestions",
            suggestions.indexOf("existing") in 0 until suggestions.indexOf("Some()")
        )
        assertTrue(
            "Expected None to outrank generic keyword suggestions. Suggestions were: $suggestions",
            suggestions.indexOf("None") >= 0 && (suggestions.indexOf("let").let { it < 0 || suggestions.indexOf("None") < it })
        )
    }

    @Test
    fun suggestsTypedItemsInsideOptionSomeForRecordFieldValues() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Credential {
              VerificationKey
              Script(ByteArray)
            }

            pub type Transaction {
              certificate: Option<Credential>,
            }

            fn main(backup: Credential) {
              let tx = Transaction { certificate: Some(b<caret>) }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val presentationByLookup = completionPresentations().associate { (lookup, typeText) -> lookup to typeText }

        assertTrue(suggestions.toString(), suggestions.contains("backup"))
        assertTrue(suggestions.toString(), suggestions.contains("VerificationKey"))
        assertTrue(suggestions.toString(), suggestions.contains("Script"))
        assertEquals("Credential", presentationByLookup["backup"])
        assertEquals("Credential", presentationByLookup["VerificationKey"])
        assertTrue(
            "Expected matching binding to outrank constructors inside Some(...). Suggestions were: $suggestions",
            suggestions.indexOf("backup") in 0 until suggestions.indexOf("VerificationKey")
        )
    }

    @Test
    fun suggestsConstructorsForAliasTypedRecordFieldsAndAutoImportsThem() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/address.ak",
            """
            pub type Credential {
              VerificationKey
              Script(ByteArray)
            }

            pub type PaymentCredential = Credential

            pub type Address {
              payment_credential: PaymentCredential,
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use address.{Address}

            fn main() {
              let address = Address { payment_credential: V<caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val presentationByLookup = completionPresentations().associate { (lookup, typeText) -> lookup to typeText }

        assertTrue(suggestions.toString(), suggestions.contains("VerificationKey"))
        assertTrue(suggestions.toString(), suggestions.contains("Script"))
        assertFalse(suggestions.toString(), suggestions.contains("VerificationKeyHash"))
        assertFalse(suggestions.toString(), suggestions.contains("ScriptHash"))
        assertEquals("PaymentCredential", presentationByLookup["VerificationKey"])

        val elements = myFixture.lookupElements ?: error("Expected completion items")
        val target = elements.firstOrNull { it.lookupString == "VerificationKey" } ?: error("Expected VerificationKey lookup")
        myFixture.lookup.currentItem = target
        myFixture.finishLookup('\n')

        val fileText = myFixture.file.text
        assertTrue(fileText, fileText.contains("use address.{Address}"))
        assertTrue(fileText, fileText.contains("use address.{VerificationKey}"))
        assertTrue(fileText, fileText.contains("payment_credential: VerificationKey"))
    }

    @Test
    fun suggestsConstructorsFromUnimportedModulesWhenReturnTypeMatchesAndAutoImportsThem() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/address.ak",
            """
            pub type Credential {
              VerificationKey
              Script(ByteArray)
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "lib/models.ak",
            """
            use address.{Credential}

            pub type Address {
              payment_credential: Credential,
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use models.{Address}

            fn main() {
              let address = Address { payment_credential: V<caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        assertTrue(suggestions.toString(), suggestions.contains("VerificationKey"))
        assertTrue(suggestions.toString(), suggestions.contains("Script"))
        assertFalse(suggestions.toString(), suggestions.contains("VerificationKeyHash"))
        assertFalse(suggestions.toString(), suggestions.contains("ScriptHash"))

        val elements = myFixture.lookupElements ?: error("Expected completion items")
        val target = elements.firstOrNull { it.lookupString == "VerificationKey" } ?: error("Expected VerificationKey lookup")
        myFixture.lookup.currentItem = target
        myFixture.finishLookup('\n')

        val fileText = myFixture.file.text
        assertTrue(fileText, fileText.contains("use models.{Address}"))
        assertTrue(fileText, fileText.contains("use address.{VerificationKey}"))
        assertTrue(fileText, fileText.contains("payment_credential: VerificationKey"))
    }

    @Test
    fun doesNotSuggestTypeNamesReferencedInsideConstructorPayloadsAsConstructorsInSameFile() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type VerificationKey = ByteArray
            pub type Script = ByteArray
            pub type Hash<alg, a> = ByteArray

            pub opaque type Blake2b_224 {
              Blake2b_224
            }

            pub type Credential {
              VerificationKey(Hash<Blake2b_224, VerificationKey>)
              Script(Hash<Blake2b_224, Script>)
            }

            pub type Address {
              payment_credential: Credential,
            }

            fn main() {
              let address = Address { payment_credential: <caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("VerificationKey"))
        assertTrue(suggestions.toString(), suggestions.contains("Script"))
        assertFalse(suggestions.toString(), suggestions.contains("Hash"))
        assertFalse(suggestions.toString(), suggestions.contains("Blake2b_224"))
    }

    @Test
    fun doesNotSuggestTypeNamesReferencedInsideImportedConstructorPayloadsAsConstructors() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/crypto.ak",
            """
            pub type VerificationKey = ByteArray
            pub type Script = ByteArray
            pub type Hash<alg, a> = ByteArray

            pub opaque type Blake2b_224 {
              Blake2b_224
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "lib/address.ak",
            """
            use crypto.{Blake2b_224, Hash, Script, VerificationKey}

            pub type Credential {
              VerificationKey(Hash<Blake2b_224, VerificationKey>)
              Script(Hash<Blake2b_224, Script>)
            }

            pub type PaymentCredential = Credential

            pub type Address {
              payment_credential: PaymentCredential,
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use address.{Address}

            fn main() {
              let address = Address { payment_credential: <caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("VerificationKey"))
        assertTrue(suggestions.toString(), suggestions.contains("Script"))
        assertFalse(suggestions.toString(), suggestions.contains("Hash"))
        assertFalse(suggestions.toString(), suggestions.contains("Blake2b_224"))
    }

    @Test
    fun doesNotSuggestOuterRecordConstructorsInsideNestedConstructorArguments() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/address.ak",
            """
            pub type VerificationKey = ByteArray
            pub type Script = ByteArray
            pub type Hash<alg, a> = ByteArray

            pub opaque type Blake2b_224 {
              Blake2b_224
            }

            pub type Credential {
              VerificationKey(Hash<Blake2b_224, VerificationKey>)
              Script(Hash<Blake2b_224, Script>)
            }

            pub type PaymentCredential = Credential

            pub type Address {
              payment_credential: PaymentCredential,
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use address.{Address, VerificationKey}

            fn main() {
              let address = Address { payment_credential: VerificationKey(<caret>) }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertFalse(suggestions.toString(), suggestions.contains("VerificationKey"))
        assertFalse(suggestions.toString(), suggestions.contains("Script"))
    }

    @Test
    fun doesNotSuggestPrimitiveAliasInOrdinaryExpressionPosition() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type VerificationKey = ByteArray

            fn main() {
              let key = Ver<caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertFalse(suggestions.toString(), suggestions.contains("VerificationKey"))
    }

    @Test
    fun stillSuggestsPrimitiveAliasInTypeAnnotationPosition() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type VerificationKey = ByteArray

            fn main(key: Ver<caret>) -> VerificationKey {
              key
            }
            """.trimIndent()
        )

        assertCompletionContainsOrAutoInserted(
            expectedLookup = "VerificationKey",
            expectedInsertedSnippet = "fn main(key: VerificationKey) -> VerificationKey {"
        )
    }

    @Test
    fun suggestsOptionFallbacksForAliasOfOptionType() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/address.ak",
            """
            pub type Credential {
              VerificationKey
              Script(ByteArray)
            }

            pub type StakeCredential = Option<Credential>

            pub type Address {
              stake_credential: StakeCredential,
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use address.{Address}

            fn main() {
              let address = Address { stake_credential: <caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val presentationByLookup = completionPresentations().associate { (lookup, typeText) -> lookup to typeText }

        assertTrue(suggestions.toString(), suggestions.contains("Some()"))
        assertTrue(suggestions.toString(), suggestions.contains("None"))
        assertEquals("StakeCredential", presentationByLookup["Some()"])
        assertEquals("StakeCredential", presentationByLookup["None"])
    }

    @Test
    fun suggestsConstructorsForAliasTypedNestedRecordFieldWhenOwnerModuleIsNotImported() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/address.ak",
            """
            pub type VerificationKey = ByteArray
            pub type Script = ByteArray
            pub type Hash<alg, a> = ByteArray

            pub opaque type Blake2b_224 {
              Blake2b_224
            }

            pub type Credential {
              VerificationKey(Hash<Blake2b_224, VerificationKey>)
              Script(Hash<Blake2b_224, Script>)
            }

            pub type PaymentCredential = Credential
            pub type StakeCredential = Option<Credential>

            pub type Address {
              payment_credential: PaymentCredential,
              stake_credential: StakeCredential,
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/transaction.ak",
            """
            use cardano/address.{Address}

            pub type Datum {
              NoDatum
            }

            pub type Output {
              address: Address,
              datum: Datum,
            }

            pub type Input {
              output: Output,
            }

            pub type Transaction {
              inputs: List<Input>,
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use cardano/transaction.{Input, NoDatum, Output, Transaction}

            fn main() {
              let tx =
                Transaction {
                  inputs: [
                    Input {
                      output: Output {
                        address: Address {
                          payment_credential: <caret>,
                          stake_credential: None,
                        },
                        datum: NoDatum,
                      },
                    },
                  ],
                }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        assertTrue(suggestions.toString(), suggestions.contains("VerificationKey"))
        assertTrue(suggestions.toString(), suggestions.contains("Script"))
        assertFalse(suggestions.toString(), suggestions.contains("Hash"))
        assertFalse(suggestions.toString(), suggestions.contains("Blake2b_224"))

        val elements = myFixture.lookupElements ?: error("Expected completion items")
        val target = elements.firstOrNull { it.lookupString == "VerificationKey" } ?: error("Expected VerificationKey lookup")
        myFixture.lookup.currentItem = target
        myFixture.finishLookup('\n')

        val fileText = myFixture.file.text
        assertTrue(fileText, fileText.contains("use cardano/address.{VerificationKey}"))
        assertTrue(fileText, fileText.contains("payment_credential: VerificationKey"))
    }

    @Test
    fun suggestsTypedItemsInsideNestedOptionListLiterals() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Credential {
              VerificationKey
              Script(ByteArray)
            }

            fn consume(certificates: Option<List<Credential>>) -> Bool {
              True
            }

            fn main(backup: Credential) -> Bool {
              consume(Some([b<caret>]))
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val presentationByLookup = completionPresentations().associate { (lookup, typeText) -> lookup to typeText }

        assertTrue(suggestions.toString(), suggestions.contains("backup"))
        assertTrue(suggestions.toString(), suggestions.contains("VerificationKey"))
        assertTrue(suggestions.toString(), suggestions.contains("Script"))
        assertEquals("Credential", presentationByLookup["backup"])
        assertEquals("Credential", presentationByLookup["VerificationKey"])
        assertTrue(
            "Expected matching binding to outrank constructors inside Some([ ... ]). Suggestions were: $suggestions",
            suggestions.indexOf("backup") in 0 until suggestions.indexOf("VerificationKey")
        )
    }

    @Test
    fun suggestsInferredRecordLiteralBindingsInsideTypedListLiterals() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Input {
              value: Int,
            }

            pub type Transaction {
              inputs: List<Input>,
            }

            fn main() {
              let input =
                Input {
                  value: 42,
                }

              let tx =
                Transaction {
                  inputs: [i<caret>],
                }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        assertTrue(suggestions.toString(), suggestions.contains("input"))
        assertTrue(suggestions.toString(), suggestions.contains("Input"))
        assertTrue(
            "Expected inferred local binding to outrank constructor in list element position. Suggestions were: $suggestions",
            suggestions.indexOf("input") in 0 until suggestions.indexOf("Input")
        )
    }

    @Test
    fun suggestsNullaryFunctionsFromUnimportedModulesWhenReturnTypeMatchesAndAutoImportsThem() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/models.ak",
            """
            pub type Qwe {
              Qwe
            }

            pub type Transaction {
              value: Qwe,
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "lib/helpers.ak",
            """
            use models.{Qwe}

            pub fn from() -> Qwe {
              Qwe
            }

            pub fn ignored(seed: Int) -> Qwe {
              Qwe
            }

            pub fn wrong() -> Bool {
              True
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use models.{Transaction}

            fn main() {
              let tx = Transaction { value: fr<caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("from"))
        assertTrue(
            "Expected typed unimported function to outrank generic keyword suggestions. Suggestions were: $suggestions",
            suggestions.indexOf("from") >= 0 && (suggestions.indexOf("let").let { it < 0 || suggestions.indexOf("from") < it })
        )

        val elements = myFixture.lookupElements ?: error("Expected completion items")
        val target = elements.firstOrNull { it.lookupString == "from" } ?: error("Expected from lookup")
        myFixture.lookup.currentItem = target
        myFixture.finishLookup('\n')

        val fileText = myFixture.file.text
        assertTrue(fileText, fileText.contains("use models.{Transaction}"))
        assertTrue(fileText, fileText.contains("use helpers.{from}"))
        assertTrue(fileText, fileText.contains("let tx = Transaction { value: from() }"))
    }

    @Test
    fun suggestsNonNullaryFunctionsFromUnimportedModulesWhenReturnTypeMatchesAndAutoImportsThemAsCalls() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/models.ak",
            """
            pub type Qwe {
              Qwe
            }

            pub type Transaction {
              value: Qwe,
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "lib/helpers.ak",
            """
            use models.{Qwe}

            pub fn from_asset(policy_id: ByteArray, quantity: Int) -> Qwe {
              Qwe
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use models.{Transaction}

            fn main() {
              let tx = Transaction { value: from<caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("from_asset"))
        assertTrue(
            "Expected typed unimported function to outrank generic keyword suggestions. Suggestions were: $suggestions",
            suggestions.indexOf("from_asset") >= 0 && (suggestions.indexOf("let").let { it < 0 || suggestions.indexOf("from_asset") < it })
        )

        val elements = myFixture.lookupElements ?: error("Expected completion items")
        val target = elements.firstOrNull { it.lookupString == "from_asset" } ?: error("Expected from_asset lookup")
        myFixture.lookup.currentItem = target
        myFixture.finishLookup('\n')

        val fileText = myFixture.file.text
        assertTrue(fileText, fileText.contains("use models.{Transaction}"))
        assertTrue(fileText, fileText.contains("use helpers.{from_asset}"))
        assertTrue(fileText, fileText.contains("let tx = Transaction { value: from_asset() }"))
    }

    @Test
    fun suggestsFunctionFromPartiallyImportedModuleWhenOnlyMatchingTypeIsImported() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/assets.ak",
            """
            pub opaque type Value {
              inner: Int,
            }

            pub fn from_asset(policy_id: ByteArray, asset_name: ByteArray, quantity: Int) -> Value {
              Value(0)
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/transaction.ak",
            """
            use cardano/assets.{Value}

            pub type Output {
              value: Value,
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use cardano/assets.{Value}
            use cardano/transaction.{Output}

            fn main() {
              let output = Output { value: from<caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        assertTrue(suggestions.toString(), suggestions.contains("from_asset"))
    }

    @Test
    fun suggestsUnimportedGenericReturningFunctionForConcreteListExpectedType() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/collections.ak",
            """
            pub fn reduce(items: List<a>, zero: b, f: fn(b, a) -> b) -> b {
              zero
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            fn main(inputs: List<Int>) {
              let len2: List<Int> = redu<caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        if (!suggestions.contains("reduce")) {
            val autoInserted = myFixture.file.text
            assertTrue(autoInserted, autoInserted.contains("use collections.{reduce}"))
            assertTrue(autoInserted, autoInserted.contains("let len2: List<Int> = reduce("))
            return
        }

        val reduceLookups = (myFixture.lookupElements ?: error("Expected completion items")).filter { it.lookupString == "reduce" }
        val target = reduceLookups.firstOrNull { lookupTailText(it)?.contains("from collections") == true }
            ?: error("Expected auto-import reduce lookup, got ${reduceLookups.mapNotNull(::lookupTailText)}")
        myFixture.lookup.currentItem = target
        myFixture.finishLookup('\n')

        val fileText = myFixture.file.text
        assertTrue(fileText, fileText.contains("use collections.{reduce}"))
        assertTrue(fileText, fileText.contains("let len2: List<Int> = reduce("))
    }

    @Test
    fun prioritizesMatchingTypedBindingInitializerSuggestionsByTypedPrefix() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/collections.ak",
            """
            pub fn reduce(items: List<a>, zero: b, f: fn(b, a) -> b) -> b {
              zero
            }

            pub fn concat(left: List<a>, right: List<a>) -> List<a> {
              left
            }

            pub fn take(items: List<a>, count: Int) -> List<a> {
              items
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "lib/bytes.ak",
            """
            pub fn reduce(items: ByteArray, zero: b, f: fn(b, Int) -> b) -> b {
              zero
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            fn main(inputs: List<Bool>) {
              let len2: List<Bool> = reduc<caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue("Expected matching typed function suggestion, got: $suggestions", suggestions.contains("reduce"))
        val reduceIndex = suggestions.indexOf("reduce")
        val concatIndex = suggestions.indexOf("concat")
        if (concatIndex >= 0) {
            assertTrue("Expected matching typed function to outrank non-matching ones, got: $suggestions", reduceIndex in 0 until concatIndex)
        }
        val takeIndex = suggestions.indexOf("take")
        if (takeIndex >= 0) {
            assertTrue("Expected matching typed function to outrank non-matching ones, got: $suggestions", reduceIndex in 0 until takeIndex)
        }
    }

    @Test
    fun doesNotLeakNonMatchingTypedBindingInitializerSuggestionsForDifferentPrefix() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/collections.ak",
            """
            pub fn reduce(items: List<a>, zero: b, f: fn(b, a) -> b) -> b {
              zero
            }

            pub fn concat(left: List<a>, right: List<a>) -> List<a> {
              left
            }

            pub fn take(items: List<a>, count: Int) -> List<a> {
              items
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            fn main(inputs: List<Bool>) {
              let len2: List<Bool> = con<caret>
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertFalse("Expected non-matching typed entries to be filtered out, got: $suggestions", suggestions.contains("reduce"))
        assertFalse("Expected non-matching typed entries to be filtered out, got: $suggestions", suggestions.contains("take"))
    }

    @Test
    fun suggestsConstFromPartiallyImportedModuleInsideNestedImportedRecordLiteral() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/assets.ak",
            """
            pub opaque type Value {
              inner: Int,
            }

            pub const zero: Value = Value { inner: 0 }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/transaction.ak",
            """
            use cardano/assets.{Value}

            pub type Address {
              payment_credential: ByteArray,
              stake_credential: Option<Data>,
            }

            pub type Datum {
              NoDatum
            }

            pub type ScriptHash {
              ScriptHash(ByteArray)
            }

            pub type Output {
              address: Address,
              value: Value,
              datum: Datum,
              reference_script: Option<ScriptHash>,
            }

            pub type Input {
              output: Output,
            }

            pub type Transaction {
              inputs: List<Input>,
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use cardano/assets.{Value}
            use cardano/transaction.{Address, Input, NoDatum, Output, Transaction}

            fn main() {
              let q =
                Transaction {
                  inputs: [
                    Input {
                      output: Output {
                        address: Address {
                          payment_credential: "deadbeef",
                          stake_credential: None,
                        },
                        datum: NoDatum,
                        reference_script: None,
                        value: z<caret>,
                      },
                    },
                  ],
                }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        assertTrue(suggestions.toString(), suggestions.contains("zero"))
    }

    @Test
    fun prefersSameFileTypedConstBeforeFunctionsInExpectedTypePosition() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Qwe {
              Qwe
            }

            pub type Transaction {
              value: Qwe,
            }

            const local_zero: Qwe = Qwe

            fn from() -> Qwe {
              Qwe
            }

            fn main() {
              let tx = Transaction { value: <caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        assertTrue(suggestions.toString(), suggestions.contains("local_zero"))
        assertTrue(suggestions.toString(), suggestions.contains("from"))
        assertTrue(
            "Expected typed const to outrank typed function. Suggestions were: $suggestions",
            suggestions.indexOf("local_zero") in 0 until suggestions.indexOf("from")
        )
    }

    @Test
    fun prefersTypedConstsFromPartiallyImportedModulesBeforeFunctionsAndAutoImportsThem() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/assets.ak",
            """
            pub opaque type Value {
              inner: Int,
            }

            pub const zero: Value = Value { inner: 0 }

            pub fn from_asset(policy_id: ByteArray, asset_name: ByteArray, quantity: Int) -> Value {
              Value { inner: quantity }
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/transaction.ak",
            """
            use cardano/assets.{Value}

            pub type Output {
              value: Value,
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use cardano/assets.{Value}
            use cardano/transaction.{Output}

            fn main() {
              let output = Output { value: <caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        assertTrue(suggestions.toString(), suggestions.contains("zero"))
        assertTrue(suggestions.toString(), suggestions.contains("from_asset"))
        assertTrue(
            "Expected typed const to outrank typed function. Suggestions were: $suggestions",
            suggestions.indexOf("zero") in 0 until suggestions.indexOf("from_asset")
        )

        val elements = myFixture.lookupElements ?: error("Expected completion items")
        val target = elements.firstOrNull { it.lookupString == "zero" } ?: error("Expected zero lookup")
        myFixture.lookup.currentItem = target
        myFixture.finishLookup('\n')

        val fileText = myFixture.file.text
        assertTrue(fileText, fileText.contains("use cardano/assets.{Value}"))
        assertTrue(fileText, fileText.contains("use cardano/assets.{zero}"))
        assertTrue(fileText, fileText.contains("let output = Output { value: zero }"))
    }

    @Test
    fun keepsTypedConstCandidateWhenSiblingFieldHasSameName() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/assets.ak",
            """
            pub opaque type Value {
              inner: Int,
            }

            pub const zero: Value = Value { inner: 0 }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/transaction.ak",
            """
            use cardano/assets.{Value}

            pub type Output {
              zero: Value,
              value: Value,
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use cardano/assets.{Value}
            use cardano/transaction.{Output}

            fn main() {
              let output = Output { zero: zero, value: z<caret> }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val zeroLookups = (myFixture.lookupElements ?: error("Expected completion items")).filter { it.lookupString == "zero" }

        assertEquals("Expected sibling-field zero and typed const zero, got: $suggestions", 2, suggestions.count { it == "zero" })
        assertEquals("Expected two zero lookups, got: $suggestions", 2, zeroLookups.size)
        assertTrue(zeroLookups.mapNotNull(::lookupTailText).any { it.contains("from cardano/assets") })

        val target = zeroLookups.firstOrNull { lookupTailText(it)?.contains("from cardano/assets") == true }
            ?: error("Expected typed const zero lookup, got ${zeroLookups.mapNotNull(::lookupTailText)}")
        myFixture.lookup.currentItem = target
        myFixture.finishLookup('\n')

        val fileText = myFixture.file.text
        assertTrue(fileText, fileText.contains("use cardano/assets.{Value}"))
        assertTrue(fileText, fileText.contains("use cardano/assets.{zero}"))
        assertTrue(fileText, fileText.contains("let output = Output { zero: zero, value: zero }"))
    }

    @Test
    fun keepsRecordFieldTypedSuggestionsInsideRecordLiteralNestedInPositionalConstructorCall() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/assets.ak",
            """
            pub opaque type Value {
              inner: Int,
            }

            pub const zero: Value = Value { inner: 0 }

            pub fn from_asset(policy_id: ByteArray, asset_name: ByteArray, quantity: Int) -> Value {
              Value { inner: quantity }
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/transaction.ak",
            """
            use cardano/assets.{Value}

            pub type OutputReference {
              transaction_id: ByteArray,
              output_index: Int,
            }

            pub type Output {
              value: Value,
            }

            pub type Input {
              output_reference: OutputReference,
              output: Output,
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use cardano/assets.{Value, zero}
            use cardano/transaction.{Input, Output, OutputReference}

            const local_zero: Value = zero

            fn from() -> Value {
              zero
            }

            fn main(fsr: Int) {
              let remembered = zero
              let inpt = Input(OutputReference("1233", fsr), Output { value: <caret> })
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("remembered"))
        assertTrue(suggestions.toString(), suggestions.contains("local_zero"))
        assertTrue(suggestions.toString(), suggestions.contains("from"))
        assertFalse(suggestions.toString(), suggestions.contains("Output"))
        assertTrue(
            "Expected local binding to outrank const and function. Suggestions were: $suggestions",
            suggestions.indexOf("remembered") in 0 until suggestions.indexOf("local_zero")
        )
        assertTrue(
            "Expected typed const to outrank typed function. Suggestions were: $suggestions",
            suggestions.indexOf("local_zero") in 0 until suggestions.indexOf("from")
        )
    }

    @Test
    fun suggestsByteArrayBindingForAliasTypedRecordFieldWithoutExplicitAnnotation() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/crypto.ak",
            """
            pub type Hash<alg, a> = ByteArray

            pub opaque type Blake2b_256 {
              Blake2b_256
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/transaction.ak",
            """
            use aiken/crypto.{Blake2b_256, Hash}

            pub type Transaction {
              Transaction
            }

            pub type OutputReference {
              transaction_id: Hash<Blake2b_256, Transaction>,
              output_index: Int,
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use cardano/transaction.{OutputReference}

            fn main() {
              let bytes = "test"
              let output_reference = OutputReference { transaction_id: b<caret>, output_index: 0 }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val presentationByLookup = completionPresentations().toMap()

        assertTrue("Expected bytes suggestion, got: $suggestions", suggestions.contains("bytes"))
        assertEquals("ByteArray", presentationByLookup["bytes"])
    }

    @Test
    fun prefersExactAliasBindingBeforeUnderlyingByteArrayBindingForAliasTypedRecordField() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/crypto.ak",
            """
            pub type Hash<alg, a> = ByteArray

            pub opaque type Blake2b_256 {
              Blake2b_256
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/transaction.ak",
            """
            use aiken/crypto.{Blake2b_256, Hash}

            pub type Transaction {
              Transaction
            }

            pub type OutputReference {
              transaction_id: Hash<Blake2b_256, Transaction>,
              output_index: Int,
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use aiken/crypto.{Blake2b_256, Hash}
            use cardano/transaction.{OutputReference, Transaction}

            fn main() {
              let exact: Hash<Blake2b_256, Transaction> = "test"
              let bytes = "test"
              let output_reference = OutputReference { transaction_id: <caret>, output_index: 0 }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()
        val presentationByLookup = completionPresentations().toMap()
        val exactIndex = suggestions.indexOf("exact")
        val bytesIndex = suggestions.indexOf("bytes")

        assertTrue("Expected exact suggestion, got: $suggestions", exactIndex >= 0)
        assertTrue("Expected bytes suggestion, got: $suggestions", bytesIndex >= 0)
        assertTrue("Expected exact alias match above underlying ByteArray, got: $suggestions", exactIndex < bytesIndex)
        assertEquals("Hash<Blake2b_256, Transaction>", presentationByLookup["exact"])
        assertEquals("ByteArray", presentationByLookup["bytes"])
    }

    @Test
    fun suggestsBindingsAndConstsForRecordSpreadAndAutoImportsUnimportedConsts() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/transaction.ak",
            """
            pub type Input {
              amount: Int,
            }

            pub type Transaction {
              inputs: List<Input>,
            }

            pub const placeholder: Transaction = Transaction { inputs: [] }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use cardano/transaction.{Transaction}

            fn main() {
              let remembered = Transaction { inputs: [] }
              let tx = Transaction { ..pla<caret>, inputs: [] }
            }
            """.trimIndent()
        )

        acceptLookupOrAutoInserted("placeholder", "..placeholder")

        val fileText = myFixture.file.text
        assertTrue(fileText, fileText.startsWith("use cardano/transaction.{placeholder}\n"))
        assertTrue(fileText, fileText.contains("use cardano/transaction.{Transaction}"))
        assertTrue(fileText, fileText.contains("let tx = Transaction { ..placeholder, inputs: [] }"))
    }

    @Test
    fun suggestsOnlyMatchingListValuesForListSpreadInsideTypedRecordField() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Input {
              amount: Int,
            }

            pub type Transaction {
              inputs: List<Input>,
            }

            const placeholder: Transaction = Transaction { inputs: [] }

            fn main(input: Input) {
              let remembered = [input]
              let tx = Transaction { inputs: [..rem<caret>] }
            }
            """.trimIndent()
        )

        assertCompletionContainsOrAutoInserted("remembered", "[..remembered]")

        val suggestions = completionVariants()
        if (suggestions.isNotEmpty()) {
            assertFalse(suggestions.toString(), suggestions.contains("input"))
            assertFalse(suggestions.toString(), suggestions.contains("Input"))
        }
    }

    @Test
    fun infersListBindingTypeFromIfExpressionForSpreadCompletion() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Input {
              amount: Int,
            }

            pub type Transaction {
              inputs: List<Input>,
            }

            fn main(flag: Bool, input1: Input, input2: Input) {
              let inputs = if flag { [input1] } else { [input2] }
              let tx = Transaction { inputs: [..in<caret>] }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("inputs"))
        assertFalse(suggestions.toString(), suggestions.contains("input1"))
        assertFalse(suggestions.toString(), suggestions.contains("Input"))
    }

    @Test
    fun infersListBindingTypeFromIfExpressionForExpectCompletion() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Input {
              amount: Int,
            }

            pub type Transaction {
              inputs: List<Input>,
            }

            fn main(flag: Bool, input1: Input, input2: Input) {
              expect inputs = if flag { [input1] } else { [input2] }
              let tx = Transaction { inputs: [..in<caret>] }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("inputs"))
        assertFalse(suggestions.toString(), suggestions.contains("input1"))
        assertFalse(suggestions.toString(), suggestions.contains("Input"))
    }

    @Test
    fun infersListBindingTypeFromWhenExpressionForSpreadCompletion() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Input {
              amount: Int,
            }

            pub type Transaction {
              inputs: List<Input>,
            }

            fn main(flag: Bool, input1: Input, input2: Input) {
              let inputs = when flag is {
                True -> [input1]
                False -> [input2]
              }
              let tx = Transaction { inputs: [..in<caret>] }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("inputs"))
        assertFalse(suggestions.toString(), suggestions.contains("input1"))
        assertFalse(suggestions.toString(), suggestions.contains("Input"))
    }

    @Test
    fun usesAnnotatedListBindingTypeForExpectEmptyPlainListLiteralCompletion() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Input {
              amount: Int,
            }

            fn main(input1: Input, input2: Input, fsr: Int) {
              expect inputs: List<Input> = [<caret>]
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("input1"))
        assertTrue(suggestions.toString(), suggestions.contains("input2"))
        assertTrue(suggestions.toString(), suggestions.contains("Input"))
        assertFalse(suggestions.toString(), suggestions.contains("fsr"))
        assertFalse(suggestions.toString(), suggestions.contains("let"))
        assertFalse(suggestions.toString(), suggestions.contains("use"))
    }

    @Test
    fun infersListBindingTypeFromBlockExpressionForSpreadCompletion() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Input {
              amount: Int,
            }

            pub type Transaction {
              inputs: List<Input>,
            }

            fn main(input1: Input, input2: Input) {
              let inputs = {
                let chosen = [input1, input2]
                chosen
              }
              let tx = Transaction { inputs: [..in<caret>] }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("inputs"))
        assertFalse(suggestions.toString(), suggestions.contains("input1"))
        assertFalse(suggestions.toString(), suggestions.contains("Input"))
    }

    @Test
    fun infersListBindingTypeFromParenthesizedExpressionForSpreadCompletion() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Input {
              amount: Int,
            }

            pub type Transaction {
              inputs: List<Input>,
            }

            fn main(input1: Input, input2: Input) {
              let inputs = ([input1, input2])
              let tx = Transaction { inputs: [..in<caret>] }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("inputs"))
        assertFalse(suggestions.toString(), suggestions.contains("input1"))
        assertFalse(suggestions.toString(), suggestions.contains("Input"))
    }

    @Test
    fun infersListBindingTypeFromPipeExpressionForSpreadCompletion() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/aiken/list.ak",
            """
            pub fn reverse(items: List<a>) -> List<a> {
              items
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use aiken/list

            pub type Input {
              amount: Int,
            }

            pub type Transaction {
              inputs: List<Input>,
            }

            fn main(input1: Input, input2: Input) {
              let inputs = [input1, input2]
              let reversed = inputs |> list.reverse
              let tx = Transaction { inputs: [..rev<caret>] }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("reversed"))
        assertFalse(suggestions.toString(), suggestions.contains("reverse"))
        assertFalse(suggestions.toString(), suggestions.contains("Input"))
    }

    @Test
    fun suggestsMatchingListValuesForSpreadWhenCaretIsBeforeExistingIdentifierWithoutTrailingComma() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Input {
              amount: Int,
            }

            pub type Transaction {
              inputs: List<Input>,
            }

            fn main(input1: Input, input2: Input) {
              let inputs = [input1, input2]
              let tx = Transaction { inputs: [input1, ..<caret>inputs] }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("inputs"))
        assertFalse(suggestions.toString(), suggestions.contains("input1"))
        assertFalse(suggestions.toString(), suggestions.contains("Input"))
    }

    @Test
    fun doesNotDuplicateExistingIdentifierWhenCompletingSpreadBeforeSameIdentifier() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Input {
              amount: Int,
            }

            pub type Transaction {
              inputs: List<Input>,
            }

            fn main(input1: Input, input2: Input) {
              let inputs = [input1, input2]
              let tx = Transaction { inputs: [input1, ..<caret>inputs] }
            }
            """.trimIndent()
        )

        acceptLookupOrAutoInserted("inputs", "[input1, ..inputs]")

        val fileText = myFixture.file.text
        assertTrue(fileText, fileText.contains("let tx = Transaction { inputs: [input1, ..inputs] }"))
        assertFalse(fileText, fileText.contains("inputsinputs"))
    }

    @Test
    fun insertsCommaWhenCompletingSpreadBeforeExistingFollowingElement() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Input {
              amount: Int,
            }

            pub type Transaction {
              inputs: List<Input>,
            }

            fn main(input1: Input, input2: Input) {
              let inputs = [input1, input2]
              let tx = Transaction { inputs: [input1, ..<caret>input2] }
            }
            """.trimIndent()
        )

        acceptLookupOrAutoInserted("inputs", "[input1, ..inputs, input2]")

        val fileText = myFixture.file.text
        assertTrue(fileText, fileText.contains("let tx = Transaction { inputs: [input1, ..inputs, input2] }"))
    }

    @Test
    fun doesNotOfferGenericKeywordsInsidePlainListLiteralItems() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            fn main() {
              let inps = [<caret>]
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertFalse(suggestions.toString(), suggestions.contains("let"))
        assertFalse(suggestions.toString(), suggestions.contains("use"))
        assertFalse(suggestions.toString(), suggestions.contains("True"))
        assertFalse(suggestions.toString(), suggestions.contains("False"))
    }

    @Test
    fun usesAnnotatedListBindingTypeForEmptyPlainListLiteralCompletion() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Input {
              amount: Int,
            }

            fn main(input1: Input, input2: Input, fsr: Int) {
              let inputs: List<Input> = [<caret>]
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("input1"))
        assertTrue(suggestions.toString(), suggestions.contains("input2"))
        assertTrue(suggestions.toString(), suggestions.contains("Input"))
        assertFalse(suggestions.toString(), suggestions.contains("fsr"))
        assertFalse(suggestions.toString(), suggestions.contains("let"))
        assertFalse(suggestions.toString(), suggestions.contains("use"))
    }

    @Test
    fun infersSameFileUntypedConstTypeForRecordSpreadCompletion() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
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
              let tx = Transaction { ..pla<caret>, inputs: [] }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("placeholder"))
    }

    @Test
    fun doesNotSuggestBindingBeingDeclaredInsideOwnPlainListLiteral() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            fn main(input: Int) {
              let inps = [<caret>]
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertFalse(suggestions.toString(), suggestions.contains("inps"))
        assertTrue(suggestions.toString(), suggestions.contains("input"))
    }

    @Test
    fun prefersMatchingBindingsBeforeConstsInRecordSpreadPosition() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Input {
              amount: Int,
            }

            pub type Transaction {
              inputs: List<Input>,
            }

            const placeholder: Transaction = Transaction { inputs: [] }

            fn main() {
              let preserved = Transaction { inputs: [] }
              let tx = Transaction { ..p<caret>, inputs: [] }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("preserved"))
        assertTrue(suggestions.toString(), suggestions.contains("placeholder"))
        assertTrue(
            "Expected binding to outrank const in spread position. Suggestions were: $suggestions",
            suggestions.indexOf("preserved") in 0 until suggestions.indexOf("placeholder")
        )
    }

    @Test
    fun doesNotSuggestBindingBeingDeclaredInItsOwnRecordSpread() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Input {
              amount: Int,
            }

            pub type Transaction {
              inputs: List<Input>,
            }

            const placeholder: Transaction = Transaction { inputs: [] }

            fn main() {
              let tx = Transaction { ..<caret>, inputs: [] }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertFalse(suggestions.toString(), suggestions.contains("tx"))
        assertTrue(suggestions.toString(), suggestions.contains("placeholder"))
    }

    @Test
    fun suggestsRecordSpreadSourcesBeforeExistingFieldNameWithoutComma() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Input {
              amount: Int,
            }

            pub type Transaction {
              inputs: List<Input>,
            }

            const placeholder: Transaction = Transaction { inputs: [] }

            fn main() {
              let preserved = Transaction { inputs: [] }
              let tx = Transaction { ..<caret>inputs: [] }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("preserved"))
        assertTrue(suggestions.toString(), suggestions.contains("placeholder"))
        assertFalse(suggestions.toString(), suggestions.contains("inputs"))
    }

    @Test
    fun doesNotFallbackToOuterConstructorSuggestionsAfterTrailingCommaInNestedRecordLiteral() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/assets.ak",
            """
            pub opaque type Value {
              inner: Int,
            }

            pub const zero: Value = Value { inner: 0 }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/address.ak",
            """
            pub type Credential {
              VerificationKey(ByteArray)
            }

            pub type Address {
              payment_credential: Credential,
              stake_credential: Option<Credential>,
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/transaction.ak",
            """
            use cardano/address.{Address}
            use cardano/assets.{Value}

            pub type Datum {
              NoDatum
            }

            pub type OutputReference {
              transaction_id: ByteArray,
              output_index: Int,
            }

            pub type Output {
              value: Value,
              reference_script: Option<ByteArray>,
              datum: Datum,
              address: Address,
            }

            pub type Input {
              output_reference: OutputReference,
              output: Output,
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use cardano/address.{VerificationKey}
            use cardano/assets.{zero}
            use cardano/transaction.{Input, NoDatum, Output, OutputReference}

            fn main(fsr: Int) {
              let inpt =
                Input(
                  OutputReference("1233", fsr),
                  Output {
                    value: zero,
                    reference_script: Some("22"),
                    datum: NoDatum,
                    address: Address {
                      payment_credential: VerificationKey("1234"),
                      stake_credential: None,
                    },<caret>
                  },
                )
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertFalse("Unexpected suggestions: $suggestions", suggestions.contains("Output"))
    }

    @Test
    fun doesNotLeakGenericSemanticIdentifiersIntoTypedRecordFieldValueCompletion() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Input {
              output: Int,
            }

            pub type Transaction {
              inputs: List<Input>,
              reference_inputs: List<Input>,
            }

            fn main(tx: Transaction) {
              let remembered = tx.reference_inputs
              let next =
                Transaction {
                  inputs: <caret>,
                  reference_inputs: remembered,
                }
            }
            """.trimIndent()
        )

        val suggestions = completionVariants()

        assertTrue("Expected typed list fallback, got: $suggestions", suggestions.contains("[]"))
        assertFalse("Unexpected generic identifier leakage: $suggestions", suggestions.contains("reference_inputs"))
    }

    private fun completionVariants(): List<String> =
        myFixture.completeBasic()
            ?.map { it.lookupString }
            .orEmpty()

    private fun completionPresentations(): List<Pair<String, String?>> =
        myFixture.lookupElements
            ?.map { element ->
                val presentation = LookupElementPresentation()
                element.renderElement(presentation)
                element.lookupString to presentation.typeText
            }
            .orEmpty()

    private fun lookupTailText(element: com.intellij.codeInsight.lookup.LookupElement): String? {
        val presentation = LookupElementPresentation()
        element.renderElement(presentation)
        return presentation.tailText
    }

    private fun assertCompletionContainsOrAutoInserted(
        expectedLookup: String,
        expectedInsertedSnippet: String
    ) {
        val suggestions = completionVariants()
        if (suggestions.contains(expectedLookup)) return

        assertTrue(
            "Expected lookup '$expectedLookup' or inserted snippet '$expectedInsertedSnippet'. File text was:\n${myFixture.file.text}",
            myFixture.file.text.contains(expectedInsertedSnippet)
        )
    }

    private fun acceptLookupOrAutoInserted(
        expectedLookup: String,
        expectedInsertedSnippet: String
    ) {
        val suggestions = myFixture.completeBasic()?.map { it.lookupString }
        if (suggestions == null) {
            assertTrue(
                "Expected lookup '$expectedLookup' or inserted snippet '$expectedInsertedSnippet'. File text was:\n${myFixture.file.text}",
                myFixture.file.text.contains(expectedInsertedSnippet)
            )
            return
        }

        val target = myFixture.lookupElements?.firstOrNull { it.lookupString == expectedLookup }
            ?: error("Expected $expectedLookup lookup, got $suggestions")
        myFixture.lookup.currentItem = target
        myFixture.finishLookup('\n')
    }
}
