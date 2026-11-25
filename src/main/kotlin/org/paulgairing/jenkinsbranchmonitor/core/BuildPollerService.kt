package org.paulgairing.jenkinsbranchmonitor.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import org.paulgairing.jenkinsbranchmonitor.git.BranchTrackerService
import org.paulgairing.jenkinsbranchmonitor.git.PushTrackerService
import org.paulgairing.jenkinsbranchmonitor.model.BranchBuildStatus
import org.paulgairing.jenkinsbranchmonitor.model.BuildInfo
import org.paulgairing.jenkinsbranchmonitor.model.BuildState
import org.paulgairing.jenkinsbranchmonitor.model.MonitoredBranch
import org.paulgairing.jenkinsbranchmonitor.model.PushPhase
import org.paulgairing.jenkinsbranchmonitor.notify.BuildNotifier
import org.paulgairing.jenkinsbranchmonitor.settings.PluginSettings
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

interface BuildStatusListener {
    fun statusesUpdated(statuses: List<BranchBuildStatus>)

    companion object {
        val TOPIC: Topic<BuildStatusListener> =
            Topic.create("Jenkins Branch Monitor builds", BuildStatusListener::class.java)
    }
}

/**
 * Polls Jenkins for the current branches of the project (read-only GETs) and
 * publishes the results on the project message bus.
 */
@Service(Service.Level.PROJECT)
class BuildPollerService(private val project: Project) : Disposable {

    @Volatile
    var latestStatuses: List<BranchBuildStatus> = emptyList()
        private set

    /** When [latestStatuses] was computed (epoch ms). */
    @Volatile
    var lastPollAt: Long = 0
        private set

    private val polling = AtomicBoolean(false)
    @Volatile
    private var scheduled: ScheduledFuture<*>? = null
    private val headWatcher: ScheduledFuture<*>
    private val phaseMemory = HashMap<String, PushPhase>()

    /** Pushes ("repo/branch|pushedAt") whose first red build was already announced with the popup. */
    private val failAlerted = HashSet<String>()

    init {
        project.messageBus.connect(this).subscribe(
            GitRepository.GIT_REPO_CHANGE,
            GitRepositoryChangeListener { refreshSoon() }
        )
        // Unmanaged roots (VCS <None>/unregistered) send no Git events — watch their HEAD files cheaply.
        headWatcher = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
            if (!project.isDisposed && !polling.get() &&
                project.getService(BranchTrackerService::class.java).unmanagedHeadsChanged()
            ) {
                refreshSoon()
            }
        }, 10, 5, TimeUnit.SECONDS)
        scheduleNext(3)
    }

    fun refreshNow() {
        JobDiscoveryService.getInstance().invalidateCache()
        refreshSoon()
    }

    fun refreshSoon() {
        scheduled?.cancel(false)
        scheduleNext(1)
    }

    private fun scheduleNext(delaySec: Long) {
        if (project.isDisposed) return
        scheduled = AppExecutorUtil.getAppScheduledExecutorService()
            .schedule({ pollAndReschedule() }, delaySec, TimeUnit.SECONDS)
    }

    private fun pollAndReschedule() {
        if (project.isDisposed) return
        if (!polling.compareAndSet(false, true)) {
            scheduleNext(5)
            return
        }
        try {
            val statuses = poll()
            latestStatuses = statuses
            lastPollAt = System.currentTimeMillis()
            handlePushPhases(statuses)
            if (!project.isDisposed) {
                project.messageBus.syncPublisher(BuildStatusListener.TOPIC).statusesUpdated(statuses)
            }
        } finally {
            polling.set(false)
            val settings = PluginSettings.getInstance()
            val anyRunning = latestStatuses.any { it.state == BuildState.RUNNING }
            val delay = if (anyRunning) settings.pollIntervalRunningSec else settings.pollIntervalIdleSec
            scheduleNext(delay.coerceAtLeast(5).toLong())
        }
    }

    private fun poll(): List<BranchBuildStatus> {
        val branches = project.getService(BranchTrackerService::class.java).currentBranches()
        val client = JenkinsClient.fromSettings()
            ?: return branches.map {
                BranchBuildStatus(it, BuildState.UNCONFIGURED, null, null, "No Jenkins server configured")
            }
        val discovery = JobDiscoveryService.getInstance()
        return branches.map { branch ->
            val branchName = branch.branch
                ?: return@map BranchBuildStatus(branch, BuildState.DETACHED, null, null, "Detached HEAD — not polled")
            val pushActive = branch.pushedAt > 0 && !branch.pushDone
            try {
                val jobUrl = discovery.resolveJobUrl(client, branch.repoName, branchName, branch.remoteUrl)
                    ?: return@map BranchBuildStatus(branch, BuildState.NOT_FOUND, null, null, "No matching Jenkins job found")
                val build = client.lastBuild(jobUrl)
                    ?: return@map BranchBuildStatus(
                        branch, BuildState.NOT_FOUND, null, jobUrl, "Job has no builds yet",
                        if (pushActive) PushPhase.WAITING else PushPhase.NONE
                    )
                val state = stateOf(build)
                val phase = if (!pushActive) PushPhase.NONE else when {
                    build.timestamp < branch.pushedAt -> PushPhase.WAITING
                    build.building -> PushPhase.BUILDING
                    state == BuildState.SUCCESS -> PushPhase.DONE
                    state == BuildState.FAILURE || state == BuildState.UNSTABLE -> PushPhase.BROKEN
                    else -> PushPhase.DONE // aborted/unknown — stop watching silently
                }
                BranchBuildStatus(branch, state, build, jobUrl, null, phase)
            } catch (e: Exception) {
                BranchBuildStatus(branch, BuildState.OFFLINE, null, null, e.message ?: "Jenkins not reachable")
            }
        }
    }

    /**
     * One-off status lookup for a branch that is not in [latestStatuses] (e.g. a non-current branch
     * selected in the Git branches tree). Read-only GET; returns null if unknown/unreachable.
     */
    fun fetchStatusNow(branch: MonitoredBranch): BranchBuildStatus? {
        val branchName = branch.branch ?: return null
        val client = JenkinsClient.fromSettings() ?: return null
        return try {
            val jobUrl = JobDiscoveryService.getInstance()
                .resolveJobUrl(client, branch.repoName, branchName, branch.remoteUrl) ?: return null
            val build = client.lastBuild(jobUrl) ?: return null
            BranchBuildStatus(branch, stateOf(build), build, jobUrl)
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun stateOf(build: BuildInfo): BuildState = when {
        build.building -> BuildState.RUNNING
        build.result == "SUCCESS" -> BuildState.SUCCESS
        build.result == "UNSTABLE" -> BuildState.UNSTABLE
        build.result == "ABORTED" -> BuildState.ABORTED
        build.result == "FAILURE" -> BuildState.FAILURE
        else -> BuildState.UNKNOWN
    }

    /**
     * Notifications relate only to MY pushes: green after my push (done),
     * red after my push (broken), and green again after broken (fixed).
     * Builds triggered by other people never raise notifications.
     */
    private fun handlePushPhases(statuses: List<BranchBuildStatus>) {
        val tracker = project.getService(PushTrackerService::class.java)
        val stats = PushStatsService.getInstance(project)
        for (s in statuses) {
            val key = s.branch.displayName
            if (s.pushPhase == PushPhase.NONE) {
                phaseMemory.remove(key)
                failAlerted.removeIf { it.startsWith("$key|") }
                continue
            }
            val prev = phaseMemory[key] ?: PushPhase.NONE
            when {
                s.pushPhase == PushPhase.BROKEN && prev != PushPhase.BROKEN -> {
                    stats.redRecorded()
                    // Popup only for the first red build after a push — rebuilds that fail again stay quiet.
                    if (failAlerted.add("$key|${s.branch.pushedAt}")) BuildNotifier.buildFailed(project, s)
                }
                s.pushPhase == PushPhase.DONE && prev != PushPhase.DONE -> {
                    tracker.markDone(s.branch.repoName, s.branch.branch ?: "")
                    if (s.state == BuildState.SUCCESS) {
                        stats.greenRecorded()
                        if (prev == PushPhase.BROKEN) BuildNotifier.buildFixed(project, s)
                        else BuildNotifier.buildPassed(project, s)
                    }
                }
            }
            phaseMemory[key] = s.pushPhase
        }
    }

    override fun dispose() {
        scheduled?.cancel(false)
        headWatcher.cancel(false)
    }
}
