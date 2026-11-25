package org.paulgairing.jenkinsbranchmonitor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import org.paulgairing.jenkinsbranchmonitor.core.BuildPollerService
import org.paulgairing.jenkinsbranchmonitor.git.PushTrackerService
import org.paulgairing.jenkinsbranchmonitor.pull.DashboardToolbarPatcher
import org.paulgairing.jenkinsbranchmonitor.pull.PullGuardService

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
