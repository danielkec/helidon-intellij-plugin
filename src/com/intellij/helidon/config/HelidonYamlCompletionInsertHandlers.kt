// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.editor.EditorModificationUtilEx
import com.intellij.openapi.util.text.StringUtil
import org.jetbrains.yaml.YAMLUtil
import org.jetbrains.yaml.completion.YamlKeyCompletionInsertHandler

internal val YAML_KEY_INSERT_HANDLER = InsertHandler<LookupElement> { context: InsertionContext, _: LookupElement ->
  val element = context.file.findElementAt(context.startOffset) ?: return@InsertHandler
  val indent = YAMLUtil.getIndentToThisElement(element) + 2
  val newLine = "\n" + StringUtil.repeatSymbol(' ', indent)
  val editor = context.editor
  val text = if (YamlKeyCompletionInsertHandler.isCharAtCaret(editor, ':')) {
    editor.caretModel.moveCaretRelatively(1, 0, false, false, false)
    newLine
  }
  else {
    ":$newLine"
  }
  EditorModificationUtilEx.insertStringAtCaret(editor, text)
  editor.project?.let { AutoPopupController.getInstance(it).scheduleAutoPopup(editor) }
}
