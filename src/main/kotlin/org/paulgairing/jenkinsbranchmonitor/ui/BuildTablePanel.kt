package org.paulgairing.jenkinsbranchmonitor.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.speedSearch.SpeedSearchSupply
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import org.paulgairing.jenkinsbranchmonitor.core.BuildPollerService
import org.paulgairing.jenkinsbranchmonitor.core.BuildStatusListener
import org.paulgairing.jenkinsbranchmonitor.log.LogViewerService
import org.paulgairing.jenkinsbranchmonitor.model.BranchBuildStatus
import org.paulgairing.jenkinsbranchmonitor.model.BuildState
import org.paulgairing.jenkinsbranchmonitor.model.PushPhase
import org.paulgairing.jenkinsbranchmonitor.settings.PluginSettings
import org.paulgairing.jenkinsbranchmonitor.settings.SettingsConfigurable
import java.awt.BorderLayout
import java.awt.Point
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableColumn
import javax.swing.table.TableRowSorter

class BuildTablePanel(private val project: Project, parentDisposable: Disposable) : JPanel(BorderLayout()) {

    enum class Column(val title: String, val hideable: Boolean, val width: Int) {
        STATUS("", false, 24),
        REPOSITORY("Repository", false, 220),
        BRANCH("Branch", true, 150),
        BUILD("Build", true, 70),
        RESULT("Result", true, 190),
        PUSH("Your push", true, 200)
    }

    private val model = StatusTableModel()
    private val table = JBTable(model)
    private val sorter = TableRowSorter(model)
    private val allColumns = ArrayList<TableColumn>()

    init {
        configureTable()
        add(createToolbar(), BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)

        project.messageBus.connect(parentDisposable).subscribe(
            BuildStatusListener.TOPIC,
            object : BuildStatusListener {
                override fun statusesUpdated(statuses: List<BranchBuildStatus>) {
                    ApplicationManager.getApplication().invokeLater { setRows(statuses) }
                }
            }
        )
        setRows(project.getService(BuildPollerService::class.java).latestStatuses)
    }

    private fun configureTable() {
        table.autoCreateColumnsFromModel = false
        table.setShowGrid(false)
        table.intercellSpacing = JBUI.emptySize()
        table.rowHeight = JBUI.scale(22)
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.tableHeader.reorderingAllowed = false
        table.emptyText.text = EMPTY_TEXT
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF

        // Rows keep the activity order of the tracker unless the user clicks a column header.
        Column.entries.forEach { col -> sorter.setComparator(col.ordinal, comparatorFor(col)) }
        sorter.sortsOnUpdates = true
        table.rowSorter = sorter

        val renderer = StatusCellRenderer()
        Column.entries.forEach { col ->
            val tc = TableColumn(col.ordinal, JBUI.scale(col.width), renderer, null)
            tc.headerValue = col.title
            if (col == Column.STATUS) {
                tc.minWidth = JBUI.scale(col.width)
                tc.maxWidth = JBUI.scale(col.width)
            }
            allColumns.add(tc)
        }
        applyColumnVisibility()

        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowRowMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowRowMenu(e)
        })
        installSpeedSearch()
        // Keyboard equivalents of right-click (IntelliJ convention): context-menu key and Shift+F10.
        table.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0), "jbmShowMenu")
        table.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F10, KeyEvent.SHIFT_DOWN_MASK), "jbmShowMenu")
        table.actionMap.put("jbmShowMenu", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                val viewRow = table.selectedRow
                if (viewRow < 0) return
                val rect = table.getCellRect(viewRow, 0, true)
                showRowMenu(viewRow, RelativePoint(table, Point(rect.x + rect.width, rect.y + rect.height)))
            }
        })
        table.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowHeaderMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowHeaderMenu(e)
        })
    }

    private fun createToolbar(): JComponent {
        val group = DefaultActionGroup()
        group.add(object : DumbAwareAction("Refresh", "Poll Jenkins now", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                project.getService(BuildPollerService::class.java).refreshNow()
            }
        })
        group.add(object : DumbAwareAction(
            "Reset Sort Order", "Back to activity order (active pushes first, then recently checked out)",
            AllIcons.ObjectBrowser.Sorted
        ) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !sorter.sortKeys.isNullOrEmpty()
            }

            override fun actionPerformed(e: AnActionEvent) {
                sorter.sortKeys = null
            }
        })
        group.add(object : DumbAwareAction("Settings", "Open plugin settings", AllIcons.General.Settings) {
            override fun actionPerformed(e: AnActionEvent) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, SettingsConfigurable::class.java)
            }
        })
        val toolbar = ActionManager.getInstance().createActionToolbar("JenkinsBranchMonitor", group, true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    private fun setRows(statuses: List<BranchBuildStatus>) {
        val selected = table.selectedRow.takeIf { it >= 0 }
            ?.let { model.rows.getOrNull(table.convertRowIndexToModel(it))?.branch?.displayName }
        model.rows = statuses
        model.fireTableDataChanged()
        fitColumnsToContent()
        if (selected != null) {
            val modelRow = statuses.indexOfFirst { it.branch.displayName == selected }
            if (modelRow >= 0) {
                val viewRow = table.convertRowIndexToView(modelRow)
                if (viewRow >= 0) table.setRowSelectionInterval(viewRow, viewRow)
            }
        }
    }

    // ---- speed search: just start typing, non-matching rows are hidden ----

    private fun installSpeedSearch() {
        val speedSearch = TableSpeedSearch.installOn(table) { value, cell ->
            val status = value as? BranchBuildStatus
            if (status == null || table.convertColumnIndexToModel(cell.column) != Column.REPOSITORY.ordinal) {
                null
            } else {
                searchText(status)
            }
        }
        speedSearch.addChangeListener { event ->
            if (event.propertyName != SpeedSearchSupply.ENTERED_PREFIX_PROPERTY_NAME) return@addChangeListener
            applyFilter(event.newValue as? String)
        }
    }

    private fun searchText(status: BranchBuildStatus) = "${status.branch.repoName}/${status.branch.branch ?: "detached"}"

    private fun applyFilter(prefix: String?) {
        val selected = selectedStatus()?.branch?.displayName
        if (prefix.isNullOrBlank()) {
            sorter.rowFilter = null
        } else {
            val matcher = NameUtil.buildMatcher("*$prefix", NameUtil.MatchingCaseSensitivity.NONE)
            sorter.rowFilter = object : RowFilter<StatusTableModel, Int>() {
                override fun include(entry: Entry<out StatusTableModel, out Int>): Boolean {
                    val status = entry.model.rows.getOrNull(entry.identifier) ?: return false
                    return matcher.matches(searchText(status))
                }
            }
        }
        val modelRow = model.rows.indexOfFirst { it.branch.displayName == selected }
        val viewRow = if (modelRow >= 0) table.convertRowIndexToView(modelRow) else -1
        when {
            viewRow >= 0 -> table.setRowSelectionInterval(viewRow, viewRow)
            table.rowCount > 0 -> table.setRowSelectionInterval(0, 0)
        }
        table.emptyText.text = if (prefix.isNullOrBlank()) EMPTY_TEXT else "Nothing matches “$prefix”"
    }

    private fun selectedStatus(): BranchBuildStatus? =
        table.selectedRow.takeIf { it >= 0 }?.let { model.rows.getOrNull(table.convertRowIndexToModel(it)) }

    // ---- row menu (right-click only): log or browser (read-only) ----

    private fun maybeShowRowMenu(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val viewRow = table.rowAtPoint(e.point)
        if (viewRow < 0) return
        table.setRowSelectionInterval(viewRow, viewRow)
        showRowMenu(viewRow, RelativePoint(e))
    }

    private fun showRowMenu(viewRow: Int, point: RelativePoint) {
        val status = model.rows.getOrNull(table.convertRowIndexToModel(viewRow)) ?: return
        val group = DefaultActionGroup()
        val build = status.build
        if (build != null) {
            group.add(simpleAction("Show Log", AllIcons.Debugger.Console) {
                LogViewerService.getInstance(project).openLog(status)
            })
            group.add(simpleAction("Open Build in Browser", AllIcons.General.Web) { BrowserUtil.browse(build.url) })
        }
        status.jobUrl?.let { jobUrl ->
            group.add(simpleAction("Open Job in Browser", AllIcons.Nodes.Folder) { BrowserUtil.browse(jobUrl) })
        }
        (build?.url ?: status.jobUrl)?.let { url ->
            group.addSeparator()
            group.add(simpleAction("Copy URL", AllIcons.Actions.Copy) {
                CopyPasteManager.getInstance().setContents(StringSelection(url))
            })
        }
        if (status.state == BuildState.UNCONFIGURED || status.state == BuildState.NOT_FOUND ||
            status.state == BuildState.OFFLINE
        ) {
            group.addSeparator()
            group.add(simpleAction("Settings…", AllIcons.General.Settings) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, SettingsConfigurable::class.java)
            })
        }
        if (group.childrenCount == 0) return
        JBPopupFactory.getInstance().createActionGroupPopup(
            status.branch.displayName,
            group,
            DataManager.getInstance().getDataContext(table),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true
        ).show(point)
    }

    private fun simpleAction(text: String, icon: javax.swing.Icon, block: () -> Unit): AnAction =
        object : DumbAwareAction(text, null, icon) {
            override fun actionPerformed(e: AnActionEvent) = block()
        }

    // ---- column visibility ----

    private fun hiddenColumns(): Set<String> =
        PluginSettings.getInstance().hiddenColumns.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun applyColumnVisibility() {
        val hidden = hiddenColumns()
        val cm = table.columnModel
        while (cm.columnCount > 0) cm.removeColumn(cm.getColumn(0))
        Column.entries.forEach { col ->
            if (!col.hideable || col.name !in hidden) cm.addColumn(allColumns[col.ordinal])
        }
        fitColumnsToContent()
    }

    /** Each column gets exactly the width its widest cell (or header) needs. */
    private fun fitColumnsToContent() {
        val cm = table.columnModel
        val headerRenderer = table.tableHeader.defaultRenderer
        val padding = JBUI.scale(12)
        for (viewCol in 0 until cm.columnCount) {
            val column = cm.getColumn(viewCol)
            if (Column.entries[column.modelIndex] == Column.STATUS) continue
            var width = headerRenderer.getTableCellRendererComponent(
                table, column.headerValue, false, false, -1, viewCol
            ).preferredSize.width
            for (row in 0 until table.rowCount) {
                val c = table.prepareRenderer(table.getCellRenderer(row, viewCol), row, viewCol)
                width = maxOf(width, c.preferredSize.width)
            }
            width += padding
            column.minWidth = JBUI.scale(30)
            column.preferredWidth = width
            column.width = width
        }
    }

    private fun maybeShowHeaderMenu(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val hidden = hiddenColumns()
        val menu = JPopupMenu()
        Column.entries.filter { it.hideable }.forEach { col ->
            val item = JCheckBoxMenuItem(col.title, col.name !in hidden)
            item.addActionListener {
                val now = hiddenColumns().toMutableSet()
                if (item.isSelected) now.remove(col.name) else now.add(col.name)
                PluginSettings.getInstance().hiddenColumns = now.joinToString(",")
                applyColumnVisibility()
            }
            menu.add(item)
        }
        menu.show(e.component, e.x, e.y)
    }

    // ---- model / rendering ----

    private class StatusTableModel : AbstractTableModel() {
        var rows: List<BranchBuildStatus> = emptyList()
        override fun getRowCount() = rows.size
        override fun getColumnCount() = Column.entries.size
        override fun getColumnName(column: Int) = Column.entries[column].title
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = rows[rowIndex]
    }

    private fun comparatorFor(col: Column): Comparator<BranchBuildStatus> = when (col) {
        Column.STATUS -> compareBy { severity(it.state) }
        Column.REPOSITORY -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.branch.repoName }
        Column.BRANCH -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.branch.branch ?: "" }
        Column.BUILD -> compareBy { it.build?.number ?: -1 }
        Column.RESULT -> compareBy { -(it.build?.timestamp ?: 0L) }
        Column.PUSH -> compareBy { -it.branch.pushedAt }
    }

    private fun severity(state: BuildState): Int = when (state) {
        BuildState.FAILURE -> 0
        BuildState.UNSTABLE -> 1
        BuildState.RUNNING -> 2
        BuildState.SUCCESS -> 3
        else -> 4
    }

    private class StatusCellRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(
            table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ) {
            val status = value as? BranchBuildStatus ?: return
            icon = null
            border = JBUI.Borders.empty(0, 4)
            toolTipText = StatusTexts.tooltip(status)
            when (Column.entries[table.convertColumnIndexToModel(column)]) {
                Column.STATUS -> icon = BubbleIcon(status.state)
                Column.REPOSITORY -> {
                    append(status.branch.repoName)
                    StatusTexts.sourceText(status.branch.source)?.let {
                        append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                }
                Column.BRANCH -> append(status.branch.branch ?: "detached", SimpleTextAttributes.GRAY_ATTRIBUTES)
                Column.BUILD -> status.build?.let { append("#${it.number}") }
                Column.RESULT -> append(StatusTexts.resultText(status), resultAttributes(status.state))
                Column.PUSH -> {
                    val text = StatusTexts.pushText(status)
                    if (text.isNotEmpty()) append(text, pushAttributes(status))
                }
            }
        }

        private fun resultAttributes(state: BuildState): SimpleTextAttributes = when (state) {
            BuildState.FAILURE, BuildState.UNSTABLE, BuildState.RUNNING ->
                SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, BubbleIcon.colorOf(state))
            BuildState.SUCCESS -> SimpleTextAttributes.REGULAR_ATTRIBUTES
            else -> SimpleTextAttributes.GRAYED_ATTRIBUTES
        }

        private fun pushAttributes(status: BranchBuildStatus): SimpleTextAttributes = when (status.pushPhase) {
            PushPhase.BROKEN -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, BubbleIcon.colorOf(BuildState.FAILURE))
            PushPhase.BUILDING, PushPhase.WAITING ->
                SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, BubbleIcon.colorOf(BuildState.RUNNING))
            else -> if (status.branch.pushDone) {
                SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, BubbleIcon.colorOf(BuildState.SUCCESS))
            } else {
                SimpleTextAttributes.GRAYED_ATTRIBUTES
            }
        }
    }

    companion object {
        private const val EMPTY_TEXT = "No Git repositories detected (or first poll pending)."
    }
}
