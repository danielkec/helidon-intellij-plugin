// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config

import com.intellij.helidon.utils.HelidonCommonUtils
import com.intellij.java.library.JavaLibraryModificationTracker
import com.intellij.microservices.jvm.config.MetaConfigKey
import com.intellij.microservices.jvm.config.MetaConfigKeyManager
import com.intellij.microservices.jvm.config.utils.findConfigFilesInMetaInf
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

@Service(Service.Level.APP)
class HelidonMetaConfigKeyManager : MetaConfigKeyManager() {
  companion object {
    fun getInstance(): HelidonMetaConfigKeyManager = ApplicationManager.getApplication().service()
  }

  override fun getAllMetaConfigKeys(module: Module?): List<MetaConfigKey> {
    if (module == null || !HelidonCommonUtils.hasHelidonConfigLibrary(module)) return emptyList()

    return getMetaConfigKeysFromLibs(module)
  }

  internal fun getMetaConfigKeys(module: Module?, configFile: PsiFile?): List<MetaConfigKey> {
    if (module == null || !HelidonCommonUtils.hasHelidonConfigLibrary(module)) return emptyList()
    if (configFile != null && isHelidonOciConfigFile(configFile)) {
      return getOciMetaConfigKeysFromLibs(module)
    }
    return getMetaConfigKeysFromLibs(module)
  }

  override fun getConfigKeyNameBinder(module: Module): ConfigKeyNameBinder = HelidonConfigKeyNameBinder

  private fun getMetaConfigKeysFromLibs(module: Module): List<MetaConfigKey> {
    return CachedValuesManager.getManager(module.project).getCachedValue(module) {
      val metadataFiles = findConfigMetadataFiles(module)
      val modulesMetadata = metadataFiles.mapNotNull { metadataFile ->
        getModuleMetadataForFile(metadataFile)
      }
      val allKeys = HelidonConfigMetadataBuilder(modulesMetadata, module.project).collectKeys(module)
      CachedValueProvider.Result.create(allKeys, *metadataCacheDependencies(module, metadataFiles))
    }
  }

  private fun getOciMetaConfigKeysFromLibs(module: Module): List<MetaConfigKey> {
    return CachedValuesManager.getManager(module.project).getCachedValue(module, OCI_META_CONFIG_KEYS_KEY, {
      val allMetadataFiles = findConfigMetadataFiles(module)
      val moduleMetadataByFile = allMetadataFiles.associateWith { getModuleMetadataForFile(it) }
      val allModulesMetadata = moduleMetadataByFile.values.filterNotNull()
      val publicOciRoots = allModulesMetadata.flatMap { moduleMetadata ->
        moduleMetadata.moduleConfigs
          .flatMap { it.types }
          .filter { it.standalone && it.prefix == PUBLIC_OCI_CONFIG_ROOT }
          .map { ForcedConfigRoot(moduleMetadata, it.prefix, it.type) }
      }
      val publicOciKeys = if (publicOciRoots.isEmpty()) {
        emptyList()
      }
      else {
        HelidonConfigMetadataBuilder(allModulesMetadata, module.project).collectKeys(module, publicOciRoots)
      }

      val providerMetadata = HelidonOciConfigSourceProviderDiscovery.getProviderMetadata(module)
      val providerMetadataFiles = providerMetadata.flatMap { it.metadataFiles }.distinctBy { it.virtualFile.path }
      val providerModuleMetadataByFile = providerMetadataFiles.associateWith { metadataFile ->
        moduleMetadataByFile[metadataFile] ?: getModuleMetadataForFile(metadataFile)
      }
      val forcedRoots = providerMetadata.flatMap { provider ->
        provider.metadataFiles.mapNotNull { providerModuleMetadataByFile[it] }
          .map { ForcedConfigRoot(it, "helidon.${provider.type}") }
      }
      val providerKeys = if (forcedRoots.isEmpty()) {
        emptyList()
      }
      else {
        HelidonConfigMetadataBuilder(providerModuleMetadataByFile.values.filterNotNull(), module.project).collectKeys(module, forcedRoots)
      }

      val keys = LinkedHashMap<String, MetaConfigKey>()
      for (key in publicOciKeys + providerKeys) {
        keys.putIfAbsent(key.name, key)
      }
      val dependencyFiles = (allMetadataFiles + providerMetadata.flatMap { it.dependencyFiles }).distinctBy { it.virtualFile.path }
      CachedValueProvider.Result.create(keys.values.toList(), *metadataCacheDependencies(module, dependencyFiles))
    }, false)
  }

  private fun findConfigMetadataFiles(module: Module): List<PsiFile> {
    return findConfigFilesInMetaInf(module, HELIDON_CONFIG_METADATA, true)
  }

  private fun metadataCacheDependencies(module: Module, metadataFiles: List<PsiFile>): Array<Any> {
    return arrayOf(JavaLibraryModificationTracker.getInstance(module.project),
                   ProjectRootModificationTracker.getInstance(module.project),
                   *metadataFiles.toTypedArray())
  }

  private fun getModuleMetadataForFile(configMetadataFile: PsiFile): ModuleMetadata? {
    try {
      return HelidonConfigMetadataParser().parse(configMetadataFile)
    }
    catch (ce: ProcessCanceledException) {
      throw ce
    }
    catch (e: Exception) {
      logger<HelidonMetaConfigKeyManager>().warn("Error parsing " + configMetadataFile.virtualFile.path, e)
    }
    return null
  }
}

private const val PUBLIC_OCI_CONFIG_ROOT = "helidon.oci"
private val OCI_META_CONFIG_KEYS_KEY = Key.create<CachedValue<List<MetaConfigKey>>>("HELIDON_OCI_META_CONFIG_KEYS")
