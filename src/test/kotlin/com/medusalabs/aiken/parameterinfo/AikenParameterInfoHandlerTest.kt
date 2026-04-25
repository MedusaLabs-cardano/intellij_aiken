package com.medusalabs.aiken.parameterinfo

import com.medusalabs.aiken.AikenPlatformTestCase
import org.junit.Test

class AikenParameterInfoHandlerTest : AikenPlatformTestCase() {
    @Test
    fun showsSignatureForCallableLocalBindingToSameFileFunction() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            fn compute(seed: Int, factor: Int) -> Int {
              seed * factor
            }

            fn main() -> Int {
              let chosen = compute
              chosen(1, <caret>2)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("compute(seed: Int, <b>factor: Int</b>) -&gt; Int", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun showsSignatureForCallableLocalBindingToImportedAlias() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/math.ak",
            """
            pub fn compute(seed: Int, factor: Int) -> Int {
              seed * factor
            }
            """.trimIndent()
        )
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            use math.{compute as imported_compute}

            fn main() -> Int {
              let chosen = imported_compute
              chosen(1, <caret>2)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("compute(seed: Int, <b>factor: Int</b>) -&gt; Int", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun showsSignatureForCallableLocalBindingToModuleAliasMember() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/math.ak",
            """
            pub fn compute(seed: Int, factor: Int) -> Int {
              seed * factor
            }
            """.trimIndent()
        )
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            use math as math_utils

            fn main() -> Int {
              let chosen = math_utils.compute
              chosen(1, <caret>2)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("compute(seed: Int, <b>factor: Int</b>) -&gt; Int", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun highlightsSecondParameterForPipeCall() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            fn compute(seed: Int, factor: Int) -> Int {
              seed * factor
            }

            fn main(value: Int) -> Int {
              value |> compute(<caret>2)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("compute(seed: Int, <b>factor: Int</b>) -&gt; Int", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun highlightsSecondParameterForQualifiedPipeCall() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/math.ak",
            """
            pub fn compute(seed: Int, factor: Int) -> Int {
              seed * factor
            }
            """.trimIndent()
        )
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            use math as math_utils

            fn main(value: Int) -> Int {
              value |> math_utils.compute(<caret>2)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("compute(seed: Int, <b>factor: Int</b>) -&gt; Int", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun keepsOriginalParameterMappingForPipeCaptureCall() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            fn blend(left: Int, middle: Int, right: Int) -> Int {
              left + middle + right
            }

            fn main(value: Int) -> Int {
              value |> blend(1, _, <caret>3)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("blend(left: Int, middle: Int, <b>right: Int</b>) -&gt; Int", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun keepsOriginalParameterMappingForPipeCallThatReturnsCallable() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            fn build(left: Int, right: Int) -> fn(Int) -> Int {
              fn(seed: Int) -> Int {
                left + right + seed
              }
            }

            fn main(value: Int) -> Int {
              value |> build(1, <caret>2)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("build(left: Int, <b>right: Int</b>) -&gt; fn(Int) -&gt; Int", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun showsSignatureForInvocationOfCallableProducedByPipeCapture() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            fn build(left: Int, middle: Int, right: Int) -> Int {
              left + middle + right
            }

            fn main(value: Int) -> Int {
              (value |> build(_, 2))(<caret>3)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("fn(<b>middle: Int</b>) -&gt; Int", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun showsSignatureForGroupedDirectPartialApplicationInvocation() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            fn add(left: Int, right: Int) -> Int {
              left + right
            }

            fn main() -> Int {
              (add(1, _))(<caret>2)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("fn(<b>right: Int</b>) -&gt; Int", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun showsSignatureForGroupedDirectAnonymousLambdaInvocation() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            fn main() -> Int {
              (fn(seed: Int, factor: Int) -> Int {
                seed * factor
              })(1, <caret>2)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("fn(seed: Int, <b>factor: Int</b>) -&gt; Int", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun showsSignatureForDirectReturnedCallableInvocation() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            pub type Foo {
              i: Int,
              b: Bool,
            }

            fn foo_i(i: Int) -> fn(Bool) -> Foo {
              Foo { i: i, b: _bool }
            }

            fn main() -> Foo {
              foo_i(42)(<caret>True)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("fn(<b>Bool</b>) -&gt; Foo", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun showsSignatureForCallableLocalBindingToReturnedCallable() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            pub type Foo {
              i: Int,
              b: Bool,
            }

            fn foo_i(i: Int) -> fn(Bool) -> Foo {
              Foo { i: i, b: _bool }
            }

            fn main() -> Foo {
              let bar = foo_i(14)
              bar(<caret>False)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("fn(<b>Bool</b>) -&gt; Foo", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun showsSignatureForCallableConst() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            pub type Foo {
              i: Int,
              b: Bool,
            }

            const give_i: fn(Int) -> Foo = Foo { i: _, b: True }

            fn main() -> Foo {
              give_i(<caret>1337)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("give_i(<b>Int</b>) -&gt; Foo", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun showsSignatureForImportedCallableConst() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/helpers.ak",
            """
            pub type Foo {
              i: Int,
              b: Bool,
            }

            pub const give_i: fn(Int) -> Foo = Foo { i: _, b: True }
            """.trimIndent()
        )
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            use helpers.{give_i}

            fn main() -> Void {
              let generated = give_i(<caret>1337)
              Void
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("give_i(<b>Int</b>) -&gt; Foo", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun showsSignatureForAnonymousLambdaLocalBinding() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            fn main() -> Int {
              let chooser = fn(seed: Int, factor: Int) -> Int {
                seed * factor
              }

              chooser(1, <caret>2)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("fn(seed: Int, <b>factor: Int</b>) -&gt; Int", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun showsParameterInfoForImportedValidatorNamespaceHandlerWithValidatorParameters() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "validators/contract.ak",
            """
            use aiken/crypto.{VerificationKeyHash}
            use cardano/assets.{PolicyId}
            use cardano/transaction.{Transaction}

            pub type MyRedeemer {
              Mint
              Burn
            }

            validator contract(expected_pubkey: VerificationKeyHash) {
              mint(redeemer: MyRedeemer, _policy_id: PolicyId, tx: Transaction) {
                expect Transaction { extra_signatories: [signature], .. } = tx

                and {
                  (signature == expected_pubkey)?,
                  when redeemer is {
                    Mint -> True
                    Burn -> False
                  }?,
                }?
              }

              else(_) {
                fail
              }
            }
            """.trimIndent()
        )
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            use aiken/crypto.{VerificationKeyHash}
            use cardano/transaction.{Transaction}
            use contract

            test failed(expected_pubkey: VerificationKeyHash, tx: Transaction) fail {
              contract.contract.mint(<caret>)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals(
            "mint(<b>expected_pubkey: VerificationKeyHash</b>, redeemer: MyRedeemer, _policy_id: PolicyId, tx: Transaction)",
            myFixture.getParameterInfoAtCaret()
        )
    }

    @Test
    fun highlightsHandlerParameterAfterPrependedValidatorParameterForImportedValidatorNamespaceCall() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "validators/contract.ak",
            """
            use aiken/crypto.{VerificationKeyHash}
            use cardano/assets.{PolicyId}
            use cardano/transaction.{Transaction}

            pub type MyRedeemer {
              Mint
              Burn
            }

            validator contract(expected_pubkey: VerificationKeyHash) {
              mint(redeemer: MyRedeemer, _policy_id: PolicyId, tx: Transaction) {
                True
              }

              else(_) {
                False
              }
            }
            """.trimIndent()
        )
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            use aiken/crypto.{VerificationKeyHash}
            use cardano/transaction.{Transaction}
            use contract

            test failed(expected_pubkey: VerificationKeyHash, tx: Transaction) fail {
              contract.contract.mint(expected_pubkey, <caret>)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals(
            "mint(expected_pubkey: VerificationKeyHash, <b>redeemer: MyRedeemer</b>, _policy_id: PolicyId, tx: Transaction)",
            myFixture.getParameterInfoAtCaret()
        )
    }

    @Test
    fun showsSignatureForPartialApplicationLocalBinding() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            fn add(left: Int, right: Int) -> Int {
              left + right
            }

            fn main() -> Int {
              let increment = add(1, _)
              increment(<caret>2)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("fn(<b>right: Int</b>) -&gt; Int", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun showsSignatureForGroupedPartialApplicationLocalBinding() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            fn add(left: Int, right: Int) -> Int {
              left + right
            }

            fn main() -> Int {
              let increment = (add(1, _))
              increment(<caret>2)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("fn(<b>right: Int</b>) -&gt; Int", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun showsSignatureForPipeProducedCallableLocalBinding() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            fn build(left: Int, middle: Int, right: Int) -> Int {
              left + middle + right
            }

            fn main(value: Int) -> Int {
              let next = value |> build(_, 2)
              next(<caret>3)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("fn(<b>middle: Int</b>) -&gt; Int", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun showsSignatureForImportedPartialApplicationLocalBinding() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/math.ak",
            """
            pub fn add(left: Int, right: Int) -> Int {
              left + right
            }
            """.trimIndent()
        )
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            use math.{add}

            fn main() -> Int {
              let increment = add(1, _)
              increment(<caret>2)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("fn(<b>right: Int</b>) -&gt; Int", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun showsSignatureForDirectAnonymousLambdaInvocation() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            fn main() -> Int {
              fn(seed: Int, factor: Int) -> Int {
                seed * factor
              }(1, <caret>2)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("fn(seed: Int, <b>factor: Int</b>) -&gt; Int", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun showsSignatureForReturnedCallableFromDirectAnonymousLambdaInvocation() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            pub type Foo {
              i: Int,
              b: Bool,
            }

            fn main() -> Foo {
              fn(i: Int) -> fn(Bool) -> Foo {
                fn(flag: Bool) -> Foo {
                  Foo { i: i, b: flag }
                }
              }(42)(<caret>True)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("fn(<b>Bool</b>) -&gt; Foo", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun showsSignatureForDirectPartialApplicationInvocation() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            fn add(left: Int, right: Int) -> Int {
              left + right
            }

            fn main() -> Int {
              add(1, _)(<caret>2)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("fn(<b>right: Int</b>) -&gt; Int", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun showsSignatureForMultiHolePartialApplicationLocalBinding() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            fn blend(left: Int, middle: Int, right: Int) -> Int {
              left + middle + right
            }

            fn main() -> Int {
              let chooser = blend(_, 2, _)
              chooser(1, <caret>3)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("fn(left: Int, <b>right: Int</b>) -&gt; Int", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun showsSignatureForDirectMultiHolePartialApplicationInvocation() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            fn blend(left: Int, middle: Int, right: Int) -> Int {
              left + middle + right
            }

            fn main() -> Int {
              blend(_, 2, _)(1, <caret>3)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("fn(left: Int, <b>right: Int</b>) -&gt; Int", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun showsSignatureForImportedDirectPartialApplicationInvocation() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/math.ak",
            """
            pub fn add(left: Int, right: Int) -> Int {
              left + right
            }
            """.trimIndent()
        )
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            use math.{add}

            fn main() -> Int {
              add(1, _)(<caret>2)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("fn(<b>right: Int</b>) -&gt; Int", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun showsSignatureForCallableLocalBindingFromWhenBranches() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            fn has_nft(value: Int, policy_id: ByteArray, asset_name: ByteArray) -> Bool {
              True
            }

            fn has_nft_strict(value: Int, policy_id: ByteArray, asset_name: ByteArray) -> Bool {
              False
            }

            fn main(strict_mode: Bool, value: Int, policy_id: ByteArray, asset_name: ByteArray) -> Bool {
              let check_function =
                when strict_mode is {
                  True -> has_nft_strict
                  False -> has_nft
                }

              check_function(value, <caret>policy_id, asset_name)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        val actual = myFixture.getParameterInfoAtCaret() ?: error("Parameter info was not shown")
        assertTrue("Unexpected parameter info: $actual", actual.contains("has_nft(value: Int, <b>policy_id: ByteArray</b>, asset_name: ByteArray) -&gt; Bool"))
        assertTrue("Unexpected parameter info: $actual", actual.contains("has_nft_strict(value: Int, <b>policy_id: ByteArray</b>, asset_name: ByteArray) -&gt; Bool"))
    }

    @Test
    fun showsSignatureForCallableLocalBindingFromIfBranches() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            fn has_nft(value: Int, policy_id: ByteArray, asset_name: ByteArray) -> Bool {
              True
            }

            fn has_nft_strict(value: Int, policy_id: ByteArray, asset_name: ByteArray) -> Bool {
              False
            }

            fn main(strict_mode: Bool, value: Int, policy_id: ByteArray, asset_name: ByteArray) -> Bool {
              let check_function =
                if strict_mode {
                  has_nft_strict
                } else {
                  has_nft
                }

              check_function(value, <caret>policy_id, asset_name)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        val actual = myFixture.getParameterInfoAtCaret() ?: error("Parameter info was not shown")
        assertTrue("Unexpected parameter info: $actual", actual.contains("has_nft(value: Int, <b>policy_id: ByteArray</b>, asset_name: ByteArray) -&gt; Bool"))
        assertTrue("Unexpected parameter info: $actual", actual.contains("has_nft_strict(value: Int, <b>policy_id: ByteArray</b>, asset_name: ByteArray) -&gt; Bool"))
    }

    @Test
    fun prefersSameFileFunctionSignatureBeforeImportedSameName() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/math.ak",
            """
            pub fn compute(left: Int, right: Int) -> Int {
              left
            }
            """.trimIndent()
        )
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            use math.{compute}

            fn compute(seed: Int) -> Int {
              seed
            }

            fn main() -> Int {
              compute(<caret>1)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("compute(<b>seed: Int</b>) -&gt; Int", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun showsSignatureForQualifiedCallViaModuleAliasAfterImportList() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/first.ak",
            """
            pub fn compute(left, right) {
              left
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "lib/second.ak",
            """
            pub fn helper() {
              Void
            }

            pub fn compute(left, right, carry) {
              left
            }
            """.trimIndent()
        )
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            use second.{helper} as chosen

            fn main() {
              chosen.compute(1, 2, <caret>3)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("compute(left, right, <b>carry</b>)", myFixture.getParameterInfoAtCaret())
    }

    @Test
    fun showsSignatureForImportedItemAlias() {
        myFixture.addFileToProject("aiken.toml", "name = \"demo\"\nversion = \"0.0.0\"\n")
        myFixture.addFileToProject(
            "lib/first.ak",
            """
            pub fn compute(left, right) {
              left
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "lib/second.ak",
            """
            pub fn compute(left, right, carry) {
              left
            }
            """.trimIndent()
        )
        val mainFile = myFixture.addFileToProject(
            "lib/main.ak",
            """
            use second.{compute as chosen}

            fn main() {
              chosen(1, 2, <caret>3)
            }
            """.trimIndent()
        )

        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)

        assertEquals("compute(left, right, <b>carry</b>)", myFixture.getParameterInfoAtCaret())
    }
}
