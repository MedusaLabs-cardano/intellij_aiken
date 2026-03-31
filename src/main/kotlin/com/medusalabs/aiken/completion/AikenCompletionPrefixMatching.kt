package com.medusalabs.aiken.completion

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.util.text.matching.KeyboardLayoutUtil

object AikenCompletionPrefixMatching {
    fun matches(candidate: String, prefixMatcher: PrefixMatcher, explicitPrefix: String = prefixMatcher.prefix): Boolean {
        if (prefixMatcher.prefixMatches(candidate)) return true
        val mappedPrefix = mapByKeyboardLayout(explicitPrefix) ?: return false
        return candidate.startsWith(mappedPrefix, ignoreCase = true)
    }

    private fun mapByKeyboardLayout(prefix: String): String? {
        if (prefix.isEmpty()) return null

        var changed = false
        val builder = StringBuilder(prefix.length)
        for (ch in prefix) {
            val mapped = KeyboardLayoutUtil.getAsciiForChar(ch)
            if (mapped != null) {
                builder.append(mapped)
                if (mapped != ch) changed = true
            } else {
                builder.append(ch)
            }
        }
        return builder.toString().takeIf { changed }
    }
}
