// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config

import com.intellij.helidon.HelidonIcons
import com.intellij.helidon.utils.HelidonCommonUtils.hasHelidonConfigLibrary
import com.intellij.java.library.JavaLibraryModificationTracker
import com.intellij.microservices.jvm.config.ConfigPlaceholderReference
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.util.CachedValueProvider.Result
import com.intellij.psi.util.CachedValuesManager
import javax.swing.Icon

internal const val HELIDON_CONFIG_FQN = "io.helidon.config.Config"
internal const val HELIDON_CONFIG_GET_METHOD = "get"

internal const val HELIDON_APPLICATION_PREFIX = "application"
internal const val HELIDON_APPLICATION_ENV_SPECIFIC_PREFIX = "$HELIDON_APPLICATION_PREFIX-"
internal const val HELIDON_MP_CONFIG_FILE_NAME = "microprofile-config"
internal const val HELIDON_OCI_CONFIG_PREFIX = "oci-config"

internal enum class HelidonConfigFileKind {
  APPLICATION,
  OCI,
}

fun isHelidonConfigFileName(fileName: String): Boolean {
  return isHelidonApplicationConfigFileName(fileName) || isHelidonOciConfigFileName(fileName)
}

internal fun isHelidonApplicationConfigFileName(fileName: String): Boolean {
  return fileName == HELIDON_APPLICATION_PREFIX ||
         fileName == HELIDON_MP_CONFIG_FILE_NAME ||
         fileName.startsWith(HELIDON_APPLICATION_ENV_SPECIFIC_PREFIX)
}

internal fun isHelidonOciConfigFileName(fileName: String): Boolean = fileName == HELIDON_OCI_CONFIG_PREFIX

fun isHelidonConfigFile(file: PsiFile): Boolean = getHelidonConfigFileIcon(file) != null

internal fun isHelidonApplicationConfigFile(file: PsiFile): Boolean {
  return getHelidonConfigFileKind(file) == HelidonConfigFileKind.APPLICATION
}

internal fun isHelidonOciConfigFile(file: PsiFile): Boolean {
  return getHelidonConfigFileKind(file) == HelidonConfigFileKind.OCI
}

internal fun getHelidonConfigFileKind(file: PsiFile): HelidonConfigFileKind? {
  if (file.virtualFile == null) return null

  val fileModule = ModuleUtilCore.findModuleForPsiElement(file)
  if (fileModule == null || !hasHelidonConfigLibrary(fileModule)) return null

  return CachedValuesManager.getCachedValue(file) {
    val module = ModuleUtilCore.findModuleForPsiElement(file)
    if (module != null && !module.isDisposed) {
      val virtualFile = file.virtualFile
      val sourceRoots = ModuleRootManager.getInstance(module).sourceRoots
      for (contributor in HelidonConfigFileContributor.EP_NAME.extensions) {
        if (contributor.fileType == file.fileType &&
            contributor.isConfigFile(virtualFile) &&
            VfsUtilCore.isUnder(virtualFile, sourceRoots.toSet())) {
          val kind = getHelidonConfigFileKind(virtualFile.nameWithoutExtension)
          if (kind != null) {
            return@getCachedValue Result.create(
              kind, file,
              JavaLibraryModificationTracker.getInstance(file.project),
              ProjectRootModificationTracker.getInstance(file.project))
          }
        }
      }
    }
    return@getCachedValue Result.create<HelidonConfigFileKind?>(null,
                                                                file,
                                                                JavaLibraryModificationTracker.getInstance(file.project),
                                                                ProjectRootModificationTracker.getInstance(file.project))
  }
}

fun getHelidonConfigFileIcon(file: PsiFile): Icon? {
  return when (getHelidonConfigFileKind(file)) {
    HelidonConfigFileKind.APPLICATION -> HelidonIcons.Helidon
    HelidonConfigFileKind.OCI -> HelidonIcons.Ora
    null -> null
  }
}

private fun getHelidonConfigFileKind(fileName: String): HelidonConfigFileKind? {
  return when {
    isHelidonApplicationConfigFileName(fileName) -> HelidonConfigFileKind.APPLICATION
    isHelidonOciConfigFileName(fileName) -> HelidonConfigFileKind.OCI
    else -> null
  }
}

fun createHelidonPlaceholderReferences(element: PsiElement): Array<PsiReference> {
  val completedReferences = ConfigPlaceholderReference.createPlaceholderReferences(element) { psiElement, range ->
    HelidonConfigPlaceholderReference.Builder(psiElement, range, false)
      .withSystemProperties()
      .build()
  }
  val incompleteReference = createIncompleteHelidonPlaceholderReference(element)
  return if (incompleteReference == null) {
    completedReferences
  }
  else {
    arrayOf(*completedReferences, incompleteReference)
  }
}

private fun createIncompleteHelidonPlaceholderReference(element: PsiElement): PsiReference? {
  val valueText = ElementManipulators.getValueText(element)
  var searchStart = 0
  while (true) {
    val placeholderStart = valueText.indexOf(ConfigPlaceholderReference.PLACEHOLDER_PREFIX, searchStart)
    if (placeholderStart < 0) return null

    val valueStart = placeholderStart + ConfigPlaceholderReference.PLACEHOLDER_PREFIX.length
    val placeholderEnd = valueText.indexOf(ConfigPlaceholderReference.PLACEHOLDER_SUFFIX, valueStart)
    if (placeholderEnd < 0) {
      val defaultValueStart = valueText.indexOf(':', valueStart).takeIf { it >= 0 } ?: valueText.length
      val range = TextRange.create(valueStart, defaultValueStart)
        .shiftRight(ElementManipulators.getOffsetInElement(element))
      return HelidonConfigPlaceholderReference.Builder(element, range, false)
        .withSystemProperties()
        .build()
    }

    searchStart = placeholderEnd + ConfigPlaceholderReference.PLACEHOLDER_SUFFIX.length
  }
}
