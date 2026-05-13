// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.ce

import com.intellij.helidon.config.ConfigOption
import com.intellij.helidon.config.ConfigOptionKind
import com.intellij.helidon.config.ConfigType
import com.intellij.helidon.config.HELIDON_CONFIG_METADATA
import com.intellij.helidon.config.HelidonConfigMetadataParser
import com.intellij.helidon.config.ModuleMetadata
import com.intellij.helidon.config.actualType
import com.intellij.helidon.utils.HelidonCommonUtils
import com.intellij.java.library.JavaLibraryModificationTracker
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

@Service(Service.Level.APP)
internal class HelidonConfigKeyService {
  companion object {
    fun getInstance(): HelidonConfigKeyService = ApplicationManager.getApplication().service()
  }

  fun getAllKeys(module: Module?): List<HelidonConfigKey> {
    if (module == null || !HelidonCommonUtils.hasHelidonLibrary(module)) return emptyList()

    return CachedValuesManager.getManager(module.project).getCachedValue(module, CONFIG_KEYS_KEY, {
      val modulesMetadata = findConfigMetadataFiles(module).mapNotNull(::parseMetadata)
      val keys = HelidonConfigKeyBuilder(modulesMetadata).collectKeys()
      CachedValueProvider.Result.create(keys,
                                        PsiModificationTracker.MODIFICATION_COUNT,
                                        JavaLibraryModificationTracker.getInstance(module.project))
    }, false)
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
