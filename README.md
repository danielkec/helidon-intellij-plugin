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

The current build targets IntelliJ IDEA `2026.1.1`, plugin version `262.0.10`,
and Java `21`.

## Compatibility

This release line is published for IntelliJ IDEA `2026.1` builds (`261.*`).
The same bound is declared in Gradle plugin metadata and in
`docs/updatePlugins.xml`.

Before widening the supported IDE range, run `./gradlew verifyPlugin` against
the new target IDE build and update both `build.gradle.kts` and
`docs/updatePlugins.xml` in the same release.

IntelliJ IDEA `2026.1` is distributed as the unified IDEA product for plugin
verification; IntelliJ IDEA Community verifier artifacts are no longer published
for this line. `./gradlew verifyPlugin` therefore verifies the unified `IU`
target, while descriptor tests cover that the base plugin descriptor does not
hard-require the optional Microservices integration.

To exercise the runtime path where the optional Microservices integration is not
available, launch a sandbox with that bundled plugin disabled:

```bash
./gradlew runIdeWithoutMicroservices
```

The base plugin descriptor is compatible with IntelliJ IDEA Community. Helidon
configuration, Endpoints, URL resolver, URL inlay, and path-variable support are
loaded from an optional microservices descriptor when `com.intellij.microservices.jvm`
is available.

Java is the only supported and tested source language for Helidon source-code
assistance. Kotlin Helidon source support is intentionally not registered; any
behavior exposed indirectly through shared IntelliJ UAST APIs is incidental and
unsupported. The Kotlin Gradle plugin remains only because the plugin
implementation and tests are written in Kotlin.

## Distribution Identity

This fork intentionally keeps the inherited plugin id `com.intellij.helidon`
while publishing Helidon team as the plugin vendor. Keeping the id preserves the
same plugin identity for users installing from the custom update repository.

Do not publish this fork to JetBrains Marketplace under the inherited
`com.intellij` id. `verifyPluginStructure` can warn that this prefix is reserved
for JetBrains-owned plugins. The supported distribution channel for this fork is
the custom plugin repository listed in the install section.

Changing the id would be a new plugin identity migration. If that becomes
necessary, update `resources/META-INF/plugin.xml`, `docs/updatePlugins.xml`,
Gradle metadata, release notes, and install instructions together so users
understand the migration path.

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

- The plugin compiles against the IntelliJ IDEA 2026.1 SDK with optional
  microservices APIs available at build time. Runtime microservices integrations
  are declared in optional descriptor files under `resources/META-INF`.
- `gen/` is part of the main source set and should be kept under version control.
- Test-only IntelliJ platform dependencies are declared with `testBundledPlugin` so
  they do not leak into the runtime sandbox classpath.
