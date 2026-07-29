// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config

import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLSequence

internal fun getPreviousYamlMappingKeyValue(element: PsiElement, offset: Int): YAMLKeyValue? {
  val file = element.containingFile.originalFile
  val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
  val text = document.charsSequence
  if (text.isEmpty()) return null

  val safeOffset = offset.coerceIn(0, text.length)
  if (safeOffset == 0) return null

  var line = document.getLineNumber(safeOffset) - 1
  while (line >= 0) {
    val lineStart = document.getLineStartOffset(line)
    val lineEnd = document.getLineEndOffset(line)
    val firstNonWhitespace = firstNonWhitespace(text, lineStart, lineEnd)
    if (firstNonWhitespace >= 0) {
      if (text[firstNonWhitespace] == '#') {
        line--
        continue
      }

      val previousLineElement = file.findElementAt(firstNonWhitespace) ?: return null
      val previousKeyValue = PsiTreeUtil.getParentOfType(previousLineElement, YAMLKeyValue::class.java) ?: return null
      val previousValue = previousKeyValue.value
      return if (previousValue == null || previousValue is YAMLMapping || previousValue is YAMLSequence) previousKeyValue else null
    }
    line--
  }
  return null
}

private fun firstNonWhitespace(text: CharSequence, startOffset: Int, endOffset: Int): Int {
  var offset = startOffset
  while (offset < endOffset) {
    val char = text[offset]
    if (char != ' ' && char != '\t') {
      return offset
    }
    offset++
  }
  return -1
}
