// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config

import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.microservices.jvm.config.ConfigKeyPathUtils
import com.intellij.microservices.jvm.config.MetaConfigKey
import com.intellij.microservices.jvm.config.MetaConfigKey.*
import com.intellij.microservices.jvm.config.MetaConfigKeyLookupElementBuilder
import com.intellij.microservices.jvm.config.MetaConfigKeyManager
import com.intellij.psi.PsiAnchor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiType
import javax.swing.Icon

class HelidonMetaConfigKey(
  private val myName: String,
  declaration: PsiElement,
  private val myDeclarationResolveResult: DeclarationResolveResult,
  private val myType: PsiType?,
  private val myDescriptionText: DescriptionText,
  private val myDeprecation: Deprecation,
  private val myDefaultValue: String?,
  accessType: AccessType,
  val subKeys: List<HelidonMetaConfigKey> = emptyList(),
) : MetaConfigKey {
  private val myDeclarationAnchor: PsiAnchor = PsiAnchor.create(declaration)
  private val myMapKeyClass: PsiClass? = if (accessType == AccessType.MAP) mapKeyClass(myType) else null
  private val myAccessType: AccessType = if (myMapKeyClass?.isEnum == true) AccessType.ENUM_MAP else accessType
  private val myPresentation = object : MetaConfigKeyPresentation {
    override fun getIcon(): Icon {
      return when (myAccessType) {
        AccessType.INDEXED -> AllIcons.Nodes.PropertyRead
        AccessType.MAP -> AllIcons.Nodes.PropertyWrite
        AccessType.ENUM_MAP -> AllIcons.Nodes.PropertyWriteStatic
        AccessType.NORMAL -> AllIcons.Nodes.Property
      }
    }

    override fun getLookupElement(): LookupElementBuilder = getLookupElement(name)

    override fun getLookupElement(lookupString: String): LookupElementBuilder {
      return MetaConfigKeyLookupElementBuilder.create(this@HelidonMetaConfigKey, lookupString)
    }

    override fun tuneLookupElement(lookupElement: LookupElement): LookupElement {
      val priority = when {
        myDeprecation != Deprecation.NOT_DEPRECATED -> -100.0
        myDeclarationResolveResult == DeclarationResolveResult.JSON_UNRESOLVED_SOURCE_TYPE -> -50.0
        myDeclarationResolveResult == DeclarationResolveResult.ADDITIONAL_JSON -> 50.0
        else -> return lookupElement
      }
      return PrioritizedLookupElement.withPriority(lookupElement, priority)
    }
  }

  override fun getManager(): MetaConfigKeyManager = HelidonMetaConfigKeyManager.getInstance()

  override fun getName(): String = myName

  override fun getDeclaration(): PsiElement {
    return myDeclarationAnchor.retrieve() ?: error("Declaration for $name is no longer valid")
  }

  override fun getType(): PsiType? = myType

  override fun getEffectiveValueType(): PsiType? = myType?.let { myAccessType.getEffectiveValueType(it) }

  override fun isAccessType(vararg types: AccessType): Boolean = types.any { it == myAccessType }

  override fun getMapKeyType(): PsiClass? = myMapKeyClass

  override fun getDescriptionText(): DescriptionText = myDescriptionText

  override fun getDeclarationResolveResult(): DeclarationResolveResult = myDeclarationResolveResult

  override fun getDeprecation(): Deprecation = myDeprecation

  override fun getDefaultValue(): String? = myDefaultValue

  override fun getItemHint(): ItemHint = ItemHint.NONE

  override fun getKeyItemHint(): ItemHint = ItemHint.NONE

  override fun getPresentation(): MetaConfigKeyPresentation = myPresentation

  override fun toString(): String {
    return "HelidonMetaConfigKey{name='$name', descriptionText='$descriptionText', defaultValue='$defaultValue', type=$type, accessType=$myAccessType}"
  }

  private fun mapKeyClass(type: PsiType?): PsiClass? {
    val mapKeyType = type?.let(ConfigKeyPathUtils::getMapKeyType) ?: return null
    return ConfigKeyPathUtils.getPsiClass(mapKeyType)
  }
}
