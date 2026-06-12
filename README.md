# Aiken IntelliJ Plugin

JetBrains IDE plugin for [Aiken](https://aiken-lang.org/), the smart-contract language for Cardano.

The plugin provides project creation, managed Aiken toolchains, runners, semantic editor support, LSP diagnostics, and blueprint workflows directly inside IntelliJ-based IDEs.

## Features

### Project and Toolchain

- Aiken entry in the IDE `New Project` wizard.
- Project type, Aiken version, and compatible `stdlib` version selection.
- Global toolchain mode for an existing `aiken` binary.
- Project-local toolchain mode that installs `@aiken-lang/aiken` through npm.
- Automatic synchronization between the selected local Aiken version and `aiken.toml`.
- First-open initialization of default Aiken run configurations.
- IDE terminal integration so project-local Aiken binaries take precedence when enabled.

### Editor Support

- `.ak` and `.uplc` file types.
- Syntax highlighting.
- Brace, folding, breadcrumbs, and sticky-line support.
- Save formatting through `aiken fmt`.
- `New -> Aiken File` templates for common modules, validators, and tests.

### Code Intelligence

- Semantic completion for expressions, type annotations, generic arguments, imports, module-qualified calls, records, destructuring, validators, handlers, `when`, `if`, pipes, and callable locals.
- Expected-type aware ranking with scope-distance, import-state, alias, generic, and visibility handling.
- Built-in Aiken types and constructors in relevant completion contexts.
- Auto-import for selected indexed exports.
- `Ctrl+P` parameter info for imported functions, aliases, callable locals, partial application, pipes, validators, and subvalidators.
- Navigation, rename, and find usages for Aiken symbols across files and modules.
- `Go to Symbol` and Structure View for top-level declarations.

### LSP Integration

- Diagnostics, hover, and code actions are provided by the Aiken language server.
- LSP quick fixes are surfaced in the IDE.
- Bulk `Remove all unused imports` is available on top of the ordinary unused-import quick fix.

### Runners and Blueprint Workflows

- Dedicated Aiken run configuration type.
- `Run checks` for `aiken check` with grouped diagnostics and source navigation.
- `Build blueprint` for `aiken build` with IDE output parsing.
- `Parametrize blueprint` UI for applying validator parameters.
- `Make artifacts` and `Clean artifacts` workflows.
- Structured parameter editor for nested constructors, lists, maps, options, byte arrays, integers, booleans, and raw values.

## Requirements

- JDK 21.
- Gradle wrapper from this repository.
- Node.js and npm in `PATH`, or configured through IDE Node.js settings, when using locally managed Aiken toolchains.
- A global `aiken` command in `PATH` when using global toolchain mode.

## Development

Build the plugin:

```bash
./gradlew -q build
```

Run the plugin in a sandbox IDE:

```bash
./gradlew -q runIde
```

Build a distributable plugin ZIP:

```bash
./gradlew -q buildPlugin
```

The plugin ZIP is written to `build/distributions/`.

Run tests:

```bash
./gradlew test
```

For memory-heavy full test runs, use a larger Gradle/Kotlin heap:

```bash
./gradlew \
  "-Dorg.gradle.jvmargs=-Xmx8g -XX:MaxMetaspaceSize=1g" \
  "-Dkotlin.daemon.jvmargs=-Xmx8g" \
  test
```

## Architecture Notes

- Native IDE behavior is implemented with IntelliJ PSI, references, search APIs, indices, completion contributors, parameter-info handlers, run configurations, and project services.
- The Aiken compiler and LSP remain the source of truth for formatting, diagnostics, hover, and ordinary code actions.
- Formatting intentionally delegates to `aiken fmt`; the plugin does not reimplement Aiken formatter semantics.
- Semantic completion, navigation, rename, usages, parameter info, and Structure View are implemented natively for IDE responsiveness and richer context handling.

## Release Notes

- `update_en.md` contains the plain changelog for Marketplace and moderation.
- `src/main/resources/whatsnew/latest/index.html` contains the visual in-IDE What's New page.

## Feedback

If you have feedback, suggestions, or found a bug, please open an issue on GitHub:

https://github.com/MedusaLabs-cardano/intellij_aiken
