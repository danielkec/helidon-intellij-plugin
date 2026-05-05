// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PsiTestUtil

class HelidonConfigFileContributorTest : HelidonHighlightingTestCase() {

  fun testCollectConfigDirectoriesIncludesMainResourcesInTestScope() {
    val mainResources = addResourceRoot("src/main/resources", false)
    val testResources = addResourceRoot("src/test/resources", true)

    val configDirectories = HelidonConfigFileContributor.collectConfigDirectories(module, true)

    assertContainsElements(configDirectories, mainResources, testResources)
  }

  private fun addResourceRoot(path: String, testRoot: Boolean): VirtualFile {
    val resources = myFixture.tempDirFixture.findOrCreateDir(path)
    PsiTestUtil.addResourceContentToRoots(module, resources, testRoot)
    Disposer.register(myFixture.testRootDisposable,
                      Disposable { PsiTestUtil.removeContentEntry(module, resources) })
    return resources
  }
}
