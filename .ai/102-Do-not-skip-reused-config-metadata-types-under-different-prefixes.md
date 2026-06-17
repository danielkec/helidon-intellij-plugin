# 102 Do not skip reused config metadata types under different prefixes

## Issue

- GitHub issue: https://github.com/danielkec/helidon-intellij-plugin/issues/102
- Branch: `kec/102-reused-config-metadata-types`
- Base: `origin/main` at `c40217e`

## Problem

`HelidonConfigMetadataBuilder` keeps one processed-type set for an entire root traversal. When a nested `VALUE`
config type is reused under two sibling options, the second sibling is skipped and does not contribute metadata
keys under its own prefix.

The CE config-key builder already uses a stack-style visiting set and removes the type on unwind, which allows
safe reuse across siblings while still stopping recursive type cycles.

## Plan

- Change the metadata builder guard from permanent processed types to stack-local visiting types.
- Keep recursive and cyclic metadata graphs guarded with `try`/`finally` unwinding.
- Add a focused builder test where one nested type is reused by two sibling prefixes and also contains a
  self-reference.

## Validation

- Passed: `git diff --check`
- Passed: `GRADLE_USER_HOME=$PWD/.gradle-home XDG_DATA_HOME=$PWD/.xdg-data TMPDIR=$PWD/.gradle-tmp JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=$PWD/.gradle-tmp ./gradlew test --tests com.intellij.helidon.config.HelidonConfigMetadataBuilderTest --no-daemon --no-configuration-cache`
- Note: this repo has no `etc/scripts/copyright.sh` or `etc/scripts/checkstyle.sh`.
