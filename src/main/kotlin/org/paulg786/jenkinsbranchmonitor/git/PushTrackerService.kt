package org.paulg786.jenkinsbranchmonitor.git

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import git4idea.push.GitPushListener
import git4idea.push.GitPushRepoResult
import git4idea.repo.GitRepository
import org.paulg786.jenkinsbranchmonitor.core.BuildPollerService
import org.paulg786.jenkinsbranchmonitor.core.PushStatsService
import org.paulg786.jenkinsbranchmonitor.settings.ProjectRepoSettings
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers branches that were recently pushed from this project so they stay
 * monitored even after checking out another branch (configurable retention).
 */
@Service(Service.Level.PROJECT)
class PushTrackerService(private val project: Project) : Disposable {

    data class PushedBranch(
        val repoName: String,
        val branch: String,
        val remoteUrl: String?,
        val pushedAt: Long,
        @Volatile var doneAt: Long = 0
    )

    private val pushed = ConcurrentHashMap<String, PushedBranch>()

    init {
        val listener = object : GitPushListener {
            override fun onCompleted(repository: GitRepository, pushResult: GitPushRepoResult) {
                handlePush(repository, pushResult)
            }
        }
        // The topic has been published on different buses across platform versions — subscribe to both.
        project.messageBus.connect(this).subscribe(GitPushListener.TOPIC, listener)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(GitPushListener.TOPIC, listener)
    }

    private fun handlePush(repository: GitRepository, pushResult: GitPushRepoResult) {
        if (repository.project != project) return
        val typeName = pushResult.type.name
        if (typeName.startsWith("REJECTED") || typeName == "ERROR" || typeName == "NOT_PUSHED") return
        val branch = pushResult.sourceBranch.removePrefix("refs/heads/")
            .ifBlank { repository.currentBranchName ?: return }
        val remote = repository.remotes.firstOrNull { it.name == "origin" } ?: repository.remotes.firstOrNull()
        val remoteUrl = remote?.firstUrl
        val repoName = BranchTrackerService.repoNameFrom(remoteUrl) ?: repository.root.name
        if (ProjectRepoSettings.getInstance(project).isExcluded(repoName)) return
        pushed["$repoName|$branch"] = PushedBranch(repoName, branch, remoteUrl, System.currentTimeMillis())
        PushStatsService.getInstance(project).pushRecorded()
        project.getService(BuildPollerService::class.java).refreshSoon()
    }

    /**
     * Returns tracked pushes, pruning entries older than the retention and
     * finished entries older than the linger time.
     */
    fun recentlyPushed(retentionMin: Int, doneLingerMin: Int): List<PushedBranch> {
        val now = System.currentTimeMillis()
        pushed.entries.removeIf {
            it.value.pushedAt < now - retentionMin * 60_000L ||
                (it.value.doneAt > 0 && it.value.doneAt < now - doneLingerMin * 60_000L)
        }
        return pushed.values.toList()
    }

    /** Marks my push as finished (build green) — the row lingers briefly, then disappears. */
    fun markDone(repoName: String, branch: String) {
        pushed["$repoName|$branch"]?.let { if (it.doneAt == 0L) it.doneAt = System.currentTimeMillis() }
    }

    override fun dispose() {}
}
