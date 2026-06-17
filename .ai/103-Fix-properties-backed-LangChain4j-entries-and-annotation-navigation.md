# 103 Fix properties-backed LangChain4j entries and annotation navigation

## Issue

- GitHub issue: https://github.com/danielkec/helidon-intellij-plugin/issues/103
- Branch: `kec/103-properties-langchain4j-entries`
- Base: `origin/main` at `cc0e2ce`

## Problem

LangChain4j `application.properties` entries are currently collected per leaf property. That can create duplicate Services rows for one logical runtime entry, truncates dotted runtime keys to the first segment after the section, and prevents Java annotation values from resolving to properties-backed entries when only leaf keys exist.

## Plan

- Group properties config entries by logical `langchain4j.<section>.<runtime-key>` instead of individual leaf properties.
- Preserve dotted runtime keys by deriving the runtime key from the full property key minus the known leaf option name.
- Resolve annotation values to properties-backed logical entries when the matching leaf properties exist.
- Add focused Services and annotation-navigation tests.

## Validation

- Passed: `git diff --check`
- Passed: `GRADLE_USER_HOME=$PWD/.gradle-home XDG_DATA_HOME=$PWD/.xdg-data TMPDIR=$PWD/.gradle-tmp JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=$PWD/.gradle-tmp ./gradlew test --tests com.intellij.helidon.services.HelidonServicesModelTest.testCollectsPropertiesLangChain4jConfigByLogicalRuntimeEntry --tests com.intellij.helidon.config.yaml.HelidonYamlLangChain4jConfigReferenceTest.testAiChatModelAnnotationValueResolvesToPropertiesBackedModelConfigKey --tests com.intellij.helidon.config.yaml.HelidonYamlLangChain4jConfigReferenceTest.testAiChatModelAnnotationValueResolvesToDottedPropertiesBackedModelConfigKey --tests com.intellij.helidon.config.yaml.HelidonYamlLangChain4jConfigReferenceTest.testAiChatModelAnnotationValueResolvesToDottedModelConfigKey --tests com.intellij.helidon.config.yaml.HelidonYamlLangChain4jConfigReferenceTest.testAiChatModelAnnotationValueResolvesToModelConfigKey --no-daemon --no-configuration-cache -Dkotlin.compiler.execution.strategy=in-process`
- Caveat: a broader run of `HelidonServicesModelTest` plus the full `HelidonYamlLangChain4jConfigReferenceTest` failed in two existing constant-expression annotation tests that resolved only to Java fields in this environment. The issue-specific literal annotation/property paths passed.
