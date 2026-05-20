# Issue 76: Cache service usage targets

## Context

PR #88 review follow-up for `kec/76-service-usage-cache`.

## Review comments addressed

- Replaced string-derived service contract cache keys with `PsiClass` keys so anonymous or in-memory contracts do not share an empty or text-range-only fallback key.
- Renamed the cache key debug name to `HELIDON_SERVICE_USAGE_TARGETS_BY_CONTRACT_KEY`.
- Stored an immutable copy of each calculated contract target set in the cache.
- Added project-root modifications to the module cache dependencies.
- Hardened test fixture edits by asserting `indexOf(...)` lookups before inserting or deleting text.

## Validation

- Passed: `env TMPDIR=/home/daniel/idp/ora/helidon/intellij-plugin-worktrees/issue88-work/.gradle-tmp JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/home/daniel/idp/ora/helidon/intellij-plugin-worktrees/issue88-work/.gradle-tmp GRADLE_USER_HOME=/home/daniel/idp/ora/helidon/intellij-plugin-worktrees/issue88-work/.gradle-home XDG_DATA_HOME=/home/daniel/idp/ora/helidon/intellij-plugin-worktrees/issue88-work/.xdg-data gradle test --tests com.intellij.helidon.providers.HelidonClassAnnotatorTest.testSharedContractUsageTargetsAreFilteredPerService --tests com.intellij.helidon.providers.HelidonClassAnnotatorTest.testServiceUsageTargetsInvalidateAfterAddingAndRemovingInjectionAndLookup --no-daemon --no-configuration-cache`
- Note: the requested `./gradlew ...` command failed before running tests because this checkout does not include `gradle/wrapper/gradle-wrapper.jar`; `gradle/wrapper/gradle-wrapper.properties` is the only tracked wrapper file.
