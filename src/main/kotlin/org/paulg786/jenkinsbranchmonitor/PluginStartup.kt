package org.paulg786.jenkinsbranchmonitor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import org.paulg786.jenkinsbranchmonitor.core.BuildPollerService
import org.paulg786.jenkinsbranchmonitor.git.PushTrackerService
import org.paulg786.jenkinsbranchmonitor.pull.DashboardToolbarPatcher
import org.paulg786.jenkinsbranchmonitor.pull.PullGuardService

/** Starts the build poller when a project is opened, independent of the tool window. */
class PluginStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        project.getService(BuildPollerService::class.java)
        project.getService(PushTrackerService::class.java) // register push listener early
        ApplicationManager.getApplication().invokeLater {
            PullGuardService.getInstance().install()
            if (!project.isDisposed) project.getService(DashboardToolbarPatcher::class.java).start()
        }
    }
}
