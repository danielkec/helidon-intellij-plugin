# 91 Add dedicated Helidon run/debug configuration

## Issue

GitHub issue: https://github.com/danielkec/helidon-intellij-plugin/issues/91

Goal: add a Helidon-specific run/debug configuration type so generated SE and MP configurations are no longer plain Java Application configurations, while preserving normal Java application execution settings and duplicate prevention.

## Plan

- Add a Helidon run configuration type, factory, and configuration class that reuse IntelliJ Java Application run behavior.
- Register the configuration type in `plugin.xml`.
- Update `HelidonRunConfigurationService` to create Helidon configurations and still dedupe against old generated Java Application configurations.
- Add focused tests for type selection, duplicate prevention, and descriptor registration.
- Validate with the focused Gradle test slice, then commit, push, and open a PR.

## Notes

- Current checkout is dirty on an unrelated issue 105 branch, so this work is isolated in `intellij-plugin-worktrees/issue91-work`.
- Existing service creates configurations through `ApplicationConfigurationType` and stores module/main-class values directly on `ApplicationConfiguration`.
- Implemented `com.intellij.helidon.run.HelidonRunConfigurationType`, `HelidonRunConfiguration`, and a Helidon-scoped main-class producer.
- `HelidonRunConfigurationService` now creates Helidon configurations and dedupes against all `ApplicationConfiguration` instances so existing Java Application configurations are preserved and block duplicates.
- `resources/META-INF/plugin.xml` registers the Helidon configuration type and producer.
- Focused validation passed:
  - `./gradlew test --tests com.intellij.helidon.newproject.HelidonRunConfigurationServiceTest --tests com.intellij.helidon.HelidonPluginDescriptorTest --no-daemon --no-configuration-cache`
- Plugin verifier passed after overriding the verifier home to a writable `/tmp` directory:
  - `./gradlew verifyPlugin --no-daemon --no-configuration-cache`
  - Compatible with `IU-261.23567.138` and `IU-262.7132.23`.
