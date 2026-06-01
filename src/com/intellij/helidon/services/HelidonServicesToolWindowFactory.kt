// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.services

import com.intellij.icons.AllIcons
import com.intellij.helidon.HelidonIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.util.Alarm
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Locale
import java.util.concurrent.Callable
import java.util.function.Consumer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

class HelidonServicesToolWindowFactory : ToolWindowFactory {
  // Project libraries may not be imported when IntelliJ performs the one-time tool window applicability check.
  override suspend fun isApplicableAsync(project: Project): Boolean = true

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = HelidonServicesPanel(project)
    val content = ContentFactory.getInstance().createContent(panel, null, false)
    content.setDisposer(panel)
    toolWindow.contentManager.addContent(content)
    panel.refresh()
  }
}

private class HelidonServicesPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
  private val moduleFilter = JComboBox<String>()
  private val kindFilter = JComboBox<KindFilterItem>()
  private val includeTests = JCheckBox("Tests")
  private val includeLibraries = JCheckBox("Libraries")
  private val showProblems = JCheckBox("Problems")
  private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
  private val treeRoot = DefaultMutableTreeNode("Helidon Services")
  private val treeModel = DefaultTreeModel(treeRoot)
  private val tree = Tree(treeModel)
  private var updatingModuleFilter = false

  init {
    background = UIUtil.getPanelBackground()
    add(toolbar(), BorderLayout.NORTH)
    tree.background = background
    tree.isRootVisible = false
    tree.showsRootHandles = true
    tree.cellRenderer = ServicesTreeCellRenderer()
    tree.addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(event: MouseEvent) {
        if (event.clickCount == 2) {
          navigateSelected()
        }
      }
    })
    tree.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "navigate")
    tree.actionMap.put("navigate", object : javax.swing.AbstractAction() {
      override fun actionPerformed(event: java.awt.event.ActionEvent) {
        navigateSelected()
      }
    })
    val scrollPane = JBScrollPane(tree)
    scrollPane.background = background
    scrollPane.viewport.background = background
    add(scrollPane, BorderLayout.CENTER)
    PsiManager.getInstance(project).addPsiTreeChangeListener(object : PsiTreeChangeAdapter() {
      override fun childAdded(event: PsiTreeChangeEvent) = scheduleRefresh(event)

      override fun childRemoved(event: PsiTreeChangeEvent) = scheduleRefresh(event)

      override fun childReplaced(event: PsiTreeChangeEvent) = scheduleRefresh(event)

      override fun childrenChanged(event: PsiTreeChangeEvent) = scheduleRefresh(event)

      override fun propertyChanged(event: PsiTreeChangeEvent) = scheduleRefresh(event)
    }, this)
  }

  fun refresh() {
    if (project.isDisposed) return
    val filter = selectedFilter()
    ReadAction.nonBlocking(Callable {
      HelidonServicesModel.collect(project, filter)
    })
      .coalesceBy(this)
      .expireWith(this)
      .inSmartMode(project)
      .finishOnUiThread(ModalityState.nonModal(), Consumer { snapshot ->
        updateModuleFilter(snapshot.modules)
        rebuildTree(snapshot)
      })
      .submit(AppExecutorUtil.getAppExecutorService())
  }

  private fun scheduleRefresh(event: PsiTreeChangeEvent) {
    if (!isRefreshRelevant(event)) return
    scheduleRefresh()
  }

  private fun scheduleRefresh() {
    if (project.isDisposed) return
    refreshAlarm.cancelAllRequests()
    refreshAlarm.addRequest({ refresh() }, 300)
  }

  private fun isRefreshRelevant(event: PsiTreeChangeEvent): Boolean {
    val virtualFile = event.file?.originalFile?.virtualFile ?: return false
    val extension = virtualFile.extension?.lowercase(Locale.ENGLISH) ?: return false
    return extension in REFRESH_FILE_EXTENSIONS
  }

  private fun toolbar(): JPanel {
    val panel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
    panel.background = background
    moduleFilter.addItem(ALL_MODULES)
    moduleFilter.addActionListener {
      if (!updatingModuleFilter) refresh()
    }
    kindFilter.addItem(KindFilterItem(null))
    HelidonServicesNodeKind.entries.forEach { kindFilter.addItem(KindFilterItem(it)) }
    kindFilter.addActionListener { refresh() }
    includeTests.addActionListener { refresh() }
    includeLibraries.addActionListener { refresh() }
    showProblems.addActionListener { refresh() }
    val refreshButton = JButton(AllIcons.Actions.Refresh)
    refreshButton.toolTipText = "Refresh"
    refreshButton.addActionListener { refresh() }

    panel.add(JLabel("Module"))
    panel.add(moduleFilter)
    panel.add(JLabel("Kind"))
    panel.add(kindFilter)
    panel.add(includeTests)
    panel.add(includeLibraries)
    panel.add(showProblems)
    panel.add(refreshButton)
    return panel
  }

  private fun selectedFilter(): HelidonServicesFilter {
    val module = moduleFilter.selectedItem as? String
    val kind = (kindFilter.selectedItem as? KindFilterItem)?.kind
    return HelidonServicesFilter(
      moduleName = module.takeUnless { it == null || it == ALL_MODULES },
      includeTests = includeTests.isSelected,
      includeLibraries = includeLibraries.isSelected,
      kind = kind,
      showOnlyProblems = showProblems.isSelected,
    )
  }

  private fun updateModuleFilter(modules: List<String>) {
    val selected = moduleFilter.selectedItem as? String ?: ALL_MODULES
    val expected = listOf(ALL_MODULES) + modules
    val current = (0 until moduleFilter.itemCount).map { moduleFilter.getItemAt(it) }
    if (current == expected) return

    updatingModuleFilter = true
    try {
      moduleFilter.removeAllItems()
      expected.forEach(moduleFilter::addItem)
      moduleFilter.selectedItem = selected.takeIf { it in expected } ?: ALL_MODULES
    }
    finally {
      updatingModuleFilter = false
    }
  }

  private fun rebuildTree(snapshot: HelidonServicesSnapshot) {
    HelidonServicesTreeModelBuilder.rebuildTree(treeRoot, snapshot)
    treeModel.reload()
    expandInitialRows()
  }

  private fun expandInitialRows() {
    var row = 0
    var expanded = 0
    while (row < tree.rowCount && expanded < MAX_AUTO_EXPANDED_ROWS) {
      val path = tree.getPathForRow(row)
      if (path != null && path.pathCount <= AUTO_EXPAND_PATH_DEPTH) {
        tree.expandRow(row)
        expanded++
      }
      row++
    }
  }

  private fun navigateSelected() {
    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
    val servicesNode = node.userObject as? HelidonServicesNode ?: return
    val file = servicesNode.navigationFile ?: return
    OpenFileDescriptor(project, file, servicesNode.navigationOffset).navigate(true)
  }

  override fun dispose() {
  }

  private inner class ServicesTreeCellRenderer : DefaultTreeCellRenderer() {
    override fun getTreeCellRendererComponent(tree: JTree,
                                              value: Any?,
                                              selected: Boolean,
                                              expanded: Boolean,
                                              leaf: Boolean,
                                              row: Int,
                                              hasFocus: Boolean): java.awt.Component {
      val component = super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
      backgroundNonSelectionColor = tree.background
      if (!selected) {
        background = tree.background
      }
      val userObject = (value as? DefaultMutableTreeNode)?.userObject
      when (userObject) {
        is HelidonServicesNode -> {
          icon = HelidonServicesModel.icon(userObject)
          text = buildString {
            append(nodeDisplayName(userObject))
            nodeDisplayDetails(userObject)?.let { append("  ").append(it) }
            if (userObject.status != HelidonServicesResolutionStatus.RESOLVED) {
              append("  [").append(userObject.status.presentableName).append("]")
            }
            if (userObject.sourceSet != HelidonServicesSourceSet.MAIN) {
              append("  ").append(userObject.sourceSet.presentableName)
            }
          }
        }
        is String -> {
          icon = if (row == 0) HelidonIcons.Helidon else defaultClosedIcon
          text = userObject
        }
        is PackageGroupNode -> {
          icon = AllIcons.Nodes.Package
          text = userObject.name
        }
        is ClassGroupNode -> {
          icon = AllIcons.Nodes.Class
          text = userObject.name
        }
        is GroupNode -> {
          icon = groupIcon(userObject, defaultClosedIcon)
          text = userObject.name
        }
      }
      return component
    }
  }

  companion object {
    private const val ALL_MODULES = "All Modules"
    private const val AUTO_EXPAND_PATH_DEPTH = 4
    private const val MAX_AUTO_EXPANDED_ROWS = 200
    private val REFRESH_FILE_EXTENSIONS = setOf("java", "properties", "yaml", "yml")
  }
}

internal object HelidonServicesTreeModelBuilder {
  private const val DEFAULT_PACKAGE = "(default package)"

  fun rebuildTree(root: DefaultMutableTreeNode, snapshot: HelidonServicesSnapshot) {
    root.removeAllChildren()
    if (snapshot.isEmpty) {
      root.add(DefaultMutableTreeNode("No Helidon services found"))
    }
    else {
      snapshot.nodes
        .groupBy { it.moduleName }
        .toSortedMap()
        .forEach { (moduleName, moduleNodes) ->
          val moduleNode = DefaultMutableTreeNode(moduleName)
          root.add(moduleNode)
          appendGroupedNodes(moduleNode, moduleNodes)
        }
    }
  }

  private fun appendGroupedNodes(parent: DefaultMutableTreeNode, nodes: List<HelidonServicesNode>) {
    val (ownedNodes, ownerlessNodes) = nodes.partition { it.ownerClassName != null }
    val packageNodes = ownedNodes
      .groupBy { it.packageName ?: DEFAULT_PACKAGE }
      .toSortedMap()
    for ((packageName, packageChildren) in packageNodes) {
      val packageNode = DefaultMutableTreeNode(PackageGroupNode(packageName))
      parent.add(packageNode)
      appendPackageChildren(packageNode, packageChildren)
    }

    ownerlessNodes
      .groupBy { it.kind }
      .toSortedMap(compareBy { it.ordinal })
      .forEach { (kind, kindNodes) ->
        val kindNode = DefaultMutableTreeNode(kind.presentableName)
        parent.add(kindNode)
        appendKindChildren(kindNode, kindNodes)
      }
  }

  private fun appendKindChildren(parent: DefaultMutableTreeNode, nodes: List<HelidonServicesNode>) {
    val (groupedCandidates, ungroupedNodes) = nodes.partition { it.groupName != null }
    val groupedNodes = groupedCandidates
      .groupBy { GroupKey(it.groupName.orEmpty(), it.groupSortOrder) }
      .toSortedMap(compareBy<GroupKey> { it.sortOrder }.thenBy { it.name })
    for ((group, groupChildren) in groupedNodes) {
      val groupNode = DefaultMutableTreeNode(GroupNode(group.name))
      parent.add(groupNode)
      groupChildren
        .sortedWith(compareBy<HelidonServicesNode> { it.name }.thenBy { it.details.orEmpty() })
        .forEach { groupNode.add(DefaultMutableTreeNode(it)) }
    }

    ungroupedNodes
      .sortedWith(compareBy<HelidonServicesNode> { it.name }.thenBy { it.details.orEmpty() })
      .forEach { parent.add(DefaultMutableTreeNode(it)) }
  }

  private fun appendPackageChildren(parent: DefaultMutableTreeNode, nodes: List<HelidonServicesNode>) {
    val classChildren = nodes
      .groupBy { it.ownerClassQualifiedName ?: it.ownerClassName.orEmpty() }
      .toSortedMap()
    for ((_, children) in classChildren) {
      val first = children.first()
      val classNode = DefaultMutableTreeNode(ClassGroupNode(first.ownerClassName ?: first.ownerClassQualifiedName.orEmpty()))
      parent.add(classNode)
      children
        .sortedWith(compareBy<HelidonServicesNode> { it.kind.ordinal }
                      .thenBy { it.name }
                      .thenBy { it.details.orEmpty() })
        .forEach { classNode.add(DefaultMutableTreeNode(it)) }
    }
  }
}

internal data class PackageGroupNode(val name: String)

internal data class ClassGroupNode(val name: String)

internal data class GroupNode(val name: String)

private data class GroupKey(val name: String, val sortOrder: Int)

private fun nodeDisplayName(node: HelidonServicesNode): String =
  if (node.ownerClassName == node.name) {
    when (node.kind) {
      HelidonServicesNodeKind.SERVICE -> "Service"
      HelidonServicesNodeKind.CONTRACT -> "Contract"
      else -> node.name
    }
  }
  else {
    node.name
  }

private fun nodeDisplayDetails(node: HelidonServicesNode): String? =
  if (node.kind == HelidonServicesNodeKind.LANGCHAIN4J_CONFIG && node.groupName != null) {
    null
  }
  else {
    node.details
  }

private fun groupIcon(node: GroupNode, defaultIcon: javax.swing.Icon): javax.swing.Icon =
  when (node.name) {
    "models", "providers" -> HelidonIcons.AiGutter
    "embedding-stores" -> HelidonIcons.DataSourceGutter
    "content-retrievers" -> HelidonIcons.GearGutter
    else -> defaultIcon
  }

private data class KindFilterItem(val kind: HelidonServicesNodeKind?) {
  override fun toString(): String = kind?.presentableName ?: "All Kinds"
}
