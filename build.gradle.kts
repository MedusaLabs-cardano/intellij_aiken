import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "com.medusalabs"
version = "2.0"

fun escapeChangeNotesHtml(text: String): String =
    text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

fun inlineChangeNotesCode(text: String): String =
    Regex("`([^`]*)`").replace(escapeChangeNotesHtml(text)) { match ->
        "<code>${match.groupValues[1]}</code>"
    }

fun markdownChangeNotesToHtml(markdown: String): String {
    val html = StringBuilder()
    var inList = false

    fun closeList() {
        if (inList) {
            html.appendLine("</ul>")
            inList = false
        }
    }

    markdown.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        when {
            line.isEmpty() -> closeList()
            line.startsWith("### ") -> {
                closeList()
                html.appendLine("<h3>${inlineChangeNotesCode(line.removePrefix("### ").trim())}</h3>")
            }
            line.startsWith("## ") -> {
                closeList()
                html.appendLine("<h2>${inlineChangeNotesCode(line.removePrefix("## ").trim())}</h2>")
            }
            line.startsWith("# ") -> {
                closeList()
                html.appendLine("<h1>${inlineChangeNotesCode(line.removePrefix("# ").trim())}</h1>")
            }
            line.startsWith("- ") -> {
                if (!inList) {
                    html.appendLine("<ul>")
                    inList = true
                }
                html.appendLine("<li>${inlineChangeNotesCode(line.removePrefix("- ").trim())}</li>")
            }
            else -> {
                closeList()
                html.appendLine("<p>${inlineChangeNotesCode(line)}</p>")
            }
        }
    }

    closeList()
    return html.toString().trim()
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    compileOnly(kotlin("stdlib"))
    testCompileOnly(kotlin("stdlib"))
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdeaUltimate("2025.2.4")
        bundledPlugin("org.jetbrains.plugins.terminal")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Keep dependency surface minimal; custom lexer handles syntax
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252.25557"
        }

        changeNotes = markdownChangeNotesToHtml(file("update_en.md").readText())
    }

    publishing {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    withType<RunIdeTask> {
        systemProperty("remote.x11.workaround", false)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
