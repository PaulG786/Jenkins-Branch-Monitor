package org.paulgairing.jenkinsbranchmonitor.notify

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import org.paulgairing.jenkinsbranchmonitor.log.LogViewerService
import org.paulgairing.jenkinsbranchmonitor.model.BranchBuildStatus
import org.paulgairing.jenkinsbranchmonitor.model.BuildState
import org.paulgairing.jenkinsbranchmonitor.ui.StatusTexts

object BuildNotifier {

    private const val GROUP_ID = "Branch Monitor for Jenkins"

    fun buildPassed(project: Project, status: BranchBuildStatus) = notify(
        project,
        "Your build passed",
        "${status.branch.displayName} #${status.build?.number ?: "?"} is green",
        NotificationType.INFORMATION,
        status
    )

    /**
     * First red build after my push: a dialog that has to be dismissed instead of an easily missed balloon.
     * Shown only when no other modal dialog is open (never interrupts e.g. a commit dialog).
     */
    fun buildFailed(project: Project, status: BranchBuildStatus) {
        ApplicationManager.getApplication().invokeLater({
            val build = status.build
            val url = build?.url ?: status.jobUrl
            val what = if (status.state == BuildState.UNSTABLE) "is unstable" else "failed"
            val ago = StatusTexts.relativeTime(build?.timestamp ?: 0)
            val message = "The Jenkins build after your push $what:\n\n" +
                "  ${status.branch.displayName}  #${build?.number ?: "?"}" + (if (ago.isNotEmpty()) "  ·  $ago" else "")
            val buttons = buildList {
                if (build != null) add(SHOW_LOG)
                if (url != null) add(OPEN_IN_BROWSER)
                add(CLOSE)
            }
            val index = Messages.showDialog(
                project, message, "Build Failed After Your Push",
                buttons.toTypedArray(), buttons.lastIndex, Messages.getErrorIcon()
            )
            when (buttons.getOrNull(index)) {
                SHOW_LOG -> LogViewerService.getInstance(project).openLog(status)
                OPEN_IN_BROWSER -> url?.let { BrowserUtil.browse(it) }
            }
        }, ModalityState.nonModal(), project.disposed)
    }

    private const val SHOW_LOG = "Show Log"
    private const val OPEN_IN_BROWSER = "Open in Browser"
    private const val CLOSE = "Close"

    fun buildFixed(project: Project, status: BranchBuildStatus) = notify(
        project,
        "Build green again",
        "${status.branch.displayName} #${status.build?.number ?: "?"} is green again",
        NotificationType.INFORMATION,
        status
    )

    private fun notify(
        project: Project,
        title: String,
        content: String,
        type: NotificationType,
        status: BranchBuildStatus
    ) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, content, type)
        val build = status.build
        if (build != null) {
            notification.addAction(NotificationAction.createSimple("Show Log") {
                LogViewerService.getInstance(project).openLog(status)
            })
        }
        val url = build?.url ?: status.jobUrl
        if (url != null) {
            notification.addAction(NotificationAction.createSimple("Open in Browser") {
                BrowserUtil.browse(url)
            })
        }
        notification.notify(project)
    }
}
