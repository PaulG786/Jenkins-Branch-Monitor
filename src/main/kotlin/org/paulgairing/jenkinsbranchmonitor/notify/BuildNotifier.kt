package org.paulgairing.jenkinsbranchmonitor.notify

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import org.paulgairing.jenkinsbranchmonitor.log.LogViewerService
import org.paulgairing.jenkinsbranchmonitor.model.BranchBuildStatus

object BuildNotifier {

    private const val GROUP_ID = "Branch Monitor for Jenkins"

    fun buildPassed(project: Project, status: BranchBuildStatus) = notify(
        project,
        "Your build passed",
        "${status.branch.displayName} #${status.build?.number ?: "?"} is green",
        NotificationType.INFORMATION,
        status
    )

    fun buildFailed(project: Project, status: BranchBuildStatus) = notify(
        project,
        "Build failed after your push",
        "${status.branch.displayName} #${status.build?.number ?: "?"}",
        NotificationType.ERROR,
        status
    )

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
