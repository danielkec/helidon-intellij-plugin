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

The current build targets IntelliJ IDEA `2026.1.1`, plugin version `262.0.6`,
and Java `21`.

## Compatibility

This release line is published for IntelliJ IDEA `2026.1` builds (`261.*`).
The same bound is declared in Gradle plugin metadata and in
`docs/updatePlugins.xml`.

Before widening the supported IDE range, run `./gradlew verifyPlugin` against
the new target IDE build and update both `build.gradle.kts` and
`docs/updatePlugins.xml` in the same release.

## Distribution Identity

This fork intentionally keeps the inherited plugin id `com.intellij.helidon` and
vendor metadata from the extracted JetBrains plugin. Keeping the id preserves the
same plugin identity for users installing from the custom update repository and
avoids creating a separate Helidon plugin lineage.

Do not publish this fork to JetBrains Marketplace under the inherited
`com.intellij` id. `verifyPluginStructure` can warn that this prefix is reserved
for JetBrains-owned plugins. The supported distribution channel for this fork is
the custom plugin repository listed in the install section.

Changing the id or vendor would be a new plugin identity migration. If that
becomes necessary, update `resources/META-INF/plugin.xml`,
`docs/updatePlugins.xml`, Gradle metadata, release notes, and install
instructions together so users understand the migration path.

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
