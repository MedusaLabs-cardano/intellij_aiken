# Aiken IntelliJ Plugin

IntelliJ IDEA plugin that brings first‑class support for **Aiken** — a modern smart‑contract language for the Cardano platform.

## Features
- **File types**: `.ak` (Aiken) and `.uplc` (UPLC)
- **Syntax highlighting**
- **Auto‑formatting** on save (`aiken fmt`)
- **Project‑wide indexing & navigation**, including `std_lib` under `build`
- **Go to Symbol** and **Structure View** for top-level Aiken declarations
- **Sticky lines / breadcrumbs** for scope awareness
- **Completion / suggestions**
- **LSP integration** for diagnostics, hover and code actions

## Requirements
- **JDK 21**
- **Gradle** (wrapper included)

## Build
```bash
./gradlew -q build
```

## Run the plugin in a sandbox IDE
```bash
./gradlew -q runIde
```

## Build a distributable plugin
```bash
./gradlew -q buildPlugin
```

The resulting ZIP will be under `build/distributions/`.

## Notes
- This repository uses the **IntelliJ Platform Gradle Plugin** (Kotlin DSL).
- Aiken navigation, rename, find usages, completion and parameter info are implemented natively on top of IntelliJ references/search APIs.
- Diagnostics, hover and code actions are LSP-owned by default.
- Formatting currently uses `aiken fmt` as the source of truth; native formatter semantics are intentionally not implemented in the plugin.
