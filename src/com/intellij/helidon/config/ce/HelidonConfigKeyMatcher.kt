// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.ce

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.util.text.StringUtil

internal object HelidonConfigKeyMatcher {
  fun matchesPart(keyPart: String, text: String): Boolean = normalize(keyPart).startsWith(normalize(text))

  fun matchesPrefix(key: String, prefix: String): Boolean {
    if (prefix.isBlank()) return true

    val keyParts = split(key)
    val prefixParts = split(prefix)
    if (prefixParts.size > keyParts.size) return false

    for (index in prefixParts.indices) {
      val keyPart = keyParts[index]
      val prefixPart = prefixParts[index]
      if (keyPart == "*") continue
      if (index == prefixParts.lastIndex) {
        if (!matchesPart(keyPart, prefixPart)) return false
      }
      else if (!StringUtil.equalsIgnoreCase(normalize(keyPart), normalize(prefixPart))) {
        return false
      }
    }
    return true
  }

  fun bindParameterizedKey(key: String, prefix: String): String? {
    val keyParts = split(key)
    if (!keyParts.contains("*")) return key

    val prefixParts = split(prefix)
    val result = keyParts.toMutableList()
    for (index in keyParts.indices) {
      if (keyParts[index] == "*" && index < prefixParts.size && prefixParts[index].isNotBlank()) {
        result[index] = prefixParts[index]
      }
    }
    if (result.contains("*")) return null
    return result.joinToString(".")
  }

  fun childLookupName(key: String, parent: String): String? {
    val keyParts = split(key)
    val parentParts = split(parent)
    if (parentParts.size >= keyParts.size) return null

    for (index in parentParts.indices) {
      val keyPart = keyParts[index]
      val parentPart = parentParts[index]
      if (keyPart == "*") continue
      if (!StringUtil.equalsIgnoreCase(normalize(keyPart), normalize(parentPart))) return null
    }
    return keyParts[parentParts.size]
  }

  private fun split(value: String): List<String> = value.split('.').filter { it.isNotBlank() }

  private fun normalize(value: String): String {
    val result = StringBuilder(value.length)
    value.forEachIndexed { index, c ->
      if (c.isUpperCase() && index > 0 && value[index - 1] != '-') {
        result.append('-')
      }
      result.append(c.lowercaseChar())
    }
    return result.toString()
  }
}

internal class HelidonConfigKeyPrefixMatcher(prefix: String, delegate: PrefixMatcher) : PrefixMatcher(prefix) {
  constructor(delegate: PrefixMatcher) : this(delegate.prefix, delegate)

  private val delegate = delegate.cloneWithPrefix(prefix)

  override fun prefixMatches(element: LookupElement): Boolean {
    return element.allLookupStrings.any { prefixMatches(it) }
  }

  override fun prefixMatches(name: String): Boolean {
    return delegate.prefixMatches(name) || HelidonConfigKeyMatcher.matchesPrefix(name, prefix)
  }

  override fun cloneWithPrefix(prefix: String): PrefixMatcher {
    return if (prefix == myPrefix) this else HelidonConfigKeyPrefixMatcher(prefix, delegate)
  }
}
