// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config

import com.intellij.java.library.JavaLibraryModificationTracker
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import java.io.DataInputStream
import java.io.IOException

private const val OCI_PACKAGE_PREFIX = "com.oracle.helidon.oci"
private const val CONFIG_SOURCE_PROVIDER_SERVICE = "META-INF/services/io.helidon.config.spi.ConfigSourceProvider"
private val CONFIG_METADATA_PATHS = listOf(
  "META-INF/helidon/$HELIDON_CONFIG_METADATA",
  "META-INF/$HELIDON_CONFIG_METADATA",
)

internal data class HelidonOciConfigSourceProviderMetadata(
  val type: String,
  val metadataFiles: List<PsiFile>,
  val dependencyFiles: List<PsiFile>,
)

internal object HelidonOciConfigSourceProviderDiscovery {
  fun getProviderMetadata(module: Module): List<HelidonOciConfigSourceProviderMetadata> {
    return CachedValuesManager.getManager(module.project).getCachedValue(module, OCI_PROVIDER_METADATA_KEY, {
      val psiManager = PsiManager.getInstance(module.project)
      val result = ArrayList<HelidonOciConfigSourceProviderMetadata>()
      val dependencies = ArrayList<PsiFile>()

      for (root in ModuleRootManager.getInstance(module).orderEntries().recursively().classes().roots) {
        val serviceFile = root.findFileByRelativePath(CONFIG_SOURCE_PROVIDER_SERVICE) ?: continue
        val servicePsiFile = psiManager.findFile(serviceFile)
        if (servicePsiFile != null) {
          dependencies.add(servicePsiFile)
        }

        val metadataFiles = CONFIG_METADATA_PATHS.mapNotNull { root.findFileByRelativePath(it) }
          .distinctBy { it.path }
          .mapNotNull { psiManager.findFile(it) }
        dependencies.addAll(metadataFiles)

        val providerTypes = parseServiceFile(serviceFile)
          .filter { it.startsWith(OCI_PACKAGE_PREFIX) }
          .flatMapTo(LinkedHashSet()) { resolveProviderTypes(module, it) }
        for (providerType in providerTypes) {
          result.add(HelidonOciConfigSourceProviderMetadata(providerType, metadataFiles, listOfNotNull(servicePsiFile) + metadataFiles))
        }
      }

      CachedValueProvider.Result.create(result,
                                        JavaLibraryModificationTracker.getInstance(module.project),
                                        ProjectRootModificationTracker.getInstance(module.project),
                                        *dependencies.distinctBy { it.virtualFile.path }.toTypedArray())
    }, false)
  }

  private fun parseServiceFile(serviceFile: com.intellij.openapi.vfs.VirtualFile): List<String> {
    return VfsUtilCore.loadText(serviceFile)
      .lineSequence()
      .map { it.substringBefore('#').trim() }
      .filter { it.isNotEmpty() }
      .toList()
  }

  private fun resolveProviderTypes(module: Module, providerClassName: String): Set<String> {
    val providerClass = JavaPsiFacade.getInstance(module.project)
      .findClass(providerClassName, module.getModuleWithDependenciesAndLibrariesScope(true))
      ?: return emptySet()

    providerClass.findFieldByName("TYPE", false)
      ?.stringConstantValue()
      ?.takeIf(::isOciProviderType)
      ?.let { return setOf(it) }

    val compiledProviderTypes = collectCompiledClassStringConstants(providerClass)
    if (compiledProviderTypes != null) {
      return compiledProviderTypes
    }

    val result = LinkedHashSet<String>()
    val visitedFields = HashSet<PsiField>()
    for (method in providerClass.methods) {
      if (method.name == "supported" || method.name == "supports") {
        collectStringConstants(method, result, visitedFields)
      }
    }
    return result
  }

  private fun collectStringConstants(element: PsiElement, result: MutableSet<String>, visitedFields: MutableSet<PsiField>) {
    when (element) {
      is PsiLiteralExpression -> {
        (element.value as? String)
          ?.takeIf(::isOciProviderType)
          ?.let(result::add)
      }
      is PsiReferenceExpression -> {
        val field = element.resolve() as? PsiField
        if (field != null) {
          collectStringConstants(field, result, visitedFields)
        }
      }
    }
    for (child in element.children) {
      collectStringConstants(child, result, visitedFields)
    }
  }

  private fun collectStringConstants(field: PsiField, result: MutableSet<String>, visitedFields: MutableSet<PsiField>) {
    if (!visitedFields.add(field)) return

    field.stringConstantValue()
      ?.takeIf(::isOciProviderType)
      ?.let {
        result.add(it)
        return
      }
    field.initializer?.let { collectStringConstants(it, result, visitedFields) }
  }

  private fun PsiField.stringConstantValue(): String? = computeConstantValue() as? String

  private fun collectCompiledClassStringConstants(providerClass: PsiClass): Set<String>? {
    val classFile = providerClass.containingFile?.virtualFile?.takeIf { it.extension == "class" } ?: return null
    return try {
      classFile.inputStream.use { input ->
        collectClassFileStringConstants(DataInputStream(input))
      }
    }
    catch (_: IOException) {
      emptySet()
    }
  }

  private fun collectClassFileStringConstants(input: DataInputStream): Set<String> {
    if (input.readInt() != 0xCAFEBABE.toInt()) return emptySet()
    input.skipBytes(4) // minor_version, major_version

    val result = LinkedHashSet<String>()
    val constantPoolCount = input.readUnsignedShort()
    var index = 1
    while (index < constantPoolCount) {
      when (input.readUnsignedByte()) {
        1 -> {
          val value = input.readUTF()
          if (isOciProviderType(value)) {
            result.add(value)
          }
        }
        3, 4, 9, 10, 11, 12, 17, 18 -> input.skipBytes(4)
        5, 6 -> {
          input.skipBytes(8)
          index++
        }
        7, 8, 16, 19, 20 -> input.skipBytes(2)
        15 -> input.skipBytes(3)
        else -> return emptySet()
      }
      index++
    }
    return result
  }

  private fun isOciProviderType(value: String): Boolean {
    return value.startsWith("oci-") && value.length > "oci-".length
  }
}

private val OCI_PROVIDER_METADATA_KEY =
  Key.create<CachedValue<List<HelidonOciConfigSourceProviderMetadata>>>("HELIDON_OCI_CONFIG_SOURCE_PROVIDER_METADATA")
