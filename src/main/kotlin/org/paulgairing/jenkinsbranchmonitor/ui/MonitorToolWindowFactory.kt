package org.paulgairing.jenkinsbranchmonitor.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import org.paulgairing.jenkinsbranchmonitor.core.BuildPollerService

class MonitorToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        project.getService(BuildPollerService::class.java) // make sure the poller is running
        val panel = BuildTablePanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, "Builds", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        const val TOOL_WINDOW_ID = "Jenkins Builds"
    }
}
