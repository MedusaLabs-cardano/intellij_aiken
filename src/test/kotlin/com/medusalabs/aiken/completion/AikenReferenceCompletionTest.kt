package com.medusalabs.aiken.completion

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.medusalabs.aiken.AikenPlatformTestCase
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

        val suggestions = completionVariants()

        assertTrue(suggestions.toString(), suggestions.contains("add"))
        assertFalse(suggestions.toString(), suggestions.contains("sub"))
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

        val suggestions = completionVariants()
        val presentationByLookup = completionPresentations().associate { (lookup, typeText) -> lookup to typeText }

        assertTrue(suggestions.toString(), suggestions.contains("credential"))
        assertEquals("Credential", presentationByLookup["credential"])

        val elements = myFixture.lookupElements ?: error("Expected completion items")
        val target = elements.firstOrNull { it.lookupString == "credential" } ?: error("Expected credential lookup")
        myFixture.lookup.currentItem = target
        myFixture.finishLookup('\n')

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

        val elements = myFixture.completeBasic() ?: error("Expected completion items")
        val target = elements.firstOrNull { it.lookupString == "certificates" } ?: error("Expected certificates lookup")
        myFixture.lookup.currentItem = target
        myFixture.finishLookup('\n')

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
}
