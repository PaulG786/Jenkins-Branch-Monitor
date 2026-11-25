package org.paulgairing.jenkinsbranchmonitor.git

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.roots.VcsRootDetector
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import org.paulgairing.jenkinsbranchmonitor.model.BranchSource
import org.paulgairing.jenkinsbranchmonitor.model.MonitoredBranch
import org.paulgairing.jenkinsbranchmonitor.settings.PluginSettings
import org.paulgairing.jenkinsbranchmonitor.settings.ProjectRepoSettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Knows which branches are checked out in the project.
 *
 * - Registered Git roots: git4idea [GitRepository] (events via GIT_REPO_CHANGE).
 * - Unmanaged roots (mapping `<None>` / unregistered): found via the IDE's [VcsRootDetector], branch and remote
 *   read with the IDE's Git executable ([GitCli]); branch switches are noticed via VFS events on their HEAD file.
 */
@Service(Service.Level.PROJECT)
class BranchTrackerService(private val project: Project) : Disposable {

    /** When a repo/branch combination was first seen checked out — newest checkouts sort to the top. */
    private val firstSeen = ConcurrentHashMap<String, Long>()

    @Volatile
    private var detectedRoots: Pair<Long, List<VirtualFile>> = 0L to emptyList()

    /** Remote URL per unmanaged root (rarely changes) — key: root path, value: (fetchedAt, url). */
    private val remoteCache = ConcurrentHashMap<String, Pair<Long, String?>>()

    /** Git dirs of unmanaged roots whose HEAD is watched (normalized path) → watch request. */
    private val watchedGitDirs = ConcurrentHashMap<String, LocalFileSystem.WatchRequest?>()
    private val headChanged = AtomicBoolean(false)

    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (watchedGitDirs.isEmpty()) return
                if (events.any { isWatchedHead(it.path) }) headChanged.set(true)
            }
        })
    }

    /** Monitored branches; repositories excluded in the project settings are dropped unless [includeExcluded]. */
    fun currentBranches(includeExcluded: Boolean = false): List<MonitoredBranch> {
        val all = collectBranches()
        if (includeExcluded) return all
        val repoSettings = ProjectRepoSettings.getInstance(project)
        return all.filterNot { repoSettings.isExcluded(it.repoName) }
    }

    private fun collectBranches(): List<MonitoredBranch> {
        val settings = PluginSettings.getInstance()
        val pushes = if (settings.trackRecentPushes) {
            project.getService(PushTrackerService::class.java)
                .recentlyPushed(settings.recentPushRetentionMin, settings.doneLingerMin)
        } else {
            emptyList()
        }
        val pushByKey = pushes.associateBy { "${it.repoName}|${it.branch}" }

        val repositories = GitRepositoryManager.getInstance(project).repositories
        val current = repositories.map { repo ->
            val repoName = repoNameOf(repo)
            val branch = repo.currentBranchName
            val key = "$repoName|$branch"
            firstSeen.putIfAbsent(key, System.currentTimeMillis())
            val push = pushByKey[key]
            MonitoredBranch(
                repoName = repoName,
                branch = branch,
                remoteUrl = remoteUrlOf(repo),
                recentlyPushed = push != null,
                pushedAt = push?.pushedAt ?: 0,
                pushDone = (push?.doneAt ?: 0L) > 0,
                rootPath = repo.root.presentableUrl
            )
        }

        val unmanaged = unmanagedBranches(settings, repositories.map { normalize(it.root.path) }.toSet())
            .filter { u -> current.none { it.repoName == u.repoName && it.branch == u.branch } }

        val currentKeys = (current + unmanaged).map { "${it.repoName}|${it.branch}" }.toSet()
        val fromPushes = pushes
            .filter { "${it.repoName}|${it.branch}" !in currentKeys }
            .map {
                MonitoredBranch(
                    repoName = it.repoName,
                    branch = it.branch,
                    remoteUrl = it.remoteUrl,
                    recentlyPushed = true,
                    pushedAt = it.pushedAt,
                    pushDone = it.doneAt > 0
                )
            }

        // Active pushes (not finished) first, newest activity on top within each group.
        return (current + unmanaged + fromPushes).sortedWith(
            compareBy(
                { if (it.pushedAt > 0 && !it.pushDone) 0 else 1 },
                { -maxOf(it.pushedAt, firstSeen["${it.repoName}|${it.branch}"] ?: 0L) }
            )
        )
    }

    /** True (once) when the HEAD of an unmanaged root changed since the last call — branch switch outside git4idea. */
    fun unmanagedHeadsChanged(): Boolean = headChanged.getAndSet(false)

    private fun unmanagedBranches(settings: PluginSettings, registered: Set<String>): List<MonitoredBranch> {
        if (!settings.watchVcsDisabledRoots && !settings.watchUnregisteredRoots) {
            updateWatches(emptySet())
            return emptyList()
        }
        val detected = detectedGitRoots()
        val noneMapped = noneMappedDirs()
        val allRootPaths = (detected.map { normalize(it.path) } + registered).toSet()

        val roots = LinkedHashMap<VirtualFile, BranchSource>()
        if (settings.watchVcsDisabledRoots) {
            // <None> mappings the detector did not report (e.g. outside content roots)
            val detectedPaths = detected.map { normalize(it.path) }.toSet()
            noneMapped.filter { it !in detectedPaths && it !in registered }
                .mapNotNull { LocalFileSystem.getInstance().findFileByPath(it) }
                .forEach { roots[it] = BranchSource.VCS_DISABLED }
        }
        for (root in detected) {
            val path = normalize(root.path)
            if (path in registered) continue
            when {
                path in noneMapped -> if (settings.watchVcsDisabledRoots) roots[root] = BranchSource.VCS_DISABLED
                settings.watchUnregisteredRoots && isRelevantUnregistered(root, path, allRootPaths) ->
                    roots[root] = BranchSource.UNREGISTERED
            }
        }

        val gitDirs = HashSet<String>()
        val result = roots.mapNotNull { (root, source) ->
            val head = GitCli.head(project, root) ?: return@mapNotNull null
            gitDirs.add(normalize(head.gitDir))
            val remoteUrl = cachedRemoteUrl(root)
            val repoName = repoNameFrom(remoteUrl) ?: root.name
            firstSeen.putIfAbsent("$repoName|${head.branch}", System.currentTimeMillis())
            MonitoredBranch(
                repoName = repoName, branch = head.branch, remoteUrl = remoteUrl, source = source,
                rootPath = root.presentableUrl
            )
        }.distinctBy { "${it.repoName}|${it.branch}" } // several clones of the same repo/branch → one Jenkins job
        updateWatches(gitDirs)
        return result
    }

    /**
     * An unregistered root is shown only if it is part of the project (in content, or containing a content root),
     * not excluded/ignored, and not nested inside another Git root (vendored repos, build output like `target/…`).
     */
    private fun isRelevantUnregistered(root: VirtualFile, path: String, allRootPaths: Set<String>): Boolean {
        if (allRootPaths.any { it != path && path.startsWith("$it/") }) return false
        return runReadAction {
            if (project.isDisposed) return@runReadAction false
            val index = ProjectFileIndex.getInstance(project)
            if (index.isExcluded(root) || index.isUnderIgnored(root)) return@runReadAction false
            index.isInContent(root) ||
                ProjectRootManager.getInstance(project).contentRoots.any { VfsUtilCore.isAncestor(root, it, false) }
        }
    }

    /** Git roots detected by the IDE (same mechanism as the "unregistered VCS root" hint), cached for 2 minutes. */
    private fun detectedGitRoots(): List<VirtualFile> {
        val (detectedAt, cached) = detectedRoots
        if (System.currentTimeMillis() - detectedAt < DETECT_CACHE_MS) return cached
        val result = runCatching {
            VcsRootDetector.getInstance(project).detect()
                .filter { it.vcs?.name == GIT_VCS_NAME }
                .mapNotNull { it.path }
                .distinctBy { normalize(it.path) }
        }.onFailure {
            if (it is ProcessCanceledException) throw it
            thisLogger().warn("VCS root detection failed", it)
        }.getOrDefault(cached)
        detectedRoots = System.currentTimeMillis() to result
        return result
    }

    /** Directory mappings with VCS `<None>`. */
    private fun noneMappedDirs(): Set<String> =
        ProjectLevelVcsManager.getInstance(project).directoryMappings
            .filter { it.vcs.isNullOrEmpty() }
            .mapNotNull { mapping ->
                val dir = if (mapping.isDefaultMapping) project.basePath else mapping.directory
                dir?.takeIf { it.isNotBlank() }?.let { normalize(it) }
            }
            .toSet()

    private fun cachedRemoteUrl(root: VirtualFile): String? {
        val key = normalize(root.path)
        remoteCache[key]?.let { (at, url) -> if (System.currentTimeMillis() - at < REMOTE_CACHE_MS) return url }
        val url = GitCli.remoteUrl(project, root)
        remoteCache[key] = System.currentTimeMillis() to url
        return url
    }

    // ---- HEAD watching via VFS (no polling of .git files) ----

    private fun isWatchedHead(path: String): Boolean {
        val p = normalize(path)
        return p.endsWith("/head", ignoreCase = true) && watchedGitDirs.containsKey(p.substringBeforeLast('/'))
    }

    private fun updateWatches(gitDirs: Set<String>) {
        val fs = LocalFileSystem.getInstance()
        (watchedGitDirs.keys - gitDirs).forEach { dir -> watchedGitDirs.remove(dir)?.let { fs.removeWatchedRoot(it) } }
        for (dir in gitDirs - watchedGitDirs.keys) {
            watchedGitDirs[dir] = fs.addRootToWatch(dir, false)
            fs.refreshAndFindFileByPath("$dir/HEAD") // load into VFS so changes produce events
        }
    }

    override fun dispose() {
        val fs = LocalFileSystem.getInstance()
        watchedGitDirs.values.filterNotNull().let { if (it.isNotEmpty()) fs.removeWatchedRoots(it) }
        watchedGitDirs.clear()
    }

    companion object {
        private const val GIT_VCS_NAME = "Git"
        private const val DETECT_CACHE_MS = 120_000L
        private const val REMOTE_CACHE_MS = 600_000L

        /** System-independent, trailing-slash-free and (on Windows) case-insensitive path key. */
        private fun normalize(path: String): String {
            val p = FileUtil.toSystemIndependentName(path).trimEnd('/')
            return if (SystemInfo.isFileSystemCaseSensitive) p else p.lowercase()
        }

        fun remoteUrlOf(repo: GitRepository): String? {
            val remote = repo.remotes.firstOrNull { it.name == "origin" } ?: repo.remotes.firstOrNull()
            return remote?.firstUrl
        }

        fun repoNameOf(repo: GitRepository): String = repoNameFrom(remoteUrlOf(repo)) ?: repo.root.name

        /** Last path segment of a remote URL — works for https, ssh (`host:path`) and local/UNC paths (`\\srv\repo`). */
        fun repoNameFrom(remoteUrl: String?): String? {
            if (remoteUrl.isNullOrBlank()) return null
            val last = remoteUrl.trim().trimEnd('/', '\\')
                .substringAfterLast('/').substringAfterLast('\\').substringAfterLast(':')
            return last.removeSuffix(".git").takeIf { it.isNotBlank() }
        }
    }
}
