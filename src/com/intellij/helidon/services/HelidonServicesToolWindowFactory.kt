// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.services

import com.intellij.icons.AllIcons
import com.intellij.helidon.HelidonIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.pom.Navigatable
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
      override fun childAdded(event: PsiTreeChangeEvent) = scheduleRefresh()

      override fun childRemoved(event: PsiTreeChangeEvent) = scheduleRefresh()

      override fun childReplaced(event: PsiTreeChangeEvent) = scheduleRefresh()

      override fun childrenChanged(event: PsiTreeChangeEvent) = scheduleRefresh()

      override fun propertyChanged(event: PsiTreeChangeEvent) = scheduleRefresh()
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

  private fun scheduleRefresh() {
    if (project.isDisposed) return
    refreshAlarm.cancelAllRequests()
    refreshAlarm.addRequest({ refresh() }, 300)
  }

  private fun toolbar(): JPanel {
    val panel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
    panel.background = background
    moduleFilter.addItem(ALL_MODULES)
    moduleFilter.addActionListener { refresh() }
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

    moduleFilter.removeAllItems()
    expected.forEach(moduleFilter::addItem)
    moduleFilter.selectedItem = selected.takeIf { it in expected } ?: ALL_MODULES
  }

  private fun rebuildTree(snapshot: HelidonServicesSnapshot) {
    treeRoot.removeAllChildren()
    if (snapshot.isEmpty) {
      treeRoot.add(DefaultMutableTreeNode("No Helidon services found"))
    }
    else {
      snapshot.nodes
        .groupBy { it.moduleName }
        .toSortedMap()
        .forEach { (moduleName, moduleNodes) ->
          val moduleNode = DefaultMutableTreeNode(moduleName)
          treeRoot.add(moduleNode)
          moduleNodes
            .groupBy { it.kind }
            .toSortedMap(compareBy { it.ordinal })
            .forEach { (kind, kindNodes) ->
              val kindNode = DefaultMutableTreeNode(kind.presentableName)
              moduleNode.add(kindNode)
              kindNodes.forEach { kindNode.add(DefaultMutableTreeNode(it)) }
            }
        }
    }
    treeModel.reload()
    expandAll()
  }

  private fun expandAll() {
    var row = 0
    while (row < tree.rowCount) {
      tree.expandRow(row)
      row++
    }
  }

  private fun navigateSelected() {
    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
    val servicesNode = node.userObject as? HelidonServicesNode ?: return
    val element = servicesNode.navigationElement ?: return
    val navigatable = element as? Navigatable ?: return
    if (navigatable.canNavigate()) {
      navigatable.navigate(true)
    }
  }

  override fun dispose() {
  }

  private class ServicesTreeCellRenderer : DefaultTreeCellRenderer() {
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
          icon = HelidonServicesModel.icon(userObject.kind)
          text = buildString {
            append(userObject.name)
            userObject.details?.let { append("  ").append(it) }
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
      }
      return component
    }
  }

  private data class KindFilterItem(val kind: HelidonServicesNodeKind?) {
    override fun toString(): String = kind?.presentableName ?: "All Kinds"
  }

  companion object {
    private const val ALL_MODULES = "All Modules"
  }
}
