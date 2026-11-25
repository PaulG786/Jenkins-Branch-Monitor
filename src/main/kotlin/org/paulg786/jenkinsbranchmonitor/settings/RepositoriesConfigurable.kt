package org.paulg786.jenkinsbranchmonitor.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.paulg786.jenkinsbranchmonitor.core.BuildPollerService
import org.paulg786.jenkinsbranchmonitor.core.JenkinsClient
import org.paulg786.jenkinsbranchmonitor.core.JobDiscoveryService
import org.paulg786.jenkinsbranchmonitor.git.BranchTrackerService
import org.paulg786.jenkinsbranchmonitor.model.BranchSource
import org.paulg786.jenkinsbranchmonitor.model.MonitoredBranch
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * Settings › Tools › Branch Monitor for Jenkins › Repositories (per project):
 * overview of all repositories found, switch monitoring off per repository and see why a job is (not) found.
 */
class RepositoriesConfigurable(private val project: Project) : Configurable {

    private class Row(
        val repoName: String,
        val branches: List<MonitoredBranch>,
        var monitored: Boolean
    ) {
        val key: String get() = repoName.lowercase()
    }

    private inner class RowModel : AbstractTableModel() {
        var rows: List<Row> = emptyList()
            set(value) {
                field = value
                fireTableDataChanged()
            }

        override fun getRowCount() = rows.size
        override fun getColumnCount() = COLUMNS.size
        override fun getColumnName(column: Int) = COLUMNS[column]
        override fun getColumnClass(column: Int): Class<*> =
            if (column == 0) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(row: Int, column: Int) = column == 0

        override fun getValueAt(row: Int, column: Int): Any {
            val r = rows[row]
            return when (column) {
                0 -> r.monitored
                1 -> r.repoName
                2 -> r.branches.joinToString(", ") { it.branch ?: "detached" }.ifEmpty { "–" }
                3 -> sourceText(r)
                else -> jobText(r)
            }
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            if (column == 0) {
                rows[row].monitored = value == true
                fireTableCellUpdated(row, column)
            }
        }
    }

    private val model = RowModel()
    private val table = JBTable(model)
    private val details = JBTextArea()
    private val checkButton = JButton("Check Job Resolution")
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "Repositories"

    override fun createComponent(): JComponent {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.emptyText.text = "Loading repositories…"
        table.setShowGrid(false)
        table.columnModel.getColumn(0).apply { maxWidth = JBUI.scale(70); preferredWidth = JBUI.scale(70) }
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showDetails() }

        details.isEditable = false
        details.font = UIUtil.getFontWithFallback("Monospaced", 0, UIUtil.getLabelFont().size)
        details.text = "Select a repository to see the Git information the Jenkins job URL is built from."

        val reloadButton = JButton("Reload")
        reloadButton.addActionListener { load() }
        checkButton.isEnabled = false
        checkButton.addActionListener { checkResolution() }

        val top = JPanel(BorderLayout()).apply {
            add(
                JBLabel(
                    "<html>Git repositories of this project. Uncheck a repository to stop monitoring it here — " +
                        "it then also disappears from the build table, the pull guard and the push statistics.</html>"
                ),
                BorderLayout.NORTH
            )
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, JBUI.scale(4))).apply {
                add(reloadButton)
                add(checkButton)
            }, BorderLayout.SOUTH)
        }
        val splitter = OnePixelSplitter(true, 0.55f).apply {
            firstComponent = top
            secondComponent = JBScrollPane(details)
        }
        val built = JPanel(BorderLayout()).apply { add(splitter, BorderLayout.CENTER) }
        panel = built
        load()
        return built
    }

    /** Git info is read off the EDT (unmanaged roots need the git executable). */
    private fun load() {
        val modality = panel?.let { ModalityState.stateForComponent(it) } ?: ModalityState.any()
        ApplicationManager.getApplication().executeOnPooledThread {
            val branches = runCatching {
                project.getService(BranchTrackerService::class.java).currentBranches(includeExcluded = true)
            }.getOrDefault(emptyList())
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed || panel == null) return@invokeLater
                val excluded = ProjectRepoSettings.getInstance(project).excludedRepos.toSet()
                val grouped = branches.groupBy { it.repoName.lowercase() }
                val rows = grouped.values.map { Row(it.first().repoName, it, it.first().repoName.lowercase() !in excluded) }
                    .toMutableList()
                // excluded repositories that are currently not found stay listed so they can be re-enabled
                excluded.filter { it !in grouped.keys }.forEach { rows.add(Row(it, emptyList(), false)) }
                model.rows = rows.sortedBy { it.key }
                table.emptyText.text = "No Git repositories found in this project"
                showDetails()
            }, modality)
        }
    }

    private fun selectedRow(): Row? = table.selectedRow.takeIf { it >= 0 }
        ?.let { model.rows.getOrNull(table.convertRowIndexToModel(it)) }

    private fun showDetails() {
        val row = selectedRow()
        checkButton.isEnabled = row != null && row.branches.any { it.branch != null }
        if (row == null) return
        val sb = StringBuilder()
        sb.append("Repository name : ").append(row.repoName).append("   (last path segment of the remote URL)\n")
        if (row.branches.isEmpty()) {
            sb.append("\nNot found in this project at the moment (excluded earlier).\n")
        }
        val statuses = project.getService(BuildPollerService::class.java).latestStatuses
        for (b in row.branches.distinctBy { it.branch to it.rootPath }) {
            sb.append('\n')
            sb.append("Branch          : ").append(b.branch ?: "detached HEAD").append('\n')
            sb.append("Root            : ").append(b.rootPath ?: "– (recently pushed, not checked out)").append('\n')
            sb.append("Source          : ").append(sourceLabel(b.source)).append('\n')
            sb.append("Remote URL      : ").append(b.remoteUrl ?: "– (no remote configured)").append('\n')
            val status = statuses.firstOrNull { it.branch.repoName == b.repoName && it.branch.branch == b.branch }
            sb.append("Jenkins job     : ").append(
                when {
                    status == null -> if (row.monitored) "not polled yet" else "not monitored"
                    status.jobUrl != null -> status.jobUrl
                    else -> status.message ?: status.state.name.lowercase()
                }
            ).append('\n')
        }
        sb.append("\nUse \"Check Job Resolution\" to list every job URL that is tried (read-only requests).")
        details.text = sb.toString()
        details.caretPosition = 0
    }

    private fun checkResolution() {
        val row = selectedRow() ?: return
        val client = JenkinsClient.fromSettings()
        if (client == null) {
            details.text = "No Jenkins server configured (Settings › Tools › Branch Monitor for Jenkins)."
            return
        }
        val branches = row.branches.filter { it.branch != null }.distinctBy { it.branch }
        checkButton.isEnabled = false
        details.text = "Checking job resolution for ${row.repoName}…"
        val modality = panel?.let { ModalityState.stateForComponent(it) } ?: ModalityState.any()
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = buildString {
                append("Server: ").append(client.baseUrl).append('\n')
                for (b in branches) {
                    append("\n=== ").append(b.repoName).append(" / ").append(b.branch).append(" ===\n")
                    append("Remote URL: ").append(b.remoteUrl ?: "–").append("\n\n")
                    val lines = runCatching {
                        JobDiscoveryService.getInstance().explain(client, b.repoName, b.branch!!, b.remoteUrl)
                    }.getOrElse { listOf("Failed: ${it.message}") }
                    lines.forEach { append(it).append('\n') }
                }
                append("\nTip: if no job is found, add a manual mapping on the main settings page, e.g.\n")
                append("  team/app\\.git => job/MY-FOLDER/job/{repo}/job/{branchEnc}\n")
            }
            ApplicationManager.getApplication().invokeLater({
                if (panel == null) return@invokeLater
                details.text = text
                details.caretPosition = 0
                checkButton.isEnabled = true
            }, modality)
        }
    }

    private fun sourceText(r: Row): String =
        r.branches.map { sourceLabel(it.source) }.distinct().joinToString(", ").ifEmpty { "not found" }

    private fun sourceLabel(source: BranchSource): String = when (source) {
        BranchSource.GIT -> "Git root"
        BranchSource.VCS_DISABLED -> "VCS <None>"
        BranchSource.UNREGISTERED -> "unregistered"
    }

    private fun jobText(r: Row): String {
        if (!r.monitored) return "–"
        val statuses = project.getService(BuildPollerService::class.java).latestStatuses
            .filter { it.branch.repoName.equals(r.repoName, ignoreCase = true) }
        if (statuses.isEmpty()) return "not polled yet"
        val found = statuses.count { it.jobUrl != null }
        return if (found == statuses.size) "found" else if (found == 0) "not found" else "$found of ${statuses.size} found"
    }

    private fun excludedFromUi(): Set<String> = model.rows.filter { !it.monitored }.map { it.key }.toSet()

    override fun isModified(): Boolean =
        excludedFromUi() != ProjectRepoSettings.getInstance(project).excludedRepos.toSet()

    override fun apply() {
        ProjectRepoSettings.getInstance(project).excludedRepos = excludedFromUi().sorted().toMutableList()
        project.getService(BuildPollerService::class.java).refreshNow()
    }

    override fun reset() {
        val excluded = ProjectRepoSettings.getInstance(project).excludedRepos.toSet()
        model.rows.forEach { it.monitored = it.key !in excluded }
        model.fireTableDataChanged()
    }

    override fun disposeUIResources() {
        panel = null
    }

    companion object {
        private val COLUMNS = arrayOf("Monitor", "Repository", "Branch", "Source", "Jenkins Job")
    }
}
