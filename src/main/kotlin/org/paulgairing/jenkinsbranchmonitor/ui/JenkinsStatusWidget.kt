package org.paulgairing.jenkinsbranchmonitor.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import org.paulgairing.jenkinsbranchmonitor.core.BuildPollerService
import org.paulgairing.jenkinsbranchmonitor.core.BuildStatusListener
import org.paulgairing.jenkinsbranchmonitor.core.PushStatsService
import org.paulgairing.jenkinsbranchmonitor.model.BranchBuildStatus
import org.paulgairing.jenkinsbranchmonitor.model.BuildState
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities

class JenkinsStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = JenkinsStatusWidget.ID
    override fun getDisplayName(): String = "Branch Monitor for Jenkins"
    override fun createWidget(project: Project): StatusBarWidget = JenkinsStatusWidget(project)
}

/**
 * Status bar summary: bubble with the worst state of all watched branches plus the
 * workspace statistics of MY pushes (↑ pushes, ✓ green, ✗ red).
 */
class JenkinsStatusWidget(private val project: Project) : CustomStatusBarWidget {

    private val label = JBLabel()

    init {
        label.border = JBUI.Borders.empty(0, 4)
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                when {
                    SwingUtilities.isRightMouseButton(e) -> showMenu(e)
                    SwingUtilities.isLeftMouseButton(e) -> openToolWindow()
                }
            }
        })
        project.messageBus.connect(this).subscribe(
            BuildStatusListener.TOPIC,
            object : BuildStatusListener {
                override fun statusesUpdated(statuses: List<BranchBuildStatus>) {
                    ApplicationManager.getApplication().invokeLater({ render(statuses) }, project.disposed)
                }
            }
        )
        render(project.getService(BuildPollerService::class.java).latestStatuses)
    }

    override fun ID(): String = ID

    override fun getComponent(): JComponent = label

    override fun install(statusBar: StatusBar) = Unit

    override fun dispose() = Unit

    private fun render(statuses: List<BranchBuildStatus>) {
        val stats = PushStatsService.getInstance(project).state
        val red = statuses.filter { it.state == BuildState.FAILURE || it.state == BuildState.UNSTABLE }
        val worst = when {
            red.any { it.state == BuildState.FAILURE } -> BuildState.FAILURE
            red.isNotEmpty() -> BuildState.UNSTABLE
            statuses.any { it.state == BuildState.RUNNING } -> BuildState.RUNNING
            statuses.any { it.state == BuildState.SUCCESS } -> BuildState.SUCCESS
            else -> BuildState.UNKNOWN
        }
        label.icon = BubbleIcon(worst, JBUI.scale(10))
        val prefix = if (red.isNotEmpty()) "${red.size} red · " else ""
        label.text = "$prefix↑${stats.pushes} ✓${stats.green} ✗${stats.red}"

        val tip = StringBuilder("<html><b>Branch Monitor for Jenkins</b>")
        tip.append("<br>Your pushes in this workspace: ${stats.pushes}")
        tip.append("<br>&nbsp;&nbsp;✓ green: ${stats.green} &nbsp; ✗ red: ${stats.red}")
        tip.append("<br><i>(only builds of your own pushes are counted)</i>")
        if (red.isNotEmpty()) {
            tip.append("<br><br><b>Red branches:</b>")
            red.forEach { s ->
                tip.append("<br>• ${s.branch.displayName} #${s.build?.number ?: "?"} · ${StatusTexts.relativeTime(s.build?.timestamp ?: 0)}")
            }
        }
        val running = statuses.count { it.state == BuildState.RUNNING }
        if (running > 0) tip.append("<br><br>Running: $running")
        tip.append("<br><br>Click: open tool window · Right-click: menu</html>")
        label.toolTipText = tip.toString()
    }

    private fun openToolWindow() {
        ToolWindowManager.getInstance(project).getToolWindow(MonitorToolWindowFactory.TOOL_WINDOW_ID)?.activate(null)
    }

    private fun showMenu(e: MouseEvent) {
        val group = DefaultActionGroup()
        group.add(object : DumbAwareAction("Open Jenkins Builds", null, AllIcons.Toolwindows.ToolWindowBuild) {
            override fun actionPerformed(e: AnActionEvent) = openToolWindow()
        })
        group.add(object : DumbAwareAction("Refresh Now", null, AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) =
                project.getService(BuildPollerService::class.java).refreshNow()
        })
        group.addSeparator()
        group.add(object : DumbAwareAction("Reset Statistics…", null, AllIcons.Actions.GC) {
            override fun actionPerformed(e: AnActionEvent) {
                val ok = MessageDialogBuilder.yesNo(
                    "Reset Statistics",
                    "Reset the push statistics (pushes / green / red) of this workspace?"
                ).yesText("Reset").noText("Cancel").ask(project)
                if (ok) {
                    PushStatsService.getInstance(project).reset()
                    render(project.getService(BuildPollerService::class.java).latestStatuses)
                }
            }
        })
        JBPopupFactory.getInstance().createActionGroupPopup(
            null, group, DataManager.getInstance().getDataContext(label),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true
        ).show(RelativePoint(e))
    }

    companion object {
        const val ID = "JenkinsBranchMonitor.StatusWidget"
    }
}
