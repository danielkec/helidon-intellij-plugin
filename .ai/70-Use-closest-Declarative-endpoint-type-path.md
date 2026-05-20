# 70 Use closest Declarative endpoint type path

## 2026-05-20

- Worker: PR82.
- Scope: dedicated issue 70 worktree.
- Branch: `kec/70-declarative-closest-path`.
- Addressed unresolved review comments locally; no GitHub threads resolved and no push performed.
- Changed `HelidonCommonUtils.findClosestMethodHierarchyTypePaths` to return only the first non-empty method-hierarchy distance level of type paths.
- Added closest interface-type path lookup so a subinterface `@Http.Path` applies to inherited endpoint methods before a farther base interface path.
- Cached the closest endpoint class/superclass `@Http.Path` lookup once per endpoint class before method iteration.
- Added a regression test for a subinterface `@Http.Path` overriding a base-interface `@Http.Path` for an inherited Declarative endpoint method.
- Tightened the multiple-interface negative assertions so both invalid cross-pair path combinations are rejected.
- Validation:
  - `./gradlew ...` could not run because `gradle/wrapper/gradle-wrapper.jar` is absent in this worktree.
  - `gradle test --tests com.intellij.helidon.providers.HelidonWebServerEndpointTest --no-daemon --no-configuration-cache` passed.
  - `git diff --check` passed.
