// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.ce

import com.intellij.helidon.config.ConfigOption
import com.intellij.helidon.config.ConfigOptionKind
import com.intellij.helidon.config.ConfigType
import com.intellij.helidon.config.ForcedConfigRoot
import com.intellij.helidon.config.HELIDON_CONFIG_METADATA
import com.intellij.helidon.config.HelidonConfigMetadataParser
import com.intellij.helidon.config.HelidonOciConfigSourceProviderDiscovery
import com.intellij.helidon.config.ModuleMetadata
import com.intellij.helidon.config.actualType
import com.intellij.helidon.config.getRootConfigTypes
import com.intellij.helidon.config.isHelidonOciConfigFile
import com.intellij.helidon.utils.HelidonCommonUtils
import com.intellij.java.library.JavaLibraryModificationTracker
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

@Service(Service.Level.APP)
internal class HelidonConfigKeyService {
  companion object {
    fun getInstance(): HelidonConfigKeyService = ApplicationManager.getApplication().service()
  }

  fun getAllKeys(module: Module?): List<HelidonConfigKey> {
    if (module == null || !HelidonCommonUtils.hasHelidonLibrary(module)) return emptyList()

    return getConfigKeysFromLibs(module)
  }

  fun getAllKeys(module: Module?, configFile: PsiFile?): List<HelidonConfigKey> {
    if (module == null || !HelidonCommonUtils.hasHelidonLibrary(module)) return emptyList()
    if (configFile != null && isHelidonOciConfigFile(configFile)) {
      return getOciConfigKeysFromLibs(module)
    }
    return getConfigKeysFromLibs(module)
  }

  private fun getConfigKeysFromLibs(module: Module): List<HelidonConfigKey> {
    return CachedValuesManager.getManager(module.project).getCachedValue(module, CONFIG_KEYS_KEY, {
      val metadataFiles = findConfigMetadataFiles(module)
      val modulesMetadata = metadataFiles.mapNotNull(::parseMetadata)
      val keys = HelidonConfigKeyBuilder(modulesMetadata).collectKeys()
      CachedValueProvider.Result.create(keys, *metadataCacheDependencies(module, metadataFiles))
    }, false)
  }

  private fun getOciConfigKeysFromLibs(module: Module): List<HelidonConfigKey> {
    return CachedValuesManager.getManager(module.project).getCachedValue(module, OCI_CONFIG_KEYS_KEY, {
      val allMetadataFiles = findConfigMetadataFiles(module)
      val publicOciKeys = getConfigKeysFromLibs(module)
        .filter { it.name == PUBLIC_OCI_CONFIG_ROOT || it.name.startsWith("$PUBLIC_OCI_CONFIG_ROOT.") }

      val providerMetadata = HelidonOciConfigSourceProviderDiscovery.getProviderMetadata(module)
      val providerMetadataFiles = providerMetadata.flatMap { it.metadataFiles }.distinctBy { it.virtualFile.path }
      val moduleMetadataByFile = providerMetadataFiles.associateWith(::parseMetadata)
      val forcedRoots = providerMetadata.flatMap { provider ->
        provider.metadataFiles.mapNotNull { moduleMetadataByFile[it] }
          .map { ForcedConfigRoot(it, "helidon.${provider.type}") }
      }
      val providerKeys = if (forcedRoots.isEmpty()) {
        emptyList()
      }
      else {
        HelidonConfigKeyBuilder(moduleMetadataByFile.values.filterNotNull()).collectKeys(forcedRoots)
      }

      val keys = LinkedHashMap<String, HelidonConfigKey>()
      for (key in publicOciKeys + providerKeys) {
        keys.putIfAbsent(key.name, key)
      }

      val dependencyFiles = (allMetadataFiles + providerMetadata.flatMap { it.dependencyFiles }).distinctBy { it.virtualFile.path }
      CachedValueProvider.Result.create(keys.values.toList(), *metadataCacheDependencies(module, dependencyFiles))
    }, false)
  }

  private fun metadataCacheDependencies(module: Module, metadataFiles: List<PsiFile>): Array<Any> {
    return arrayOf(JavaLibraryModificationTracker.getInstance(module.project),
                   ProjectRootModificationTracker.getInstance(module.project),
                   *metadataFiles.toTypedArray())
  }

  private fun findConfigMetadataFiles(module: Module): List<PsiFile> {
    val psiManager = PsiManager.getInstance(module.project)
    val result = ArrayList<PsiFile>()
    val seenPaths = HashSet<String>()

    for (root in ModuleRootManager.getInstance(module).orderEntries().recursively().classes().roots) {
      for (path in listOf("META-INF/helidon/$HELIDON_CONFIG_METADATA", "META-INF/$HELIDON_CONFIG_METADATA")) {
        val metadataFile = root.findFileByRelativePath(path) ?: continue
        if (!seenPaths.add(metadataFile.path)) continue
        psiManager.findFile(metadataFile)?.let(result::add)
      }
    }
    return result
  }

  private fun parseMetadata(configMetadataFile: PsiFile): ModuleMetadata? {
    try {
      return HelidonConfigMetadataParser().parse(configMetadataFile)
    }
    catch (ce: ProcessCanceledException) {
      throw ce
    }
    catch (e: Exception) {
      logger<HelidonConfigKeyService>().warn("Error parsing " + configMetadataFile.virtualFile.path, e)
    }
    return null
  }

  private class HelidonConfigKeyBuilder(modulesMetadata: List<ModuleMetadata>) {
    private val configTypes: Map<String, ConfigType> = modulesMetadata.flatMap { it.moduleConfigs }
      .flatMap { it.types }
      .associateBy { it.type }

    fun collectKeys(): List<HelidonConfigKey> {
      val keys = LinkedHashMap<String, HelidonConfigKey>()
      configTypes.values
        .filter { it.standalone && it.prefix.isNotBlank() }
        .forEach { processConfigType(it, it.prefix, keys, HashSet()) }
      return keys.values.toList()
    }

    fun collectKeys(forcedRoots: List<ForcedConfigRoot>): List<HelidonConfigKey> {
      val keys = LinkedHashMap<String, HelidonConfigKey>()
      for (forcedRoot in forcedRoots) {
        for (configType in getRootConfigTypes(forcedRoot.moduleMetadata)) {
          processConfigType(configType, forcedRoot.prefix, keys, HashSet())
        }
      }
      return keys.values.toList()
    }

    private fun processConfigType(configType: ConfigType,
                                  prefix: String,
                                  keys: MutableMap<String, HelidonConfigKey>,
                                  visitingTypes: MutableSet<String>) {
      if (!visitingTypes.add(configType.type)) return
      try {
        for (option in configType.options) {
          processConfigOption(configType, option, prefix, keys, visitingTypes)
        }
        for (inheritedType in configType.inherits.mapNotNull(configTypes::get)) {
          processConfigType(inheritedType, prefix, keys, visitingTypes)
        }
      }
      finally {
        visitingTypes.remove(configType.type)
      }
    }

    private fun processConfigOption(configType: ConfigType,
                                    option: ConfigOption,
                                    prefix: String,
                                    keys: MutableMap<String, HelidonConfigKey>,
                                    visitingTypes: MutableSet<String>) {
      val keyName = if (prefix.isBlank()) option.key else "$prefix.${option.key}"
      val nestedType = configTypes[option.type]

      if (option.kind == ConfigOptionKind.VALUE && nestedType != null) {
        if (nestedType.type != configType.type) {
          processConfigType(nestedType, keyName, keys, visitingTypes)
        }
        return
      }

      keys.putIfAbsent(keyName, option.toConfigKey(keyName))

      if (nestedType == null) return

      val nestedPrefix = when (option.kind) {
        ConfigOptionKind.MAP -> "$keyName.*"
        ConfigOptionKind.LIST -> "$keyName.*"
        ConfigOptionKind.VALUE -> keyName
      }
      if (nestedType.type != configType.type) {
        processConfigType(nestedType, nestedPrefix, keys, visitingTypes)
      }
    }

    private fun ConfigOption.toConfigKey(name: String): HelidonConfigKey {
      return HelidonConfigKey(name, actualType(), description, defaultValue, deprecated)
    }
  }
}

private val CONFIG_KEYS_KEY = Key.create<CachedValue<List<HelidonConfigKey>>>("HELIDON_CE_CONFIG_KEYS")
private const val PUBLIC_OCI_CONFIG_ROOT = "helidon.oci"
private val OCI_CONFIG_KEYS_KEY = Key.create<CachedValue<List<HelidonConfigKey>>>("HELIDON_CE_OCI_CONFIG_KEYS")
