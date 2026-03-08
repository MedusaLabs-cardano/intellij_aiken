package com.medusalabs.aiken.naming

import com.medusalabs.aiken.highlight.lexer.AikenLexing
import java.util.Locale

internal object AikenNamingRules {
    // Mirrors `aiken_project::package_name::PackageName::validate`.
    private val packageTokenRegex = Regex("^[a-z0-9_-]+$")

    // Mirrors `aiken_project::is_aiken_path` for a single module segment with optional dotted suffixes.
    private val moduleFileStemRegex = Regex("^[a-z][-_a-z0-9]*(\\.[-_a-z0-9]*)*$")

    private val reservedKeywords: Set<String> = AikenLexing.keywords

    fun normalizePackageToken(raw: String): String {
        val lowered = raw.lowercase(Locale.US)
        val mapped = lowered.map { ch ->
            when {
                ch in 'a'..'z' -> ch
                ch in '0'..'9' -> ch
                ch == '_' || ch == '-' -> ch
                else -> '-'
            }
        }.joinToString("")
        return mapped.replace(Regex("-+"), "-").trim('-')
    }

    fun requireValidPackageToken(label: String, value: String) {
        if (value.isBlank()) {
            throw IllegalStateException("$label is required")
        }
        if (!packageTokenRegex.matches(value)) {
            throw IllegalStateException("$label must match [a-z0-9_-]+ (lowercase, no spaces)")
        }
    }

    fun validateAikenFileName(rawName: String): String? {
        val value = rawName.trim()
        if (value.isBlank()) return "File name is required."
        if (value == "." || value == "..") return "Invalid file name."
        if (value.contains('/') || value.contains('\\')) {
            return "File name must not contain path separators."
        }

        val stem = if (value.endsWith(".ak")) value.removeSuffix(".ak") else value
        if (stem.isBlank()) {
            return "File name must contain a module name before `.ak`."
        }

        if (!moduleFileStemRegex.matches(stem)) {
            return "File name must match [a-z][-_a-z0-9]*(\\.[-_a-z0-9]*)* (lowercase only)."
        }

        for (segment in stem.split('.')) {
            if (segment.isNotBlank() && reservedKeywords.contains(segment)) {
                return "File name contains reserved keyword `$segment`."
            }
        }

        return null
    }
}

