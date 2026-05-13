// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon

import com.intellij.helidon.config.HELIDON_APPLICATION_PROPERTIES
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.VfsTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Files

abstract class HelidonHighlightingTestCase : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor {
        return HELIDON_PROJECT
    }

    override fun getTestDataPath(): String? = "testData" + getTestDirectory()

    protected open fun getTestDirectory(): String = "Override_getTestDirectory"

    protected open fun configureApplicationProperties(text: String): PsiFile {
        return myFixture.configureByText(HELIDON_APPLICATION_PROPERTIES, text)
    }

    protected fun configureContentRootOnlyFile(fileName: String, text: String): PsiFile {
        val rootPath = Files.createTempDirectory("helidon-test-content-root")
        val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(rootPath)!!
        PsiTestUtil.addContentRoot(module, root)
        Disposer.register(myFixture.testRootDisposable,
                          Disposable { PsiTestUtil.removeContentEntry(module, root) })
        val file = VfsTestUtil.createFile(root, fileName, text)
        myFixture.configureFromExistingVirtualFile(file)
        return myFixture.file
    }
}
