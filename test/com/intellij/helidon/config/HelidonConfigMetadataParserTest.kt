// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightVirtualFile
import java.io.InputStream
import java.lang.reflect.Proxy

class HelidonConfigMetadataParserTest : HelidonHighlightingTestCase() {

  fun testRethrowsProcessCanceledExceptionFromMetadataLoading() {
    val metadataFile = psiFileForVirtualFile(object : LightVirtualFile("config-metadata.json", "[]") {
      override fun contentsToByteArray(): ByteArray {
        throw ProcessCanceledException()
      }

      override fun getInputStream(): InputStream {
        throw ProcessCanceledException()
      }
    })

    try {
      HelidonConfigMetadataParser().parse(metadataFile)
      fail("Expected ProcessCanceledException")
    }
    catch (_: ProcessCanceledException) {
    }
  }

  private fun psiFileForVirtualFile(virtualFile: VirtualFile): PsiFile {
    val resolveScope = GlobalSearchScope.allScope(project)
    return Proxy.newProxyInstance(
      PsiFile::class.java.classLoader,
      arrayOf(PsiFile::class.java)
    ) { proxy, method, args ->
      when (method.name) {
        "getVirtualFile" -> virtualFile
        "getResolveScope" -> resolveScope
        "getProject" -> project
        "toString" -> "PsiFile proxy for ${virtualFile.path}"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> args?.singleOrNull() === proxy
        else -> throw UnsupportedOperationException("Unexpected PsiFile method ${method.name}")
      }
    } as PsiFile
  }
}
