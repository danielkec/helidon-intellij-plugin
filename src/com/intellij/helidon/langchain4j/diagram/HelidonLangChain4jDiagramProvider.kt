// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.langchain4j.diagram

import com.intellij.diagram.AbstractDiagramElementManager
import com.intellij.diagram.BaseDiagramProvider
import com.intellij.diagram.DiagramDataModel
import com.intellij.diagram.DiagramBuilder
import com.intellij.diagram.DiagramEdgeBase
import com.intellij.diagram.DiagramElementManager
import com.intellij.diagram.DiagramNode
import com.intellij.diagram.DiagramNodeBase
import com.intellij.diagram.DiagramPresentationModel
import com.intellij.diagram.DiagramProvider
import com.intellij.diagram.DiagramRelationshipInfoAdapter
import com.intellij.diagram.DiagramVfsResolver
import com.intellij.diagram.EmptyDiagramVisibilityManager
import com.intellij.diagram.presentation.DiagramLineType
import com.intellij.helidon.HelidonIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.graph.builder.NodeGroupDescriptor
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.SimpleColoredText
import com.intellij.ui.SimpleTextAttributes
import com.intellij.uml.core.actions.ShowDiagram
import javax.swing.Icon

internal class HelidonLangChain4jDiagramProvider : BaseDiagramProvider<HelidonLangChain4jDiagramElement>() {
  private val elementManager = HelidonLangChain4jDiagramElementManager(this)
  private val vfsResolver = HelidonLangChain4jDiagramVfsResolver()

  override fun getID(): String = HELIDON_LANGCHAIN4J_DIAGRAM_ID

  override fun getPresentableName(): String = "Helidon LangChain4j Workflow"

  override fun getActionName(isPopup: Boolean): String = "Helidon LangChain4j Workflow Diagram"

  override fun getActionIcon(isPopup: Boolean): Icon = HelidonIcons.RobotGutter

  override fun createDataModel(project: Project,
                               element: HelidonLangChain4jDiagramElement?,
                               file: VirtualFile?,
                               presentationModel: DiagramPresentationModel): DiagramDataModel<HelidonLangChain4jDiagramElement> {
    val seed = element ?: HelidonLangChain4jDiagramElement(
      id = "root:${project.name}",
      name = "langchain4j",
      kind = HelidonLangChain4jDiagramNodeKind.ROOT,
      psiElement = null,
      module = null,
      includeTests = false,
    )
    return HelidonLangChain4jDiagramDataModel(project, this, seed)
  }

  override fun createVisibilityManager() = EmptyDiagramVisibilityManager.INSTANCE

  override fun getElementManager(): DiagramElementManager<HelidonLangChain4jDiagramElement> = elementManager

  override fun getVfsResolver(): DiagramVfsResolver<HelidonLangChain4jDiagramElement> = vfsResolver
}

internal class HelidonLangChain4jShowDiagramAction : ShowDiagram(), DumbAware {
  override fun getForcedProvider(): DiagramProvider<*> {
    return DiagramProvider.findByID<HelidonLangChain4jDiagramElement>(HELIDON_LANGCHAIN4J_DIAGRAM_ID)
      ?: HelidonLangChain4jDiagramProvider()
  }

  override fun update(event: AnActionEvent) {
    val provider = getForcedProvider() as DiagramProvider<HelidonLangChain4jDiagramElement>
    val element = provider.elementManager.findInDataContext(event.dataContext)
    event.presentation.isEnabledAndVisible = element != null
    event.presentation.text = "Helidon LangChain4j Workflow Diagram"
    event.presentation.icon = HelidonIcons.RobotGutter
  }
}

private class HelidonLangChain4jDiagramElementManager(
  provider: DiagramProvider<HelidonLangChain4jDiagramElement>,
) : AbstractDiagramElementManager<HelidonLangChain4jDiagramElement>() {
  init {
    setUmlProvider(provider)
  }

  override fun findInDataContext(dataContext: DataContext): HelidonLangChain4jDiagramElement? {
    val psiElement = CommonDataKeys.PSI_ELEMENT.getData(dataContext)
      ?: LangDataKeys.PSI_ELEMENT_ARRAY.getData(dataContext)?.firstOrNull()
      ?: CommonDataKeys.PSI_FILE.getData(dataContext)
    return psiElement?.let { HelidonLangChain4jWorkflowGraphBuilder.seedFromPsiElement(it) }
  }

  override fun findElementsInDataContext(dataContext: DataContext): Collection<HelidonLangChain4jDiagramElement> {
    return listOfNotNull(findInDataContext(dataContext))
  }

  override fun isAcceptableAsNode(element: Any?): Boolean = element is HelidonLangChain4jDiagramElement

  override fun getElementTitle(element: HelidonLangChain4jDiagramElement): String = element.name

  override fun getPresentableElementTitle(element: HelidonLangChain4jDiagramElement,
                                          builder: DiagramBuilder): SimpleColoredText {
    return SimpleColoredText(element.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
  }

  override fun getNodeTooltip(element: HelidonLangChain4jDiagramElement): String {
    return "${element.kind.presentableName}: ${element.name}"
  }

  override fun getItemName(element: HelidonLangChain4jDiagramElement?,
                           item: Any?,
                           builder: DiagramBuilder): SimpleColoredText {
    if (item is HelidonLangChain4jDiagramItem) {
      return SimpleColoredText(item.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
    }
    return SimpleColoredText(element?.name ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
  }

  override fun getItemType(element: HelidonLangChain4jDiagramElement?,
                           item: Any?,
                           builder: DiagramBuilder?): SimpleColoredText {
    if (item is HelidonLangChain4jDiagramItem) {
      return SimpleColoredText(item.type, SimpleTextAttributes.GRAY_ATTRIBUTES)
    }
    return SimpleColoredText(element?.kind?.presentableName ?: "", SimpleTextAttributes.GRAY_ATTRIBUTES)
  }

  override fun getItemIcon(element: HelidonLangChain4jDiagramElement?,
                           item: Any?,
                           builder: DiagramBuilder?): Icon? {
    if (item is HelidonLangChain4jDiagramItem) {
      return null
    }
    return element?.let { iconFor(it.kind) }
  }

  override fun getNodeItems(element: HelidonLangChain4jDiagramElement): Array<Any> {
    return element.items.takeIf { it.isNotEmpty() }?.toTypedArray() ?: AbstractDiagramElementManager.EMPTY_ARRAY
  }
}

private class HelidonLangChain4jDiagramVfsResolver : DiagramVfsResolver<HelidonLangChain4jDiagramElement> {
  override fun getQualifiedName(element: HelidonLangChain4jDiagramElement?): String? = element?.id

  override fun resolveElementByFQN(fqn: String, project: Project): HelidonLangChain4jDiagramElement? {
    return HelidonLangChain4jWorkflowGraphBuilder.findElement(project, fqn)
  }
}

private class HelidonLangChain4jDiagramDataModel(
  project: Project,
  private val diagramProvider: HelidonLangChain4jDiagramProvider,
  private val seed: HelidonLangChain4jDiagramElement,
) : DiagramDataModel<HelidonLangChain4jDiagramElement>(project, diagramProvider) {
  private var graph = HelidonLangChain4jWorkflowGraphBuilder.build(seed)
  private var nodes = graph.nodes.associateWith { HelidonLangChain4jDiagramNode(it, diagramProvider) }.toMutableMap()
  private var edges = graph.edges.map { HelidonLangChain4jDiagramEdge(nodes.getValue(it.source), nodes.getValue(it.target), it) }.toMutableList()
  private var groups = createGroups(graph.nodes)

  init {
    setOriginalElement(seed)
    setModelInitializationFinished()
  }

  override fun getModificationTracker(): ModificationTracker = PsiModificationTracker.getInstance(project)

  override fun getNodes(): Collection<DiagramNode<HelidonLangChain4jDiagramElement>> = nodes.values

  override fun getNodeName(node: DiagramNode<HelidonLangChain4jDiagramElement>): String = node.identifyingElement.name

  override fun addElement(element: HelidonLangChain4jDiagramElement?): DiagramNode<HelidonLangChain4jDiagramElement>? {
    if (element == null) return null
    return nodes.getOrPut(element) { HelidonLangChain4jDiagramNode(element, diagramProvider) }
  }

  override fun removeNode(node: DiagramNode<HelidonLangChain4jDiagramElement>) {
    nodes.remove(node.identifyingElement)
    edges.removeIf { it.source == node || it.target == node }
  }

  override fun hasElement(element: HelidonLangChain4jDiagramElement?): Boolean = nodes.containsKey(element)

  override fun getEdges(): Collection<HelidonLangChain4jDiagramEdge> = edges

  override fun getGroup(node: DiagramNode<HelidonLangChain4jDiagramElement>): NodeGroupDescriptor? {
    return node.identifyingElement.group?.let { groups[it] }
  }

  override fun getGroupNodes(): Collection<NodeGroupDescriptor> = groups.values

  override fun removeEdge(edge: com.intellij.diagram.DiagramEdge<HelidonLangChain4jDiagramElement>) {
    edges.remove(edge)
  }

  override fun refreshDataModel() {
    graph = HelidonLangChain4jWorkflowGraphBuilder.build(seed)
    nodes = graph.nodes.associateWith { HelidonLangChain4jDiagramNode(it, diagramProvider) }.toMutableMap()
    edges = graph.edges.map { HelidonLangChain4jDiagramEdge(nodes.getValue(it.source), nodes.getValue(it.target), it) }.toMutableList()
    groups = createGroups(graph.nodes)
  }

  override fun rebuild(element: HelidonLangChain4jDiagramElement) {
    refreshDataModel()
  }

  override fun dispose() {
  }

  private fun createGroups(elements: List<HelidonLangChain4jDiagramElement>): MutableMap<String, HelidonLangChain4jNodeGroupDescriptor> {
    return elements
      .mapNotNull { it.group }
      .distinct()
      .associateWith { HelidonLangChain4jNodeGroupDescriptor(it) }
      .toMutableMap()
  }
}

private class HelidonLangChain4jNodeGroupDescriptor(
  private val name: String,
) : NodeGroupDescriptor {
  private var closed = false

  override fun getGroupName(): String = name

  override fun getParent(): NodeGroupDescriptor? = null

  override fun isClosed(): Boolean = closed

  override fun setClosed(closed: Boolean) {
    this.closed = closed
  }
}

private class HelidonLangChain4jDiagramNode(
  private val element: HelidonLangChain4jDiagramElement,
  provider: DiagramProvider<HelidonLangChain4jDiagramElement>,
) : DiagramNodeBase<HelidonLangChain4jDiagramElement>(provider) {
  override fun getIdentifyingElement(): HelidonLangChain4jDiagramElement = element

  override fun getTooltip(): String = "${element.kind.presentableName}: ${element.name}"

  override fun getIcon(): Icon = iconFor(element.kind)

  override fun getQualifiedName(): String = element.id

  override fun getPresentableTitle(): SimpleColoredText {
    return SimpleColoredText(element.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
  }

  override fun canNavigate(): Boolean = (element.psiElement as? Navigatable)?.canNavigate() == true

  override fun navigate(requestFocus: Boolean) {
    (element.psiElement as? Navigatable)?.navigate(requestFocus)
  }
}

private class HelidonLangChain4jDiagramEdge(
  source: DiagramNode<HelidonLangChain4jDiagramElement>,
  target: DiagramNode<HelidonLangChain4jDiagramElement>,
  private val edge: HelidonLangChain4jWorkflowEdge,
) : DiagramEdgeBase<HelidonLangChain4jDiagramElement>(
  source,
  target,
  DiagramRelationshipInfoAdapter(edge.label,
                                 if (edge.kind == HelidonLangChain4jWorkflowEdgeKind.RESOURCE) {
                                   DiagramLineType.DASHED
                                 }
                                 else {
                                   DiagramLineType.SOLID
                                 }),
) {
  override fun getName(): String = edge.label

  override fun getIdentifyingElement(): HelidonLangChain4jDiagramElement = edge.target

  override fun getNavigationElements(): Array<PsiElement> {
    val navigationElement = edge.navigationElement ?: return emptyArray()
    return arrayOf(navigationElement)
  }
}

private fun iconFor(kind: HelidonLangChain4jDiagramNodeKind): Icon {
  return when (kind) {
    HelidonLangChain4jDiagramNodeKind.ENDPOINT,
    HelidonLangChain4jDiagramNodeKind.JAVA_AGENT,
    HelidonLangChain4jDiagramNodeKind.JAVA_SERVICE -> HelidonIcons.RobotGutter
    HelidonLangChain4jDiagramNodeKind.ROOT -> HelidonIcons.Helidon
    HelidonLangChain4jDiagramNodeKind.JAVA_CHAT_MODEL,
    HelidonLangChain4jDiagramNodeKind.JAVA_STREAMING_CHAT_MODEL,
    HelidonLangChain4jDiagramNodeKind.JAVA_MODERATION_MODEL -> HelidonIcons.AiGutter
    HelidonLangChain4jDiagramNodeKind.SERVICE_CONFIG,
    HelidonLangChain4jDiagramNodeKind.AGENT_CONFIG,
    HelidonLangChain4jDiagramNodeKind.MODEL_CONFIG,
    HelidonLangChain4jDiagramNodeKind.CONTENT_RETRIEVER_CONFIG -> HelidonIcons.RobotGutter
    else -> HelidonIcons.HelidonGutter
  }
}
