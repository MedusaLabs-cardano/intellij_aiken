package com.medusalabs.aiken.tooling

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

enum class AikenToolchainMode(private val label: String) {
    LOCAL("Install locally (recommended)"),
    GLOBAL("Use global Aiken");

    override fun toString(): String = label
}

@Service(Service.Level.PROJECT)
@State(name = "AikenProjectToolchainSettings", storages = [Storage("aiken-toolchain.xml")])
class AikenProjectToolchainSettings : PersistentStateComponent<AikenProjectToolchainSettings.State> {
    data class State(
        var mode: AikenToolchainMode = AikenToolchainMode.LOCAL,
        var globalAikenCommand: String = "aiken",
        var localAikenVersion: String = AikenNodeToolchain.DEFAULT_AIKEN_VERSION
    )

    private var state = State()

    override fun getState(): State = state.copy()

    override fun loadState(state: State) {
        this.state = state.copy(
            globalAikenCommand = state.globalAikenCommand.trim().ifEmpty { "aiken" },
            localAikenVersion = AikenNodeToolchain.normalizeRequestedVersion(state.localAikenVersion)
        )
    }

    fun getMode(): AikenToolchainMode = state.mode

    fun getGlobalAikenCommand(): String = state.globalAikenCommand.trim().ifEmpty { "aiken" }

    fun getLocalAikenVersion(): String = AikenNodeToolchain.normalizeRequestedVersion(state.localAikenVersion)

    fun update(mode: AikenToolchainMode, globalAikenCommand: String, localAikenVersion: String) {
        state = State(
            mode = mode,
            globalAikenCommand = globalAikenCommand.trim().ifEmpty { "aiken" },
            localAikenVersion = AikenNodeToolchain.normalizeRequestedVersion(localAikenVersion)
        )
    }
}
