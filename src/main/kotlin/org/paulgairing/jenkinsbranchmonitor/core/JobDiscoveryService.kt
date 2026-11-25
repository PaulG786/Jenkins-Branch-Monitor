package org.paulgairing.jenkinsbranchmonitor.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import org.paulgairing.jenkinsbranchmonitor.settings.PluginSettings
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves (repo, branch, remote URL) to a Jenkins job URL.
 *
 * Resolution order:
 *  1. manual mappings from the settings ("remote-url-regex => job-url-template"),
 *  2. name-based heuristic: multibranch job named like the repository
 *     (<server>/job/<repo>/job/<url-encoded-branch>).
 */
@Service
class JobDiscoveryService {

    private data class CacheEntry(val jobUrl: String?, val createdAt: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    fun invalidateCache() {
        cache.clear()
        jobIndex = null
    }

    fun resolveJobUrl(client: JenkinsClient, repoName: String, branch: String, remoteUrl: String?): String? {
        val key = "${client.baseUrl}|${remoteUrl ?: ""}|$repoName|$branch"
        val cached = cache[key]
        if (cached != null) {
            if (cached.jobUrl != null) return cached.jobUrl
            if (System.currentTimeMillis() - cached.createdAt < NEGATIVE_TTL_MS) return null
        }
        val resolved = doResolve(client, repoName, branch, remoteUrl)
        cache[key] = CacheEntry(resolved, System.currentTimeMillis())
        return resolved
    }

    /**
     * Runs the full resolution (bypassing the cache) and describes every step — for the settings diagnostics.
     * Read-only GETs only.
     */
    fun explain(client: JenkinsClient, repoName: String, branch: String, remoteUrl: String?): List<String> {
        val lines = ArrayList<String>()
        val result = doResolve(client, repoName, branch, remoteUrl) { lines.add(it) }
        lines.add("")
        lines.add(if (result != null) "Result: $result" else "Result: no matching Jenkins job found")
        return lines
    }

    private fun doResolve(
        client: JenkinsClient, repoName: String, branch: String, remoteUrl: String?,
        trace: ((String) -> Unit)? = null
    ): String? {
        fun exists(url: String): Boolean {
            val found = client.jobExists(url)
            trace?.invoke("  ${if (found) "✔" else "✘"} $url")
            return found
        }
        val mappings = parseMappings(PluginSettings.getInstance().manualMappings)
        trace?.invoke("1. Manual mappings (${mappings.size})")
        for ((pattern, template) in mappings) {
            val regex = runCatching { Regex(pattern) }.getOrNull()
            if (regex == null) {
                trace?.invoke("  invalid regex: $pattern")
                continue
            }
            if (remoteUrl != null && regex.containsMatchIn(remoteUrl)) {
                trace?.invoke("  '$pattern' matches the remote URL")
                val url = expandTemplate(template, client.baseUrl, repoName, branch)
                if (exists(url)) return url
            } else {
                trace?.invoke("  '$pattern' does not match the remote URL")
            }
        }
        // Branch jobs of multibranch pipelines may be named with the raw branch ("release/1.4",
        // single-encoded URL) or with an encoded name ("release%2F1.4", double-encoded URL).
        val branchSegments = linkedSetOf(
            encodeSegment(branch),
            encodeSegment(encodeSegment(branch))
        )
        // Folder candidates derived from the SCM project/org in the remote URL
        // (e.g. ssh://git@host:7999/team/repo.git -> "team"), tried in both cases.
        val projectKey = projectKeyFrom(remoteUrl)
        trace?.invoke("2. Name heuristic (job '$repoName', folder from remote URL: ${projectKey ?: "none"})")
        val folders = LinkedHashSet<String?>()
        folders.add(null) // job directly at top level
        projectKey?.let {
            folders.add(it)
            folders.add(it.uppercase())
            folders.add(it.lowercase())
        }
        for (folder in folders) {
            val prefix = if (folder == null) client.baseUrl
                         else "${client.baseUrl}/job/${encodeSegment(folder)}"
            for (branchSeg in branchSegments) {
                val candidate = "$prefix/job/${encodeSegment(repoName)}/job/$branchSeg"
                if (exists(candidate)) return candidate
            }
        }
        // Fallback: the folder name is not derivable from the remote (e.g. repo "team/app" lives in folder "TEAM-APPS") —
        // look up jobs named like the repository in the Jenkins folder tree (one cached GET).
        val byName = repoJobsByName(client, repoName)
        trace?.invoke("3. Folder tree ($INDEX_DEPTH levels): ${byName.size} job(s) named '$repoName'")
        for (repoJobUrl in byName) {
            for (branchSeg in branchSegments) {
                val candidate = "${repoJobUrl.trimEnd('/')}/job/$branchSeg"
                if (exists(candidate)) return candidate
            }
        }
        return null
    }

    private data class JobIndex(val byName: Map<String, List<String>>, val createdAt: Long)

    @Volatile
    private var jobIndex: JobIndex? = null

    /** URLs of jobs (up to [INDEX_DEPTH] folder levels deep) whose name equals [repoName], case-insensitive. */
    private fun repoJobsByName(client: JenkinsClient, repoName: String): List<String> {
        val index = jobIndex?.takeIf { System.currentTimeMillis() - it.createdAt < INDEX_TTL_MS }
            ?: buildJobIndex(client)?.also { jobIndex = it }
            ?: return emptyList()
        return index.byName[repoName.lowercase()].orEmpty()
    }

    private fun buildJobIndex(client: JenkinsClient): JobIndex? {
        // tree=jobs[name,url,jobs[name,url,jobs[name,url]]] — names/URLs only, no build data
        var tree = "name,url"
        repeat(INDEX_DEPTH - 1) { tree = "name,url,jobs[$tree]" }
        val query = "jobs[$tree]".replace("[", "%5B").replace("]", "%5D")
        val root = runCatching { client.getJson("${client.baseUrl}/api/json?tree=$query") }.getOrNull() ?: return null
        val byName = HashMap<String, MutableList<String>>()
        fun walk(obj: com.google.gson.JsonObject) {
            val jobs = obj.getAsJsonArray("jobs") ?: return
            for (el in jobs) {
                val job = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val name = job.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                val url = job.get("url")?.takeIf { it.isJsonPrimitive }?.asString
                if (name != null && url != null) byName.getOrPut(name.lowercase()) { mutableListOf() }.add(url)
                walk(job)
            }
        }
        walk(root)
        return JobIndex(byName, System.currentTimeMillis())
    }

    /** Second-to-last path segment of the remote URL, e.g. the Bitbucket project key or GitHub org. */
    private fun projectKeyFrom(remoteUrl: String?): String? {
        if (remoteUrl.isNullOrBlank()) return null
        val path = remoteUrl.substringAfter("://").substringAfter('@')
            .replace(':', '/')
            .trimEnd('/')
        val segments = path.split('/').filter { it.isNotBlank() && !it.all(Char::isDigit) }
        if (segments.size < 3) return null // host + project + repo required
        return segments[segments.size - 2].takeIf { it.isNotBlank() }
    }

    private fun parseMappings(raw: String): List<Pair<String, String>> =
        raw.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
            val idx = trimmed.indexOf("=>")
            if (idx <= 0) return@mapNotNull null
            trimmed.substring(0, idx).trim() to trimmed.substring(idx + 2).trim()
        }

    private fun expandTemplate(template: String, baseUrl: String, repoName: String, branch: String): String {
        val expanded = template
            .replace("{repo}", repoName)
            .replace("{branch}", branch)
            .replace("{branchEnc}", encodeSegment(branch))
        return if (expanded.startsWith("http")) expanded else "$baseUrl/${expanded.trimStart('/')}"
    }

    companion object {
        private const val NEGATIVE_TTL_MS = 120_000L
        private const val INDEX_TTL_MS = 600_000L
        private const val INDEX_DEPTH = 3

        fun getInstance(): JobDiscoveryService =
            ApplicationManager.getApplication().getService(JobDiscoveryService::class.java)

        fun encodeSegment(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }
}
