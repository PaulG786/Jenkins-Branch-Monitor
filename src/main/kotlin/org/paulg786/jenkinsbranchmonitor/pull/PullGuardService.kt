package org.paulg786.jenkinsbranchmonitor.pull

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionWrapper
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import git4idea.GitBranch
import git4idea.GitLocalBranch
import git4idea.GitRemoteBranch
import git4idea.branch.GitBranchUtil
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import org.paulg786.jenkinsbranchmonitor.core.BuildPollerService
import org.paulg786.jenkinsbranchmonitor.git.BranchTrackerService
import org.paulg786.jenkinsbranchmonitor.model.BranchBuildStatus
import org.paulg786.jenkinsbranchmonitor.model.MonitoredBranch
import org.paulg786.jenkinsbranchmonitor.settings.PluginSettings
import org.paulg786.jenkinsbranchmonitor.settings.ProjectRepoSettings
import org.paulg786.jenkinsbranchmonitor.ui.StatusTexts

/**
 * Warns before pulling/updating a branch whose Jenkins build is currently red.
 * Wraps the platform's pull/update actions; the originals are restored on unload.
 * Never blocks the pull itself — the user always decides.
 */
@Service(Service.Level.APP)
class PullGuardService : Disposable {

    /** What a guarded action pulls into the workspace. */
    enum class Kind {
        PULL_CURRENT,       // VCS menu / toolbar "Pull…"
        UPDATE_PROJECT,     // "Update Project" (Ctrl+T)
        UPDATE_SELECTED,    // Git tool window branches tree / branch popup: "Update" / "Update Selected"
        PULL_INTO_CURRENT   // branch popup: "Pull into '<current>' Using Rebase/Merge"
    }

    private val originals = LinkedHashMap<String, AnAction>()

    @Synchronized
    fun install() {
        val actionManager = ActionManager.getInstance()
        for ((id, kind) in GUARDED_ACTIONS) {
            if (originals.containsKey(id)) continue
            val original = actionManager.getAction(id) ?: continue // not present in this IDE version
            if (original is PullGuardAction) continue
            runCatching { actionManager.replaceAction(id, PullGuardAction(kind, original)) }
                .onSuccess {
                    originals[id] = original
                    thisLogger().info("Pull guard installed for $id")
                }
                .onFailure { thisLogger().warn("Could not install pull guard for $id", it) }
        }
    }

    @Synchronized
    override fun dispose() {
        val app = ApplicationManager.getApplication()
        if (app == null || app.isDisposed) return
        val actionManager = ActionManager.getInstance()
        originals.forEach { (id, original) -> runCatching { actionManager.replaceAction(id, original) } }
        originals.clear()
    }

    companion object {
        /**
         * Action IDs; actions registered without explicit id use their short class name.
         * Unknown IDs (other IDE versions) are skipped.
         */
        private val GUARDED_ACTIONS = linkedMapOf(
            "Git.Pull" to Kind.PULL_CURRENT,
            "Vcs.UpdateProject" to Kind.UPDATE_PROJECT,
            "GitUpdateSelectedBranchAction" to Kind.UPDATE_SELECTED,
            "Git.Update.Selected" to Kind.UPDATE_SELECTED,
            "GitPullBranchAction\$WithRebase" to Kind.PULL_INTO_CURRENT,
            "GitPullBranchAction\$WithMerge" to Kind.PULL_INTO_CURRENT
        )

        fun getInstance(): PullGuardService =
            ApplicationManager.getApplication().getService(PullGuardService::class.java)
    }
}

class PullGuardAction(private val kind: PullGuardService.Kind, delegate: AnAction) : AnActionWrapper(delegate) {

    /** A branch whose Jenkins build matters for this pull; [names] = local name and/or upstream name. */
    private data class Target(val repo: GitRepository, val names: List<String>)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        if (project == null || project.isDisposed || !PluginSettings.getInstance().pullGuardEnabled) {
            super.actionPerformed(e)
            return
        }
        val red = runCatching {
            val targets = targets(project, e)
            thisLogger().info("Pull guard ($kind): checking ${targets.map { "${it.repo.root.name}:${it.names}" }}")
            redBranches(project, targets).also { r ->
                thisLogger().info("Pull guard ($kind): red = ${r.map { it.branch.displayName }}")
            }
        }
            .onFailure { if (it is ProcessCanceledException) throw it else thisLogger().warn("Pull guard check failed", it) }
            .getOrDefault(emptyList())
        if (red.isEmpty() || confirm(project, red)) super.actionPerformed(e)
    }

    // ---- which branches does the action pull? ----

    private fun targets(project: Project, e: AnActionEvent): List<Target> {
        val allRepos = GitRepositoryManager.getInstance(project).repositories
        val affectedRepos = e.getData(AFFECTED_REPOSITORIES)?.takeIf { it.isNotEmpty() }
            ?: e.getData(SELECTED_REPOSITORY)?.let { listOf(it) }
        return when (kind) {
            PullGuardService.Kind.PULL_CURRENT -> {
                val repo = GitBranchUtil.guessRepositoryForOperation(project, e.dataContext)
                (repo?.let { listOf(it) } ?: allRepos).mapNotNull { currentTarget(it) }
            }
            PullGuardService.Kind.UPDATE_PROJECT -> allRepos.mapNotNull { currentTarget(it) }
            PullGuardService.Kind.UPDATE_SELECTED -> {
                val treeSelection = treeSelectionTargets(e)
                if (treeSelection != null) return treeSelection
                val repos = affectedRepos ?: allRepos
                val branches = e.getData(BRANCHES)
                if (branches.isNullOrEmpty() || e.getData(USE_CURRENT_BRANCH) == true) {
                    repos.mapNotNull { currentTarget(it) }
                } else {
                    repos.flatMap { repo -> branches.mapNotNull { branchTarget(repo, it) } }
                }
            }
            PullGuardService.Kind.PULL_INTO_CURRENT -> {
                val repos = affectedRepos ?: allRepos
                val branches = e.getData(BRANCHES).orEmpty()
                repos.flatMap { repo -> branches.mapNotNull { branchTarget(repo, it) } }
            }
        }
    }

    private fun currentTarget(repo: GitRepository): Target? = repo.currentBranch?.let { branchTarget(repo, it) }

    /**
     * Selection of the Git branches tree (internal `BranchesTreeSelection`, read reflectively so the plugin
     * does not depend on internal API): selectedBranches -> BranchInfo(branch, repositories).
     */
    private fun treeSelectionTargets(e: AnActionEvent): List<Target>? {
        val selection = e.getData(TREE_SELECTION) ?: return null
        return runCatching {
            val infos = selection.javaClass.getMethod("getSelectedBranches").invoke(selection) as? List<*>
                ?: return null
            infos.filterNotNull().flatMap { info ->
                val branch = info.javaClass.getMethod("getBranch").invoke(info) as? GitBranch
                    ?: return@flatMap emptyList()
                val repos = (info.javaClass.getMethod("getRepositories").invoke(info) as? List<*>)
                    .orEmpty().filterIsInstance<GitRepository>()
                repos.mapNotNull { branchTarget(it, branch) }
            }
        }.onFailure { thisLogger().info("Cannot read Git branches tree selection", it) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun branchTarget(repo: GitRepository, branch: GitBranch): Target? = when (branch) {
        is GitRemoteBranch -> Target(repo, listOf(branch.nameForRemoteOperations))
        is GitLocalBranch -> {
            val local = repo.branches.findLocalBranch(branch.name) ?: branch
            val upstream = local.findTrackedBranch(repo)?.nameForRemoteOperations
            Target(repo, listOfNotNull(upstream, branch.name).distinct())
        }
        else -> null
    }

    // ---- which of them are red? ----

    private fun redBranches(project: Project, targets: List<Target>): List<BranchBuildStatus> {
        if (targets.isEmpty()) return emptyList()
        val includeUnstable = PluginSettings.getInstance().pullGuardIncludeUnstable
        val poller = project.getService(BuildPollerService::class.java)
        val cached = poller.latestStatuses
        val repoSettings = ProjectRepoSettings.getInstance(project)

        val found = ArrayList<BranchBuildStatus>()
        val missing = ArrayList<MonitoredBranch>()
        for (t in targets.distinctBy { it.repo.root.path to it.names }) {
            val repoName = BranchTrackerService.repoNameOf(t.repo)
            if (repoSettings.isExcluded(repoName)) continue
            val status = cached.firstOrNull { it.branch.repoName == repoName && it.branch.branch in t.names }
            if (status != null) {
                found.add(status)
            } else {
                // e.g. a non-current branch selected in the Git tool window — look it up once (GET only)
                missing.add(MonitoredBranch(repoName, t.names.first(), BranchTrackerService.remoteUrlOf(t.repo)))
            }
        }
        if (missing.isNotEmpty()) {
            val fetched = try {
                ProgressManager.getInstance().runProcessWithProgressSynchronously(
                    ThrowableComputable<List<BranchBuildStatus>, RuntimeException> {
                        missing.mapNotNull { poller.fetchStatusNow(it) }
                    },
                    "Checking Jenkins build status…", true, project
                )
            } catch (e: ProcessCanceledException) {
                emptyList()
            }
            found.addAll(fetched)
        }
        thisLogger().info("Pull guard: statuses ${found.map { "${it.branch.displayName}=${it.state}" }} (looked up on demand: ${missing.size})")
        return found.filter { StatusTexts.isRed(it, includeUnstable) }.distinctBy { it.branch.displayName }
    }

    private fun confirm(project: Project, red: List<BranchBuildStatus>): Boolean {
        val lines = red.joinToString("\n") { s ->
            val build = s.build
            "  • ${s.branch.displayName}  #${build?.number ?: "?"}  ${s.state.name.lowercase()} · " +
                StatusTexts.relativeTime(build?.timestamp ?: 0)
        }
        val what = if (red.size == 1) "The Jenkins build of this branch is red" else "The Jenkins builds of these branches are red"
        return MessageDialogBuilder.yesNo(
            "Jenkins Build Is Red",
            "$what:\n\n$lines\n\nPulling now brings the broken state into your workspace."
        )
            .yesText("Pull Anyway")
            .noText("Cancel")
            .icon(Messages.getWarningIcon())
            .ask(project)
    }

    companion object {
        // Data keys of the Git branch actions, looked up by name (class GitBranchActionsDataKeys only exists in newer IDEs).
        private val AFFECTED_REPOSITORIES = DataKey.create<List<GitRepository>>("Git.Repositories")
        private val SELECTED_REPOSITORY = DataKey.create<GitRepository>("Git.Selected.Repository")
        private val BRANCHES = DataKey.create<List<GitBranch>>("Git.Branches")
        private val USE_CURRENT_BRANCH = DataKey.create<Boolean>("Git.Branches.UseCurrent")
        private val TREE_SELECTION = DataKey.create<Any>("GitBranchesTreeSelection")
    }
}
