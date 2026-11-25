package org.paulgairing.jenkinsbranchmonitor.log

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import org.paulgairing.jenkinsbranchmonitor.core.BuildStatusListener
import org.paulgairing.jenkinsbranchmonitor.model.BranchBuildStatus
import org.paulgairing.jenkinsbranchmonitor.ui.MonitorToolWindowFactory

/** Opens build logs as closeable tabs inside the "Jenkins Builds" tool window (one tab per build). */
@Service(Service.Level.PROJECT)
class LogViewerService(private val project: Project) : Disposable {

    init {
        project.messageBus.connect(this).subscribe(
            BuildStatusListener.TOPIC,
            object : BuildStatusListener {
                override fun statusesUpdated(statuses: List<BranchBuildStatus>) {
                    ApplicationManager.getApplication().invokeLater({ refreshHeaders(statuses) }, project.disposed)
                }
            }
        )
    }

    fun openLog(status: BranchBuildStatus) {
        val build = status.build ?: return
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(MonitorToolWindowFactory.TOOL_WINDOW_ID) ?: return
        toolWindow.activate {
            val manager = toolWindow.contentManager
            val existing = manager.contents.firstOrNull { it.getUserData(BUILD_URL_KEY) == build.url }
            if (existing != null) {
                manager.setSelectedContent(existing, true)
                return@activate
            }
            val panel = LogPanel(project, status)
            val content = ContentFactory.getInstance().createContent(
                panel, "Log: ${status.branch.displayName} #${build.number}", false
            )
            content.isCloseable = true
            content.putUserData(BUILD_URL_KEY, build.url)
            content.setDisposer(panel)
            content.preferredFocusableComponent = panel
            manager.addContent(content)
            manager.setSelectedContent(content, true)
        }
    }

    private fun refreshHeaders(statuses: List<BranchBuildStatus>) {
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(MonitorToolWindowFactory.TOOL_WINDOW_ID) ?: return
        val byUrl = statuses.filter { it.build != null }.associateBy { it.build!!.url }
        toolWindow.contentManager.contents.forEach { content ->
            val url = content.getUserData(BUILD_URL_KEY) ?: return@forEach
            val status = byUrl[url] ?: return@forEach
            (content.component as? LogPanel)?.update(status)
        }
    }

    override fun dispose() = Unit

    companion object {
        private val BUILD_URL_KEY = Key.create<String>("jenkinsBranchMonitor.buildUrl")

        fun getInstance(project: Project): LogViewerService = project.getService(LogViewerService::class.java)
    }
}
