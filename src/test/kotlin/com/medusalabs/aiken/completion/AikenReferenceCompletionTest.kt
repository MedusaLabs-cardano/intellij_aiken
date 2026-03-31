package com.medusalabs.aiken.completion

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.medusalabs.aiken.AikenPlatformTestCase
import com.medusalabs.aiken.highlight.lexer.AikenLexing
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
        assertTrue(suggestions.toString(), suggestions.contains("list.length"))
        assertTrue(suggestions.toString(), suggestions.contains("list.filter"))
        assertFalse(suggestions.toString(), suggestions.contains("unrelated"))
        assertFalse(suggestions.toString(), suggestions.contains("let"))
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
        assertTrue(fileText, fileText.contains("let tx = Transaction { value: from_asset(policy_id: , quantity: ) }"))
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
