package com.txpipe.aiken.actions

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Replaces Reformat actions once when the first project opens.
 * Uses stable StartupActivity API instead of ApplicationInitializedListener.
 */
class AikenReformatStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        patchActionsOnce()
    }

    companion object {
        private val patched = AtomicBoolean(false)
        private val log = Logger.getInstance(AikenReformatStartupActivity::class.java)

        private fun patchActionsOnce() {
            if (!patched.compareAndSet(false, true)) return

            val actionManager = ActionManager.getInstance()
            val baseReformat = actionManager.getAction("ReformatCode")
            val baseEditor = actionManager.getAction("EditorReformat")

            baseReformat?.let {
                actionManager.replaceAction("ReformatCode", AikenReformatAction(it))
                log.info("AikenReformatStartupActivity: replaced action ReformatCode with AikenReformatAction")
            } ?: log.warn("AikenReformatStartupActivity: no action found for id=ReformatCode")

            when {
                baseEditor != null -> {
                    actionManager.replaceAction("EditorReformat", AikenReformatAction(baseEditor))
                    log.info("AikenReformatStartupActivity: replaced action EditorReformat with AikenReformatAction")
                }
                baseReformat != null && actionManager.getAction("EditorReformat") == null -> {
                    actionManager.registerAction("EditorReformat", AikenReformatAction(baseReformat))
                    log.info("AikenReformatStartupActivity: registered AikenReformatAction as EditorReformat")
                }
                else -> log.warn("AikenReformatStartupActivity: unable to install EditorReformat wrapper (no base action)")
            }
        }
    }
}
