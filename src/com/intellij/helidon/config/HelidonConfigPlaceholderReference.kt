// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config

import com.intellij.codeInsight.highlighting.HighlightedReference
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.helidon.HelidonIcons
import com.intellij.helidon.config.yaml.getYamlPlaceholderLookupRenderer
import com.intellij.lang.properties.IProperty
import com.intellij.lang.properties.psi.PropertiesElementFactory
import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.lang.properties.psi.PropertyKeyIndex
import com.intellij.lang.properties.references.PropertiesCompletionContributor
import com.intellij.microservices.jvm.config.ConfigPlaceholderReference
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.*
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.ArrayUtil
import com.intellij.util.PairProcessor
import com.intellij.util.SmartList
import com.intellij.util.containers.addIfNotNull
import org.jetbrains.yaml.psi.YAMLKeyValue

class HelidonConfigPlaceholderReference private constructor(builder: Builder) :
  PsiReferenceBase.Poly<PsiElement>(builder.element, builder.range, builder.soft), HighlightedReference, ConfigPlaceholderReference {

  data class Builder(val element: PsiElement?, val range: TextRange?, val soft: Boolean) {

    var withSystemProperties: Boolean = false
      private set

    fun withSystemProperties(): Builder {
      withSystemProperties = true
      return this
    }

    fun build(): HelidonConfigPlaceholderReference = HelidonConfigPlaceholderReference(this)
  }

  private val withSystemProperties: Boolean = builder.withSystemProperties

  override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult?> {
    val key = value
    if (withSystemProperties) {
      val systemProperty = getSystemProperties().findPropertyByKey(key)
      if (systemProperty != null) {
        return PsiElementResolveResult.createResults(systemProperty.psiElement)
      }
    }

    val module = ModuleUtilCore.findModuleForPsiElement(element)

    val existingKeys = SmartList<PsiElement>()
    processKeys(module, PairProcessor { contributor: HelidonConfigFileContributor, psiFile: PsiFile ->
      existingKeys.addIfNotNull(contributor.findKey(psiFile, key))
      return@PairProcessor true
    })
    if (existingKeys.isNotEmpty()) {
      return PsiElementResolveResult.createResults(existingKeys)
    }

    // fallback to key in any .properties file
    val contentScope = module?.moduleContentScope ?: return ResolveResult.EMPTY_ARRAY
    val properties = PropertyKeyIndex.getInstance().getProperties(key, element.project, element.resolveScope.uniteWith(contentScope))
    return if (properties.isEmpty()) ResolveResult.EMPTY_ARRAY else PsiElementResolveResult.createResults(properties)
  }

  private fun getSystemProperties(): PropertiesFile {
    return PropertiesElementFactory.getSystemProperties(myElement.project)
  }

  override fun getVariants(): Array<Any> {
    val variants: MutableList<LookupElement> = ArrayList()

    val module = ModuleUtilCore.findModuleForPsiElement(element)
    getCachedKeyVariants(module).mapNotNullTo(variants) { it.createLookupElement() }
    if (withSystemProperties) {
      for (property in getSystemProperties().properties) {
        val key = property.key ?: continue
        variants.add(LookupElementBuilder.create(property, key).withIcon(HelidonIcons.Helidon))
      }
    }
    return ArrayUtil.toObjectArray(variants)
  }

  private fun processKeys(module: Module?, processor: PairProcessor<HelidonConfigFileContributor, PsiFile>) {
    if (module == null) return

    val containingFile = element.containingFile.originalFile.virtualFile
    val isInTests = containingFile != null &&
                    ModuleRootManager.getInstance(module).fileIndex.isInTestSourceContent(containingFile)

    processConfigFiles(module, isInTests, processor)
  }

  private fun getCachedKeyVariants(module: Module?): List<CachedConfigKeyVariant> {
    if (module == null) return emptyList()

    val containingFile = element.containingFile.originalFile.virtualFile
    val isInTests = containingFile != null &&
                    ModuleRootManager.getInstance(module).fileIndex.isInTestSourceContent(containingFile)
    return getCachedKeyVariants(module, isInTests)
  }
}

private val CONFIG_KEY_VARIANTS_KEY = Key.create<CachedValue<List<CachedConfigKeyVariant>>>("HELIDON_CONFIG_KEY_VARIANTS")
private val TEST_CONFIG_KEY_VARIANTS_KEY = Key.create<CachedValue<List<CachedConfigKeyVariant>>>("HELIDON_TEST_CONFIG_KEY_VARIANTS")

private fun getCachedKeyVariants(module: Module, isInTests: Boolean): List<CachedConfigKeyVariant> {
  val key = if (isInTests) TEST_CONFIG_KEY_VARIANTS_KEY else CONFIG_KEY_VARIANTS_KEY
  return CachedValuesManager.getManager(module.project).getCachedValue(module, key, {
    collectKeyVariants(module, isInTests)
  }, false)
}

private fun collectKeyVariants(module: Module, isInTests: Boolean): CachedValueProvider.Result<List<CachedConfigKeyVariant>> {
  val variants = ArrayList<CachedConfigKeyVariant>()
  val configFileModificationTracker = HelidonConfigFileModificationTracker.getInstance(module.project)

  processConfigFiles(module, isInTests, PairProcessor { contributor: HelidonConfigFileContributor, psiFile: PsiFile ->
    configFileModificationTracker.track(psiFile)
    val pointerManager = SmartPointerManager.getInstance(psiFile.project)
    contributor.getKeyVariants(psiFile).mapTo(variants) { createCachedConfigKeyVariant(pointerManager, it) }
    return@PairProcessor true
  })

  val dependencies = ArrayList<Any>(3)
  dependencies.add(configFileModificationTracker)
  dependencies.add(ProjectRootModificationTracker.getInstance(module.project))
  dependencies.add(VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)

  return CachedValueProvider.Result.create(variants, *dependencies.toTypedArray())
}

private sealed interface CachedConfigKeyVariant {
  fun createLookupElement(): LookupElement?
}

private data class YamlCachedConfigKeyVariant(
  val lookupString: String,
  val keyValuePointer: SmartPsiElementPointer<YAMLKeyValue>,
) : CachedConfigKeyVariant {
  override fun createLookupElement(): LookupElement? {
    val keyValue = keyValuePointer.element ?: return null
    return LookupElementBuilder.create(keyValue, lookupString)
      .withRenderer(getYamlPlaceholderLookupRenderer())
  }
}

private data class PropertiesCachedConfigKeyVariant(
  val propertyPointer: SmartPsiElementPointer<PsiElement>,
) : CachedConfigKeyVariant {
  override fun createLookupElement(): LookupElement? {
    val property = propertyPointer.element as? IProperty ?: return null
    return PropertiesCompletionContributor.createVariant(property)
  }
}

private data class PsiCachedConfigKeyVariant(
  val lookupString: String,
  val elementPointer: SmartPsiElementPointer<PsiElement>,
) : CachedConfigKeyVariant {
  override fun createLookupElement(): LookupElement? {
    val element = elementPointer.element ?: return null
    return LookupElementBuilder.create(element, lookupString)
  }
}

private data class PlainCachedConfigKeyVariant(
  val lookupString: String,
) : CachedConfigKeyVariant {
  override fun createLookupElement(): LookupElement {
    return LookupElementBuilder.create(lookupString)
  }
}

private fun createCachedConfigKeyVariant(pointerManager: SmartPointerManager,
                                         lookupElement: LookupElement): CachedConfigKeyVariant {
  val lookupObject = lookupElement.`object`
  return when (lookupObject) {
    is YAMLKeyValue -> YamlCachedConfigKeyVariant(
      lookupElement.lookupString,
      pointerManager.createSmartPsiElementPointer(lookupObject),
    )
    is IProperty -> PropertiesCachedConfigKeyVariant(
      pointerManager.createSmartPsiElementPointer(lookupObject.psiElement),
    )
    is PsiElement -> PsiCachedConfigKeyVariant(
      lookupElement.lookupString,
      pointerManager.createSmartPsiElementPointer(lookupObject),
    )
    else -> PlainCachedConfigKeyVariant(lookupElement.lookupString)
  }
}

private fun processConfigFiles(module: Module,
                               isInTests: Boolean,
                               processor: PairProcessor<HelidonConfigFileContributor, PsiFile>) {
  val psiManager = PsiManager.getInstance(module.project)
  for ((virtualFile, contributor) in HelidonConfigFileContributor.findConfigFiles(module, isInTests)) {
    val psiFile = psiManager.findFile(virtualFile) ?: continue
    if (!processor.process(contributor, psiFile)) return
  }
}
