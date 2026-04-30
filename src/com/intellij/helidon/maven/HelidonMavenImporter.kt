// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.maven

import com.intellij.helidon.newproject.HelidonRunConfigurationService
import org.jetbrains.idea.maven.importing.MavenAfterImportConfigurator
import org.jetbrains.idea.maven.importing.hasChanges
import org.jetbrains.idea.maven.project.MavenProject

private const val HELIDON_MAVEN_PLUGIN_GROUP_ID = "io.helidon.build-tools"
private const val HELIDON_MAVEN_PLUGIN_ARTIFACT_ID = "helidon-maven-plugin"

internal class HelidonMavenImporter : MavenAfterImportConfigurator {
  override fun afterImport(context: MavenAfterImportConfigurator.Context) {
    val hasApplicableChangedProjects = context.mavenProjectsWithModules.any {
      it.hasChanges() && isApplicable(it.mavenProject)
    }
    if (!hasApplicableChangedProjects) return

    context.project.getService(HelidonRunConfigurationService::class.java)
      .createRunConfigurations(context.project, onlyForNewProjects = false)
  }

  private fun isApplicable(mavenProject: MavenProject): Boolean =
    mavenProject.findPlugin(HELIDON_MAVEN_PLUGIN_GROUP_ID, HELIDON_MAVEN_PLUGIN_ARTIFACT_ID, true) != null
}
