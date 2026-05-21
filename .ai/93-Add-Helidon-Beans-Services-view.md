# 93 Add Helidon Beans/Services view

## Context

- Worktree: `/tmp/helidon-intellij-issue93`
- Branch: `kec/93-add-helidon-beans-services-view`
- Issue: `#93`

## Scope

- Add a read-only Helidon Beans/Services project view.
- Show the view only for Helidon projects.
- List service-registry classes, service usages/lookups, HTTP services/endpoints, and LangChain4j components.
- Provide navigation to source/config declarations where available.
- Add filters for module/test/library/component kind and unresolved/ambiguous entries.
- Keep dependency graph rendering as a follow-up unless existing diagram code is cheap to reuse.

## Current Plan

- Reuse existing service-registry, endpoint, and LangChain4j detection code instead of adding parallel analyzers.
- Register a Helidon tool window with a tree/table MVP.
- Refresh the view on source/config changes.
- Add focused tests for model collection and project gating where UI-level testing is not practical.

## Progress

- 2026-05-21: Issue read from GitHub; no existing PR found.
- 2026-05-21: Created isolated worktree and branch from `origin/main`.
- 2026-05-21: Added a reusable Helidon Services snapshot model with service-registry, injection, lookup, LangChain4j component, and LangChain4j config rows.
- 2026-05-21: Added an optional Microservices contributor for HTTP endpoint rows.
- 2026-05-21: Added the Helidon Services tool window registration and a filterable tree UI.
- 2026-05-21: Added debounced PSI-change refresh so source/config edits update the view.
- 2026-05-21: Added descriptor and model tests for services, contracts, ambiguous/unresolved injection, filters, HTTP endpoints, LangChain4j rows, and non-Helidon projects.
- 2026-05-21: Removed startup-time tool window applicability gating after manual `runIde` showed the view could be hidden before project dependencies were available.

## Validation

- 2026-05-21: `git diff --check` passed.
- 2026-05-21: Focused descriptor/model tests passed:
  `./gradlew test --tests com.intellij.helidon.HelidonPluginDescriptorTest --tests com.intellij.helidon.services.HelidonServicesModelTest --tests com.intellij.helidon.services.HelidonServicesModelNoHelidonTest --no-daemon --no-configuration-cache`
- 2026-05-21: Broader impacted slice passed:
  `./gradlew test --tests com.intellij.helidon.HelidonPluginDescriptorTest --tests com.intellij.helidon.services.HelidonServicesModelTest --tests com.intellij.helidon.services.HelidonServicesModelNoHelidonTest --tests com.intellij.helidon.providers.HelidonClassAnnotatorTest --tests com.intellij.helidon.providers.view.HelidonUrlFrameworkTest --tests com.intellij.helidon.langchain4j.diagram.HelidonLangChain4jWorkflowGraphTest --no-daemon --no-configuration-cache`
- 2026-05-21: `./gradlew verifyPlugin --no-daemon --no-configuration-cache` passed with plugin verifier status `Compatible`.
- 2026-05-21: Re-ran focused descriptor/model tests after removing tool window applicability gating; passed.
- 2026-05-21: Two LangChain4j config reference tests were excluded from the final impacted slice after reproducing the same failures on unchanged `origin/main`:
  `testAiChatModelAnnotationConstantValueResolvesToModelConfigKey` and
  `testAiChatModelAnnotationConstantExpressionResolvesToModelConfigKey`.
