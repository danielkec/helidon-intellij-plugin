// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.templates

import com.intellij.helidon.HelidonIcons
import com.intellij.ide.fileTemplates.FileTemplate
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.ide.fileTemplates.FileTemplateUtil
import com.intellij.ide.fileTemplates.actions.AttributesDefaults
import com.intellij.ide.fileTemplates.actions.CreateFromTemplateActionBase
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import javax.swing.Icon

abstract class HelidonCreateFileFromTemplateAction(
  private val templateName: String,
  presentableName: String,
  icon: Icon = HelidonIcons.Helidon,
  private val fixedFileName: String? = null,
) : CreateFromTemplateActionBase(
  presentableName,
  "Create $presentableName from Helidon template",
  icon,
), DumbAware {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(event: AnActionEvent) {
    val project = event.project
    val view = event.getData(LangDataKeys.IDE_VIEW)
    if (project == null || view == null) {
      event.presentation.isEnabledAndVisible = false
      return
    }

    event.presentation.isEnabledAndVisible = FileTemplateUtil.canCreateFromTemplate(view.directories, template(project))
  }

  override fun getTemplate(project: Project, dir: PsiDirectory): FileTemplate = template(project)

  override fun getAttributesDefaults(dataContext: DataContext): AttributesDefaults? {
    return fixedFileName?.let { AttributesDefaults(it).withFixedName(true) }
  }

  private fun template(project: Project): FileTemplate {
    return FileTemplateManager.getInstance(project).getJ2eeTemplate(templateName)
  }
}

class HelidonCreateSeServiceAction : HelidonCreateFileFromTemplateAction(
  HELIDON_SE_SERVICE_TEMPLATE,
  "SE Service",
)

class HelidonCreateMpResourceAction : HelidonCreateFileFromTemplateAction(
  HELIDON_MP_RESOURCE_TEMPLATE,
  "MP Resource",
)

class HelidonCreateDeclarativeHttpServiceAction : HelidonCreateFileFromTemplateAction(
  HELIDON_DECLARATIVE_HTTP_SERVICE_TEMPLATE,
  "Declarative HTTP Service",
)

class HelidonCreateConfigClassAction : HelidonCreateFileFromTemplateAction(
  HELIDON_CONFIG_CLASS_TEMPLATE,
  "Config Class",
)

class HelidonCreateServerTestAction : HelidonCreateFileFromTemplateAction(
  HELIDON_SERVER_TEST_TEMPLATE,
  "Server Test",
)

class HelidonCreateLangChain4jServiceAction : HelidonCreateFileFromTemplateAction(
  HELIDON_LANGCHAIN4J_SERVICE_TEMPLATE,
  "LangChain4j Service",
)

class HelidonCreateLangChain4jAgentAction : HelidonCreateFileFromTemplateAction(
  HELIDON_LANGCHAIN4J_AGENT_TEMPLATE,
  "LangChain4j Agent",
)

class HelidonCreateOciConfigAction : HelidonCreateFileFromTemplateAction(
  HELIDON_OCI_CONFIG_TEMPLATE,
  "OCI Config",
  HelidonIcons.Ora,
  fixedFileName = HELIDON_OCI_CONFIG_FILE_STEM,
)
