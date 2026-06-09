package com.medusalabs.aiken.ui

import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.JTextArea

class AdaptiveWrapText(
    text: String = "",
    private val maxWrapWidth: Int = JBUI.scale(420)
) : JTextArea(text) {
    init {
        isEditable = false
        isFocusable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty()
        margin = JBUI.emptyInsets()
        columns = 0
    }

    override fun getMinimumSize(): Dimension = Dimension(0, super.getMinimumSize().height)

    override fun getPreferredSize(): Dimension {
        val availableWidth = parent?.width?.takeIf { it > 0 }
            ?: width.takeIf { it > 0 }
            ?: maxWrapWidth
        val wrapWidth = minOf(availableWidth, maxWrapWidth)
        val previousSize = size
        setSize(wrapWidth, Short.MAX_VALUE.toInt())
        val preferredSize = super.getPreferredSize()
        size = previousSize
        return Dimension(wrapWidth, preferredSize.height)
    }
}
