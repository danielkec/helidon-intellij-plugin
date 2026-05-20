// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config

import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.helidon.config.yaml.HelidonConfigYamlAccessor
import com.intellij.helidon.config.yaml.getQualifiedConfigKeyName
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
internal class HelidonConfigFileModificationTracker(private val project: Project) : ModificationTracker {

  companion object {
    fun getInstance(project: Project): HelidonConfigFileModificationTracker = project.service()
  }

  private val modificationCounter = AtomicLong()
  private val fileStamps = ConcurrentHashMap<VirtualFile, Long>()
  private val keySignatures = ConcurrentHashMap<VirtualFile, String>()

  fun track(file: PsiFile) {
    val originalFile = file.originalFile
    if (!isTrackedConfigFile(originalFile)) return
    val virtualFile = originalFile.virtualFile ?: originalFile.viewProvider.virtualFile
    fileStamps[virtualFile] = fileStamp(originalFile, virtualFile)
    keySignatures[virtualFile] = keySignature(originalFile)
  }

  override fun getModificationCount(): Long {
    val application = ApplicationManager.getApplication()
    if (application.isReadAccessAllowed) {
      return computeModificationCount()
    }
    return ReadAction.compute<Long, RuntimeException> { computeModificationCount() }
  }

  private fun computeModificationCount(): Long {
    val psiManager = PsiManager.getInstance(project)
    for (virtualFile in fileStamps.keys) {
      if (!virtualFile.isValid) {
        fileStamps.remove(virtualFile)
        keySignatures.remove(virtualFile)
        continue
      }
      val psiFile = psiManager.findFile(virtualFile)?.originalFile
      if (psiFile == null || !isTrackedConfigFile(psiFile)) {
        fileStamps.remove(virtualFile)
        keySignatures.remove(virtualFile)
        modificationCounter.incrementAndGet()
        continue
      }

      val currentStamp = fileStamp(psiFile, virtualFile)
      val previousStamp = fileStamps.put(virtualFile, currentStamp)
      if (previousStamp != null && previousStamp == currentStamp) {
        continue
      }
      val newSignature = keySignature(psiFile)
      val oldSignature = keySignatures.put(virtualFile, newSignature)
      if (oldSignature != null && oldSignature != newSignature) {
        modificationCounter.incrementAndGet()
      }
    }
    return modificationCounter.get()
  }

  private fun isTrackedConfigFile(file: PsiFile): Boolean {
    val originalFile = file.originalFile
    return originalFile.isValid && isHelidonConfigFile(originalFile)
  }

  private fun fileStamp(file: PsiFile, virtualFile: VirtualFile): Long {
    return maxOf(file.modificationStamp, file.viewProvider.modificationStamp, virtualFile.modificationStamp)
  }

  private fun keySignature(file: PsiFile): String {
    return when (file) {
      is YAMLFile -> yamlKeySignature(file)
      is PropertiesFile -> file.properties
        .mapNotNull { it.key }
        .distinct()
        .sorted()
        .joinToString("\n")
      else -> file.text
    }
  }

  private fun yamlKeySignature(file: YAMLFile): String {
    return file.documents.asSequence()
      .flatMap { document -> HelidonConfigYamlAccessor(document).allKeys.asSequence() }
      .filter { yamlKeyValue -> PsiTreeUtil.getParentOfType(yamlKeyValue, YAMLSequenceItem::class.java) == null }
      .filter { yamlKeyValue ->
        val yamlValue = yamlKeyValue.value
        yamlValue is YAMLScalar || yamlValue is YAMLSequence
      }
      .map(::getQualifiedConfigKeyName)
      .distinct()
      .sorted()
      .joinToString("\n")
  }
}
