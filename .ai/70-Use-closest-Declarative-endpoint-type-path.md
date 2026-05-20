# 70 Use closest Declarative endpoint type path

## 2026-05-20

- Worker: PR82.
- Scope: `/home/daniel/idp/ora/helidon/intellij-plugin-worktrees/issue70-work`.
- Branch: `kec/70-declarative-closest-path`.
- Addressed unresolved review comments locally; no GitHub threads resolved and no push performed.
- Changed `HelidonCommonUtils.findClosestMethodHierarchyTypePaths` to return only the first non-empty method-hierarchy distance level of type paths.
- Added closest interface-type path lookup so a subinterface `@Http.Path` applies to inherited endpoint methods before a farther base interface path.
- Added a regression test for a subinterface `@Http.Path` overriding a base-interface `@Http.Path` for an inherited Declarative endpoint method.
- Tightened the multiple-interface negative assertions so they constrain HTTP method, parent path, and method path.
- Validation:
  - `./gradlew ...` could not run because `gradle/wrapper/gradle-wrapper.jar` is absent in this worktree.
  - `gradle test --tests ...testRestServerEndpointSubinterfaceTypePathOverridesBaseInterfacePathForInheritedMethod --no-daemon --no-configuration-cache` passed with repo-local `TMPDIR`, `XDG_DATA_HOME`, and `GRADLE_USER_HOME`.
  - Focused six-test Gradle slice passed with repo-local `TMPDIR`, `XDG_DATA_HOME`, and `GRADLE_USER_HOME`.
  - `git diff --check` passed.
