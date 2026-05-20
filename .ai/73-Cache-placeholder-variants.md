# 73 Cache placeholder variants

## Context

- Worktree: `/home/daniel/idp/ora/helidon/intellij-plugin-worktrees/issue85-work`
- Branch: `kec/73-placeholder-variant-cache`
- PR: `#85`
- Issue: `#73`

## Review Comments

- Cache the real `LookupElement.lookupString` as insertion text.
- Keep presentable text separately and reapply it when rebuilding lookup elements.
- Do not use `PsiFile` instances as module cached-value dependencies.
- Add a regression test proving placeholder completion cache invalidates after config file changes.
- Replace global PSI modification invalidation with a narrower Helidon-config-file-only invalidation dependency.
- Do not recompute config key signatures for unchanged tracked files on every cached-value dependency query.
- Do not cache YAML value-derived lookup presentation fields when the tracker intentionally ignores value-only edits.

## Changes

- `CachedConfigKeyVariant` now stores `lookupString`, optional `presentableText`, `typeText`, and `icon`.
- Recreated lookup elements use `lookupString` for insertion and `withPresentableText(...)` only for a distinct rendered item text.
- Placeholder variant cache dependencies now use `HelidonConfigFileModificationTracker`, `ProjectRootModificationTracker`, and `VFS_STRUCTURE_MODIFICATIONS`.
- `HelidonConfigFileModificationTracker` tracks key signatures for files accepted by existing Helidon config file detection; scalar YAML value edits do not advance the tracker, while key edits do.
- Added `testPlaceholderReferenceCompletionInvalidatesAfterConfigFileChange`, which populates completion from `application-dev.yml`, edits that contributing file, reruns completion, and asserts the variant list updates.
- Added `testHelidonConfigFileModificationTrackerTracksOnlyConfigKeyChanges`, which verifies Java source edits and YAML scalar value edits do not invalidate the Helidon config tracker, while YAML key edits do.
- `HelidonConfigFileModificationTracker` now records each tracked file stamp and skips PSI lookup/key-signature recomputation when the stamp has not changed.
- Cached placeholder variants now store only the stable lookup string and rebuild lookup elements without value-derived YAML `typeText`.
- Added `testPlaceholderReferenceCompletionDoesNotCacheYamlValuePresentation` for the value-presentation staleness case.

## Validation

- `./gradlew test ...` could not start because this worktree does not contain `gradle/wrapper/gradle-wrapper.jar`.
- `gradle test ...` with only `TMPDIR`/`JAVA_TOOL_OPTIONS` could not initialize Gradle native services.
- Passed focused test run with repo-local `GRADLE_USER_HOME`, `TMPDIR`, and `XDG_DATA_HOME`:
  - `HelidonYamlConfigTest.testPlaceholderReferenceCompletion`
  - `HelidonYamlConfigTest.testPlaceholderReferenceCompletionWithNestedPrefix`
  - `HelidonYamlConfigTest.testPlaceholderReferenceCompletionInvalidatesAfterConfigFileChange`
  - `HelidonYamlConfigTest.testHelidonConfigFileModificationTrackerTracksOnlyConfigKeyChanges`
- 2026-05-20 second follow-up:
  - First rerun exposed two failing cache-invalidation tests because file stamps did not include the PSI view-provider stamp; fixed by including `file.viewProvider.modificationStamp`.
  - One retry failed before tests with an IntelliJ Platform Gradle `ClosedFileSystemException` while resolving `intellijPlatformTestRuntimeFixClasspath`.
  - Final rerun passed: `GRADLE_USER_HOME=$PWD/.gradle-home XDG_DATA_HOME=$PWD/.xdg-data TMPDIR=$PWD/.gradle-tmp JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=$PWD/.gradle-tmp gradle test --tests com.intellij.helidon.config.yaml.HelidonYamlConfigTest --no-daemon --no-configuration-cache`.

## Commit

- Pending follow-up commit for the 2026-05-20 review comment.

## 2026-05-20 Fifth Review Follow-up

Live head before changes: `a7f18ca6ea8c5eb58ad3492c69e397ae6f5cbccb`.

Unresolved review comment to address:

- Avoid duplicate PSI traversal in `collectKeyVariants()` by computing placeholder key variants once and passing a precomputed key signature to `HelidonConfigFileModificationTracker`.

Changes:

- Added a `HelidonConfigFileModificationTracker.track(file, keySignature)` overload while keeping `track(file)` for callers/tests that need the tracker to compute the signature from PSI.
- Shared key-signature normalization for iterable/sequence lookup strings and tracker recomputation.
- `collectKeyVariants()` now calls `contributor.getKeyVariants(psiFile)` once, derives the tracked signature from the resulting `LookupElement.lookupString` values, then caches the same lookup elements.
- Adjusted the key-order/sequence-item tracker regression test to use the precomputed-signature path and verify later tracker recomputation remains aligned.

Validation:

- `GRADLE_USER_HOME=$PWD/.gradle-home XDG_DATA_HOME=$PWD/.xdg-data TMPDIR=$PWD/.gradle-tmp JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=$PWD/.gradle-tmp gradle test --tests com.intellij.helidon.config.yaml.HelidonYamlConfigTest --tests com.intellij.helidon.config.properties.HelidonPropertiesConfigTest --no-daemon --no-configuration-cache`
  - Passed on 2026-05-20.

## 2026-05-20 Third Review Follow-up

Live head before changes: `082ade16cbec8c1c43fb2aeb8c828b93738920ec`.

Unresolved review comments to address:

- Preserve contributor-specific lookup elements when serving cached placeholder variants.
- Guard `HelidonConfigFileModificationTracker.getModificationCount()` PSI reads with read access.
- Make config key signatures order-insensitive and aligned with the actual placeholder variant set.
- Strengthen the YAML value-presentation regression test so it asserts the rendered value changes.

Changes:

- Cached placeholder variants now store smart pointers to YAML key/value or properties PSI and recreate contributor-specific lookup elements.
- YAML cached variants use the original YAML placeholder renderer, so value `typeText` is read from current PSI without cache invalidation on value-only edits.
- Properties cached variants recreate `PropertiesCompletionContributor` variants instead of degrading to plain string lookup elements.
- `HelidonConfigFileModificationTracker.getModificationCount()` now performs PSI lookup/signature work under read access.
- YAML/properties key signatures are de-duplicated and sorted; YAML signatures now match the keys offered by placeholder completion by excluding sequence-item keys and non-scalar/non-sequence values.
- Strengthened tests for YAML value-presentation updates, properties lookup object preservation, and key-order/sequence-item tracker stability.

Validation:

- `GRADLE_USER_HOME=$PWD/.gradle-home XDG_DATA_HOME=$PWD/.xdg-data TMPDIR=$PWD/.gradle-tmp JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=$PWD/.gradle-tmp gradle test --tests com.intellij.helidon.config.yaml.HelidonYamlConfigTest --tests com.intellij.helidon.config.properties.HelidonPropertiesConfigTest --no-daemon --no-configuration-cache`
  - Passed on 2026-05-20.

## 2026-05-20 Fourth Review Follow-up

Live head before changes: `2ccc4af7bc2432519128f2bc536a63496369ec9f`.

Unresolved review comments to address:

- Use `VirtualFile` rather than `VirtualFile.url` strings as modification-tracker map keys so renamed/moved files do not leave stale duplicate entries.
- Hoist project-scoped `SmartPointerManager` lookup out of the per-config-file loop.

Changes:

- `HelidonConfigFileModificationTracker` now keys stamps and signatures by `VirtualFile`, avoiding stale URL-keyed entries after file rename or move.
- `collectKeyVariants()` now resolves the project-scoped `SmartPointerManager` once per cache build instead of once per config file.

Validation:

- `GRADLE_USER_HOME=$PWD/.gradle-home XDG_DATA_HOME=$PWD/.xdg-data TMPDIR=$PWD/.gradle-tmp JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=$PWD/.gradle-tmp gradle test --tests com.intellij.helidon.config.yaml.HelidonYamlConfigTest --tests com.intellij.helidon.config.properties.HelidonPropertiesConfigTest --no-daemon --no-configuration-cache`
  - Passed on 2026-05-20.
