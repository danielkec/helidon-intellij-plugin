// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.testing

import com.intellij.openapi.module.Module
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import org.jetbrains.idea.maven.execution.MavenRunnerParameters

internal data class HelidonMavenTestTarget(
  val module: Module,
  val className: String,
  val methodName: String?,
  val workingDirectory: String,
  val pomFile: VirtualFile?,
  val sourceElement: PsiElement
) {
  val testPattern: String
    get() = methodName?.let { "$className#$it" } ?: className

  val goals: List<String>
    get() = listOf("test", "-Dtest=$testPattern")

  val configurationName: String
    get() = "Helidon Test: $testPattern"

  fun createRunnerParameters(): MavenRunnerParameters =
    MavenRunnerParameters(true, workingDirectory, pomFile?.name ?: "pom.xml", goals, emptyList<String>())
}
