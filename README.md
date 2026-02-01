# Aiken IntelliJ Plugin

IntelliJ IDEA plugin that brings first‑class support for **Aiken** — a modern smart‑contract language for the Cardano platform.

## Features
- **File types**: `.ak` (Aiken) and `.uplc` (UPLC)
- **Syntax highlighting**
- **Auto‑formatting** on save (`aiken fmt`)
- **Project‑wide indexing & navigation**, including `std_lib` under `build`
- **Sticky lines / breadcrumbs** for scope awareness
- **Completion / suggestions**
- **LSP integration** for diagnostics

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
- LSP‑based diagnostics are surfaced in the editor and project view.

## License
Internal / private use unless stated otherwise.
