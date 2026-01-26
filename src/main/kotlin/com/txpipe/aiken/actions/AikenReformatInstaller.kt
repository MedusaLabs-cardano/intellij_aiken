package com.txpipe.aiken.actions

import com.intellij.ide.ApplicationInitializedListener
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.diagnostic.Logger

/**
 * Runs after application init and replaces Reformat actions with our wrapper.
 * If EditorReformat action is missing, registers a wrapper under that id too.
 */
class AikenReformatInstaller : ApplicationInitializedListener {
    private val log = Logger.getInstance(AikenReformatInstaller::class.java)
    private val ids = listOf("ReformatCode", "EditorReformat")

    override fun componentsInitialized() {
        log.info("AikenReformatInstaller: componentsInitialized, patching existing actions")
        patchExisting()
    }

    // Newer IDEs call execute(); keep both to satisfy all platform versions.
    override suspend fun execute() {
        log.info("AikenReformatInstaller: execute(), patching existing actions")
        patchExisting()
    }

    private fun patchExisting() {
        val actionManager = ActionManager.getInstance()

        // Capture originals before replacing.
        val baseReformat = actionManager.getAction("ReformatCode")
        val baseEditor = actionManager.getAction("EditorReformat")

        baseReformat?.let {
            actionManager.replaceAction("ReformatCode", AikenReformatAction(it))
            log.info("AikenReformatInstaller: replaced action ReformatCode with AikenReformatAction")
        } ?: log.warn("AikenReformatInstaller: no action found for id=ReformatCode")

        when {
            baseEditor != null -> {
                actionManager.replaceAction("EditorReformat", AikenReformatAction(baseEditor))
                log.info("AikenReformatInstaller: replaced action EditorReformat with AikenReformatAction")
            }
            baseReformat != null && actionManager.getAction("EditorReformat") == null -> {
                actionManager.registerAction("EditorReformat", AikenReformatAction(baseReformat))
                log.info("AikenReformatInstaller: registered AikenReformatAction as EditorReformat")
            }
            else -> {
                log.warn("AikenReformatInstaller: unable to install EditorReformat wrapper (no base action)")
            }
        }
    }
}
