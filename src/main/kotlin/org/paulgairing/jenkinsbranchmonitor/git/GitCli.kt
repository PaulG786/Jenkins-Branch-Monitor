package org.paulgairing.jenkinsbranchmonitor.git

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler

/**
 * Branch/remote of Git roots the IDE does not manage (VCS mapping `<None>` or unregistered), where no
 * git4idea `GitRepository` exists. Uses the IDE's configured Git executable via git4idea — read-only
 * commands only (`rev-parse`, `config --get-regexp`). Must not run on the EDT.
 */
object GitCli {

    data class HeadInfo(val gitDir: String, val branch: String?)

    /** `git rev-parse --absolute-git-dir --abbrev-ref HEAD` — one process for git dir + current branch. */
    fun head(project: Project, root: VirtualFile): HeadInfo? {
        val lines = run(project, root, GitCommand.REV_PARSE, "--absolute-git-dir", "--abbrev-ref", "HEAD") ?: return null
        if (lines.size < 2) return null
        val branch = lines[1].trim().takeIf { it.isNotEmpty() && it != "HEAD" } // "HEAD" = detached
        return HeadInfo(FileUtil.toSystemIndependentName(lines[0].trim()), branch)
    }

    /** URL of `origin`, otherwise of the first remote. */
    fun remoteUrl(project: Project, root: VirtualFile): String? {
        val lines = run(project, root, GitCommand.CONFIG, "--get-regexp", "^remote\\..*\\.url$") ?: return null
        val urls = lines.mapNotNull { line ->
            val key = line.substringBefore(' ')
            val url = line.substringAfter(' ', "").trim()
            if (url.isEmpty()) null else key.removePrefix("remote.").removeSuffix(".url") to url
        }
        return (urls.firstOrNull { it.first == "origin" } ?: urls.firstOrNull())?.second
    }

    private fun run(project: Project, root: VirtualFile, command: GitCommand, vararg params: String): List<String>? {
        val handler = GitLineHandler(project, root, command).apply {
            addParameters(*params)
            setSilent(true)
            setEnableInteractiveCallbacks(false)
        }
        val result = runCatching { Git.getInstance().runCommand(handler) }
            .onFailure { thisLogger().debug("git ${command.name()} failed in ${root.path}", it) }
            .getOrNull() ?: return null
        return if (result.success()) result.output else null
    }
}
