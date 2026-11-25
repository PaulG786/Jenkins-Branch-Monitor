package org.paulg786.jenkinsbranchmonitor.model

enum class BuildState {
    RUNNING,
    SUCCESS,
    FAILURE,
    UNSTABLE,
    ABORTED,
    NOT_FOUND,
    OFFLINE,
    DETACHED,
    UNCONFIGURED,
    UNKNOWN
}

data class BuildInfo(
    val number: Int,
    val building: Boolean,
    val result: String?,
    val timestamp: Long,
    val durationMs: Long,
    val estimatedDurationMs: Long,
    val url: String
)

enum class PushPhase {
    NONE,       // row not related to one of my pushes
    WAITING,    // pushed, but no build with my changes started yet
    BUILDING,   // the build containing my push is running
    BROKEN,     // build went red after my push — watching until green again
    DONE        // my push ended green (or watch finished)
}

/** Where a monitored repository comes from. */
enum class BranchSource {
    GIT,            // registered Git root (git4idea)
    VCS_DISABLED,       // directory mapping with VCS <None> — read via git CLI
        UNREGISTERED    // Git root detected by the IDE but not registered — read via git CLI
}

data class MonitoredBranch(
    val repoName: String,
    val branch: String?,
    val remoteUrl: String?,
    val recentlyPushed: Boolean = false,
    val pushedAt: Long = 0,
    val pushDone: Boolean = false,
    val source: BranchSource = BranchSource.GIT,
    /** Local root directory (for diagnostics only). */
    val rootPath: String? = null
) {
    val displayName: String get() = "$repoName/${branch ?: "detached"}"
}

data class BranchBuildStatus(
    val branch: MonitoredBranch,
    val state: BuildState,
    val build: BuildInfo?,
    val jobUrl: String?,
    val message: String? = null,
    val pushPhase: PushPhase = PushPhase.NONE
)
