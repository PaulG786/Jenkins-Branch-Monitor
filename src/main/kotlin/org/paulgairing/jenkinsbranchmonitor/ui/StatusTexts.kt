package org.paulgairing.jenkinsbranchmonitor.ui

import org.paulgairing.jenkinsbranchmonitor.model.BranchBuildStatus
import org.paulgairing.jenkinsbranchmonitor.model.BranchSource
import org.paulgairing.jenkinsbranchmonitor.model.BuildState
import org.paulgairing.jenkinsbranchmonitor.model.PushPhase

/** Display texts shared by table, status bar widget and dialogs. */
object StatusTexts {

    fun relativeTime(timestamp: Long): String {
        if (timestamp <= 0) return ""
        val mins = (System.currentTimeMillis() - timestamp) / 60_000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 48 * 60 -> "${mins / 60} h ago"
            else -> "${mins / (60 * 24)} d ago"
        }
    }

    fun duration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun resultText(status: BranchBuildStatus): String {
        val build = status.build
        val ago = relativeTime(build?.timestamp ?: 0)
        return when (status.state) {
            BuildState.RUNNING -> {
                val elapsed = duration(System.currentTimeMillis() - (build?.timestamp ?: 0))
                val est = build?.estimatedDurationMs ?: 0
                if (est > 0) "running $elapsed / ~${(est + 59_999) / 60_000} min" else "running $elapsed"
            }
            BuildState.SUCCESS -> "passed · $ago"
            BuildState.FAILURE -> "failed · $ago"
            BuildState.UNSTABLE -> "unstable · $ago"
            BuildState.ABORTED -> "aborted · $ago"
            BuildState.UNKNOWN -> (build?.result?.lowercase() ?: "unknown") + if (ago.isNotEmpty()) " · $ago" else ""
            BuildState.NOT_FOUND -> status.message ?: "no Jenkins job found"
            BuildState.OFFLINE -> "Jenkins unreachable"
            BuildState.DETACHED -> "detached HEAD"
            BuildState.UNCONFIGURED -> "configure Jenkins server…"
        }
    }

    fun pushText(status: BranchBuildStatus): String = when {
        status.pushPhase == PushPhase.WAITING -> "⏳ waiting for your build"
        status.pushPhase == PushPhase.BUILDING -> "⏳ your push is building"
        status.pushPhase == PushPhase.BROKEN -> "⚠ red — waiting for green"
        status.branch.recentlyPushed && status.branch.pushDone -> "✓ your build passed"
        status.branch.recentlyPushed -> "pushed ${relativeTime(status.branch.pushedAt)}"
        else -> ""
    }

    fun sourceText(source: BranchSource): String? = when (source) {
        BranchSource.GIT -> null
        BranchSource.VCS_DISABLED -> "VCS off"
        BranchSource.UNREGISTERED -> "unregistered"
    }

    fun isRed(status: BranchBuildStatus, includeUnstable: Boolean): Boolean =
        status.state == BuildState.FAILURE || (includeUnstable && status.state == BuildState.UNSTABLE)

    fun tooltip(status: BranchBuildStatus): String {
        val build = status.build
        val sb = StringBuilder("<html><b>${status.branch.displayName}</b><br>State: ${status.state}")
        when (status.branch.source) {
            BranchSource.VCS_DISABLED -> sb.append("<br>VCS mapping disabled — branch read via git")
            BranchSource.UNREGISTERED -> sb.append("<br>Git root not registered in the IDE — branch read via git")
            BranchSource.GIT -> {}
        }
        if (status.branch.recentlyPushed) {
            sb.append("<br>Tracking your push (${relativeTime(status.branch.pushedAt)})")
        }
        if (build != null) {
            sb.append("<br>Build #").append(build.number)
            if (build.durationMs > 0) sb.append("<br>Duration: ").append(duration(build.durationMs))
        }
        status.jobUrl?.let { sb.append("<br>").append(it) }
        status.message?.let { sb.append("<br>").append(it) }
        sb.append("<br><i>Right-click for log / browser · type to filter</i></html>")
        return sb.toString()
    }
}
