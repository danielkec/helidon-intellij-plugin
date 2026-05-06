// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.maven

import com.intellij.helidon.constants.HelidonConstants
import com.intellij.helidon.newproject.HelidonRunConfigurationService
import com.intellij.helidon.newproject.HelidonRunConfigurationTarget
import org.jetbrains.idea.maven.importing.MavenAfterImportConfigurator
import org.jetbrains.idea.maven.importing.hasChanges
import org.jetbrains.idea.maven.project.MavenProject

private const val HELIDON_APPLICATIONS_GROUP_ID = "io.helidon.applications"
private const val HELIDON_SE_PARENT_ARTIFACT_ID = "helidon-se"
private const val HELIDON_MP_PARENT_ARTIFACT_ID = "helidon-mp"
private const val HELIDON_APPLICATIONS_PARENT_ARTIFACT_ID = "helidon-applications"
private const val HELIDON_MAVEN_PLUGIN_GROUP_ID = "io.helidon.build-tools"
private const val HELIDON_MAVEN_PLUGIN_ARTIFACT_ID = "helidon-maven-plugin"
private const val HELIDON_WEBSERVER_GROUP_ID = "io.helidon.webserver"
private const val HELIDON_WEBSERVER_ARTIFACT_ID = "helidon-webserver"
private const val HELIDON_MICROPROFILE_BUNDLES_GROUP_ID = "io.helidon.microprofile.bundles"
private const val HELIDON_MICROPROFILE_ARTIFACT_ID = "helidon-microprofile"
private const val HELIDON_MICROPROFILE_CORE_ARTIFACT_ID = "helidon-microprofile-core"
private const val HELIDON_MICROPROFILE_CDI_GROUP_ID = "io.helidon.microprofile.cdi"
private const val HELIDON_MICROPROFILE_CDI_ARTIFACT_ID = "helidon-microprofile-cdi"
private const val MAIN_CLASS_PROPERTY = "mainClass"

internal class HelidonMavenImporter : MavenAfterImportConfigurator {
  override fun afterImport(context: MavenAfterImportConfigurator.Context) {
    val targets = context.mavenProjectsWithModules
      .filter { it.hasChanges() && isApplicable(it.mavenProject) }
      .flatMap { mavenProjectWithModules ->
        val mainClassName = mainClassName(mavenProjectWithModules.mavenProject) ?: return@flatMap emptySequence()
        mavenProjectWithModules.modules.asSequence()
          .map { HelidonRunConfigurationTarget(it.module, mainClassName) }
      }
      .toList()
    if (targets.isEmpty()) {
      return
    }

    context.project.getService(HelidonRunConfigurationService::class.java)
      .createRunConfigurations(context.project, targets, onlyForNewProjects = false)
  }

  private fun isApplicable(mavenProject: MavenProject): Boolean =
    mavenProject.findPlugin(HELIDON_MAVEN_PLUGIN_GROUP_ID, HELIDON_MAVEN_PLUGIN_ARTIFACT_ID, true) != null ||
      isHelidonApplicationParent(mavenProject) ||
      hasStarterHelidonDependency(mavenProject)

  private fun mainClassName(mavenProject: MavenProject): String? {
    mavenProject.properties.getProperty(MAIN_CLASS_PROPERTY)?.takeIf(String::isNotBlank)?.let { return it }
    if (isHelidonMpApplicationParent(mavenProject)) {
      return HelidonConstants.HELIDON_MAIN
    }
    if (hasMicroProfileDependency(mavenProject)) {
      return HelidonConstants.MP_MAIN
    }
    return null
  }

  private fun isHelidonApplicationParent(mavenProject: MavenProject): Boolean {
    val parentId = mavenProject.parentId ?: return false
    return parentId.groupId == HELIDON_APPLICATIONS_GROUP_ID &&
      parentId.artifactId in setOf(
        HELIDON_SE_PARENT_ARTIFACT_ID,
        HELIDON_MP_PARENT_ARTIFACT_ID,
        HELIDON_APPLICATIONS_PARENT_ARTIFACT_ID
      )
  }

  private fun isHelidonMpApplicationParent(mavenProject: MavenProject): Boolean {
    val parentId = mavenProject.parentId ?: return false
    return parentId.groupId == HELIDON_APPLICATIONS_GROUP_ID && parentId.artifactId == HELIDON_MP_PARENT_ARTIFACT_ID
  }

  private fun hasStarterHelidonDependency(mavenProject: MavenProject): Boolean =
    mavenProject.hasDependency(HELIDON_WEBSERVER_GROUP_ID, HELIDON_WEBSERVER_ARTIFACT_ID) ||
      hasMicroProfileDependency(mavenProject)

  private fun hasMicroProfileDependency(mavenProject: MavenProject): Boolean =
    mavenProject.hasDependency(HELIDON_MICROPROFILE_BUNDLES_GROUP_ID, HELIDON_MICROPROFILE_ARTIFACT_ID) ||
      mavenProject.hasDependency(HELIDON_MICROPROFILE_BUNDLES_GROUP_ID, HELIDON_MICROPROFILE_CORE_ARTIFACT_ID) ||
      mavenProject.hasDependency(HELIDON_MICROPROFILE_CDI_GROUP_ID, HELIDON_MICROPROFILE_CDI_ARTIFACT_ID)
}
