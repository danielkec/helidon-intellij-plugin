# Helidon IntelliJ Plugin

This is the extracted Helidon plugin from JetBrains' `intellij-obsolete-plugins` repository.

## Provenance

- Upstream repository: `JetBrains/intellij-obsolete-plugins`
- Upstream path: `helidon/`
- Imported commit: `b7fc718c8815aaa9f593096a469bda51e7cbcb4a`
- Imported commit date: `2026-03-31 16:25:44 +0200`

## Install

Add this custom plugin repository in IntelliJ IDEA:

```text
https://danielkec.github.io/helidon-intellij-plugin/updatePlugins.xml
```

Use `Settings | Plugins | gear icon | Manage Plugin Repositories... | +`,
then install `Helidon` from the Plugins dialog.

## Build

This project is a standalone Gradle IntelliJ Platform plugin project.

```bash
./gradlew buildPlugin
```

The current build targets IntelliJ IDEA `2026.1.1`, plugin version `262.0.0`,
and Java `21`.

## Tests

Tests no longer hardcode the original JetBrains developer machine path. To provide an
IDE checkout for tests that need `idea.home.path`, use either a Gradle property:

```bash
./gradlew test -Pidea.home.path=/path/to/intellij/community
```

or an environment variable:

```bash
IDEA_HOME_PATH=/path/to/intellij/community ./gradlew test
```

## Notes

- The plugin declares several IntelliJ Ultimate/platform bundled dependencies.
- `gen/` is part of the main source set and should be kept under version control.
- Test-only IntelliJ platform dependencies are declared with `testBundledPlugin` so
  they do not leak into the runtime sandbox classpath.
- The inherited plugin id is still `com.intellij.helidon`. `verifyPluginStructure`
  warns that this uses JetBrains' reserved `com.intellij` prefix, but changing it
  would create a new Marketplace plugin identity.
