package com.medusalabs.aiken.completion

import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.testFramework.fixtures.CompletionAutoPopupTestCase
import java.nio.file.Path

class AikenCompletionAutoPopupTest : CompletionAutoPopupTestCase() {
    override fun getTestDataPath(): String =
        Path.of(System.getProperty("user.dir"), "src", "test", "testData").toString()

    fun testAutoPopupWorksForUseModuleCompletion() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/math.ak",
            """
            pub fn add(left, right) {
              left
            }
            """.trimIndent()
        )
        myFixture.configureByText("main.ak", "use <caret>")

        type("ma")

        val suggestions = myFixture.lookupElements?.map { it.lookupString }.orEmpty()
        assertTrue(suggestions.toString(), suggestions.contains("math"))
    }

    fun testAutoPopupWorksForReverseUseSuggestions() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/placeholder.ak",
            """
            pub type Qwe {
              Qwe
            }
            """.trimIndent()
        )
        myFixture.configureByText("main.ak", "use <caret>")

        type("Qw")

        val suggestions = myFixture.lookupElements?.map { it.lookupString }.orEmpty()
        assertTrue(suggestions.toString(), suggestions.contains("placeholder.{Qwe}"))
    }

    fun testAutoPopupWorksForLocalSemanticCompletion() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            fn helper(seed: Int) -> Int {
              seed
            }

            fn main(seed: Int) -> Int {
              <caret>
            }
            """.trimIndent()
        )

        type("h")

        val suggestions = myFixture.lookupElements?.map { it.lookupString }.orEmpty()
        assertTrue(suggestions.toString(), suggestions.contains("helper"))
    }

    fun testAutoPopupWorksForUnimportedTypeInOrdinaryExpressionPosition() {
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
              let input = <caret>
            }
            """.trimIndent()
        )

        type("Inp")

        val suggestions = myFixture.lookupElements?.map { it.lookupString }.orEmpty()
        assertTrue(suggestions.toString(), suggestions.contains("Input"))
    }

    fun testAutoPopupWorksForUnimportedTypeOnFirstCharacterInOrdinaryExpressionPosition() {
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
              let input = <caret>
            }
            """.trimIndent()
        )

        type("I")

        val suggestions = myFixture.lookupElements?.map { it.lookupString }.orEmpty()
        assertTrue(suggestions.toString(), suggestions.contains("Input"))
    }

    fun testAutoPopupPrefersUnimportedTypeOnFirstCharacterWhenImportedSiblingTypeExists() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/transaction.ak",
            """
            pub type Input {
              Input
            }

            pub type Transaction {
              Transaction
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use cardano/transaction.{Transaction}

            fn main() {
              let input = <caret>
            }
            """.trimIndent()
        )

        type("I")

        val suggestions = myFixture.lookupElements?.map { it.lookupString }.orEmpty()
        assertTrue(suggestions.toString(), suggestions.contains("Input"))
    }

    fun testAutoPopupPrefersUnimportedTypeAfterWhitespaceRefreshOnFirstCharacter() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/transaction.ak",
            """
            pub type Input {
              Input
            }

            pub type Transaction {
              Transaction
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use cardano/transaction.{Transaction}

            fn main() {
              let input =<caret>
            }
            """.trimIndent()
        )

        type(" I")

        val suggestions = myFixture.lookupElements?.map { it.lookupString }.orEmpty()
        assertTrue(suggestions.toString(), suggestions.contains("Input"))
    }

    fun testAutoPopupMatchesWrongKeyboardLayoutForUnimportedTypeAfterWhitespace() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "build/packages/aiken-lang-stdlib/lib/cardano/transaction.ak",
            """
            pub type Input {
              Input
            }

            pub type Transaction {
              Transaction
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use cardano/transaction.{Transaction}

            fn main() {
              let input = <caret>
            }
            """.trimIndent()
        )

        type("ш")

        val suggestions = myFixture.lookupElements?.map { it.lookupString }.orEmpty()
        assertTrue(suggestions.toString(), suggestions.contains("Input"))
    }

    fun testAutoPopupWorksForUnimportedFunctionInOrdinaryExpressionPosition() {
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
              let interval = <caret>
            }
            """.trimIndent()
        )

        type("befo")

        val suggestions = myFixture.lookupElements?.map { it.lookupString }.orEmpty()
        assertTrue(suggestions.toString(), suggestions.contains("before"))
    }

    fun testAutoPopupWorksForUnimportedModuleInOrdinaryExpressionPosition() {
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
              let q = <caret>
            }
            """.trimIndent()
        )

        type("lis")

        val suggestions = myFixture.lookupElements?.map { it.lookupString }.orEmpty()
        assertTrue(suggestions.toString(), suggestions.contains("list"))
    }

    fun testAutoPopupAndExplicitCompletionConvergeForOrdinaryPrefix() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/interval.ak",
            """
            pub fn entirely_after() -> Bool {
              True
            }

            pub fn entirely_before() -> Bool {
              True
            }

            pub fn entirely_between() -> Bool {
              True
            }
            """.trimIndent()
        )

        assertAutoPopupAndExplicitCompletionConverge(
            """
            fn main() {
              let interval = <caret>
            }
            """.trimIndent(),
            typedText = "ent",
            meaningfulPrefix = "ent"
        )
    }

    fun testAutoPopupAndExplicitCompletionConvergeForQualifiedPrefix() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/math.ak",
            """
            pub fn add(left: Int, right: Int) -> Int {
              left
            }

            pub fn abs(value: Int) -> Int {
              value
            }
            """.trimIndent()
        )

        assertAutoPopupAndExplicitCompletionConverge(
            """
            use math as math_utils

            fn main(seed: Int) -> Int {
              math_utils<caret>
            }
            """.trimIndent(),
            typedText = ".a",
            meaningfulPrefix = "a"
        )
    }

    fun testAutoPopupAndExplicitCompletionConvergeForTypedExpectedValuePrefix() {
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

            pub fn from_asset(policy_id: ByteArray, quantity: Int) -> Qwe {
              Qwe
            }
            """.trimIndent()
        )

        assertAutoPopupAndExplicitCompletionConverge(
            """
            use models.{Transaction}

            fn main() {
              let tx = Transaction { value: <caret> }
            }
            """.trimIndent(),
            typedText = "from",
            meaningfulPrefix = "from"
        )
    }

    fun testAutoPopupWorksForListSpreadTypedAfterReturningBeforeExistingIdentifier() {
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
              let tx = Transaction { inputs: [input1, <caret>inputs] }
            }
            """.trimIndent()
        )

        type("..")

        val suggestions = myFixture.lookupElements?.map { it.lookupString }.orEmpty()
        assertTrue(suggestions.toString(), suggestions.contains("inputs"))
    }

    fun testAutoPopupWorksForRecordSpreadTypedBeforeExistingFieldNameWithoutComma() {
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
              let tx = Transaction { <caret>inputs: [] }
            }
            """.trimIndent()
        )

        type("..")

        val suggestions = myFixture.lookupElements?.map { it.lookupString }.orEmpty()
        assertTrue(suggestions.toString(), suggestions.contains("preserved"))
        assertTrue(suggestions.toString(), suggestions.contains("placeholder"))
    }

    fun testAutoPopupRefreshesActiveLookupForLongerOrdinaryPrefixes() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/interval.ak",
            """
            pub fn entirely_after() -> Bool {
              True
            }

            pub fn entirely_before() -> Bool {
              True
            }

            pub fn entirely_between() -> Bool {
              True
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            fn main() {
              let interval = <caret>
            }
            """.trimIndent()
        )

        type("e")
        assertNotNull("Expected active lookup after first character", myFixture.lookupElements)

        type("n")

        val suggestions = myFixture.lookupElements?.map { it.lookupString }.orEmpty()
        assertTrue(suggestions.toString(), suggestions.contains("entirely_after"))
        assertTrue(suggestions.toString(), suggestions.contains("entirely_before"))
        assertTrue(suggestions.toString(), suggestions.contains("entirely_between"))
    }

    fun testAutoPopupWorksForQualifiedCompletion() {
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
        myFixture.configureByText(
            "main.ak",
            """
            use math as math_utils

            fn main(seed: Int) -> Int {
              math_utils<caret>
            }
            """.trimIndent()
        )

        type(".a")

        val suggestions = myFixture.lookupElements?.map { it.lookupString }.orEmpty()
        assertTrue(suggestions.toString(), suggestions.contains("add"))
    }

    fun testAutoPopupWorksAfterPipeWhitespace() {
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
              let q = inputs<caret>
            }
            """.trimIndent()
        )

        type(" |> ")

        val suggestions = myFixture.lookupElements?.map { it.lookupString }.orEmpty()
        assertTrue(suggestions.toString(), suggestions.contains("length"))
        assertFalse(suggestions.toString(), suggestions.contains("list.length"))
        assertFalse(suggestions.toString(), suggestions.contains("let"))
    }

    fun testAutoPopupWorksForRecordFieldNameCompletion() {
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
              let tx = Transaction { <caret> }
            }
            """.trimIndent()
        )

        type("c")

        val suggestions = myFixture.lookupElements?.map { it.lookupString }.orEmpty()
        assertTrue(suggestions.toString(), suggestions.contains("credential"))
    }

    fun testAutoPopupWorksForTypedRecordValueSuggestionsAfterColon() {
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
              let q = Qwe1 { primary<caret> }
            }
            """.trimIndent()
        )

        type(":")

        val suggestions = myFixture.lookupElements?.map { it.lookupString }.orEmpty()
        assertTrue(suggestions.toString(), suggestions.contains("backup"))
        assertTrue(suggestions.toString(), suggestions.contains("VerificationKey"))
        assertTrue(suggestions.toString(), suggestions.contains("Script"))
    }

    fun testAutoPopupWorksForImportedBuildPackageListFieldSuggestionsAfterColonAndSpace() {
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
              let tx = Transaction { outputs<caret> }
            }
            """.trimIndent()
        )

        type(": ")

        val suggestions = myFixture.lookupElements?.map { it.lookupString }.orEmpty()
        assertTrue(suggestions.toString(), suggestions.contains("[]"))
    }

    fun testAutoPopupWorksForOptionFieldSuggestionsAfterColonAndSpace() {
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
              let tx = Transaction { certificate<caret> }
            }
            """.trimIndent()
        )

        type(": ")

        val suggestions = myFixture.lookupElements?.map { it.lookupString }.orEmpty()
        assertTrue(suggestions.toString(), suggestions.contains("existing"))
        assertTrue(suggestions.toString(), suggestions.contains("Some()"))
        assertTrue(suggestions.toString(), suggestions.contains("None"))
    }

    fun testAutoPopupFiltersToNullaryUnimportedFunctionsWithMatchingReturnType() {
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
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.ak",
            """
            use models.{Transaction}

            fn main() {
              let tx = Transaction { value: <caret> }
            }
            """.trimIndent()
        )

        type("fr")

        val suggestions = myFixture.lookupElements?.map { it.lookupString }.orEmpty()
        assertTrue(suggestions.toString(), suggestions.contains("from"))
    }

    fun testAutoPopupSuggestsNonNullaryUnimportedFunctionsWithMatchingReturnType() {
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
              let tx = Transaction { value: <caret> }
            }
            """.trimIndent()
        )

        type("from")

        val suggestions = myFixture.lookupElements?.map { it.lookupString }.orEmpty()
        assertTrue(suggestions.toString(), suggestions.contains("from_asset"))
    }

    fun testAutoPopupPrefersTypedConstsOverFunctionsForExpectedType() {
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

        type("z")

        val suggestions = myFixture.lookupElements?.map { it.lookupString }.orEmpty()
        assertTrue(suggestions.toString(), suggestions.contains("zero"))
    }

    fun testAutoPopupWorksForTypedFunctionArgumentSuggestions() {
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
              consume(<caret>)
            }
            """.trimIndent()
        )

        type("b")

        val suggestions = myFixture.lookupElements?.map { it.lookupString }.orEmpty()
        assertTrue(suggestions.toString(), suggestions.contains("backup"))
        assertTrue(suggestions.toString(), suggestions.contains("VerificationKey"))
        assertTrue(suggestions.toString(), suggestions.contains("Script"))
    }

    fun testAutoPopupWorksInsideListLiteralForTypedSuggestions() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.configureByText(
            "main.ak",
            """
            pub type Credential {
              VerificationKey
              Script(ByteArray)
            }

            fn consume(certificates: List<Credential>) -> Bool {
              True
            }

            fn main(backup: Credential) -> Bool {
              consume(<caret>)
            }
            """.trimIndent()
        )

        type("[")

        val suggestions = myFixture.lookupElements?.map { it.lookupString }.orEmpty()
        assertTrue(suggestions.toString(), suggestions.contains("backup"))
        assertTrue(suggestions.toString(), suggestions.contains("VerificationKey"))
        assertTrue(suggestions.toString(), suggestions.contains("Script"))
    }

    private fun assertAutoPopupAndExplicitCompletionConverge(
        initialText: String,
        typedText: String,
        meaningfulPrefix: String = typedText
    ) {
        myFixture.configureByText("auto_main.ak", initialText)
        type(typedText)

        val autoPopupSuggestions =
            myFixture.lookupElements
                ?.map { it.lookupString }
                .orEmpty()
                .filterMeaningfulMatches(meaningfulPrefix)
        assertTrue(
            "Expected meaningful auto-popup suggestions for '$typedText'",
            autoPopupSuggestions.isNotEmpty()
        )

        myFixture.configureByText(
            "explicit_main.ak",
            initialText.replace("<caret>", "$typedText<caret>")
        )

        val explicitSuggestions =
            myFixture.completeBasic()
                ?.map { it.lookupString }
                .orEmpty()
                .filterMeaningfulMatches(meaningfulPrefix)

        assertEquals(
            "Expected auto-popup and explicit completion to converge for '$typedText'",
            autoPopupSuggestions,
            explicitSuggestions
        )
    }

    private fun List<String>.filterMeaningfulMatches(prefix: String): List<String> {
        val matcher = PlainPrefixMatcher(prefix)
        return filter { candidate ->
            AikenCompletionPrefixMatching.matches(candidate, matcher, prefix)
        }
    }
}
