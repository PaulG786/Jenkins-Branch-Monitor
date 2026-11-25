package org.paulg786.jenkinsbranchmonitor.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.util.net.ssl.CertificateManager
import org.paulg786.jenkinsbranchmonitor.model.BuildInfo
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

/**
 * Strictly read-only Jenkins REST client: only HTTP GET is ever issued.
 * This plugin must never trigger, abort or modify anything in Jenkins.
 */
class JenkinsClient(baseUrl: String, private val username: String, private val apiToken: String) {

    val baseUrl: String = baseUrl.trimEnd('/')

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .sslContext(CertificateManager.getInstance().sslContext)
        .build()

    class HttpStatusException(statusCode: Int, url: String) :
        RuntimeException("HTTP $statusCode for $url")

    /** A piece of a build log. [nextOffset] is the byte offset to continue from (Jenkins `X-Text-Size`). */
    data class LogChunk(
        val text: String,
        val nextOffset: Long,
        val moreData: Boolean,
        val truncated: Boolean = false
    )

    private fun newRequest(url: String, timeout: Duration): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .GET()
        if (username.isNotBlank()) {
            val auth = Base64.getEncoder()
                .encodeToString("$username:$apiToken".toByteArray(StandardCharsets.UTF_8))
            builder.header("Authorization", "Basic $auth")
        }
        return builder
    }

    private fun get(url: String): String? {
        val response = http.send(newRequest(url, Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString())
        return when (response.statusCode()) {
            in 200..299 -> response.body()
            404 -> null
            else -> throw HttpStatusException(response.statusCode(), url)
        }
    }

    private fun progressiveUrl(buildUrl: String, start: Long) =
        "${buildUrl.trimEnd('/')}/logText/progressiveText?start=$start"

    /** Reads the log from [start] on (GET `logText/progressiveText`). */
    fun progressiveText(buildUrl: String, start: Long): LogChunk {
        val url = progressiveUrl(buildUrl, start)
        val response = http.send(
            newRequest(url, Duration.ofMinutes(2)).build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        )
        if (response.statusCode() !in 200..299) throw HttpStatusException(response.statusCode(), url)
        val body = response.body()
        val size = response.headers().firstValue("X-Text-Size").orElse(null)?.toLongOrNull()
            ?: (start + body.toByteArray(StandardCharsets.UTF_8).size)
        val more = response.headers().firstValue("X-More-Data").orElse("") == "true"
        return LogChunk(body, size, more)
    }

    /**
     * Loads at most [maxBytes] from the end of a build log. For large logs the first
     * transfer is aborted as soon as the size header arrived and only the tail is fetched.
     */
    fun logTail(buildUrl: String, maxBytes: Long): LogChunk {
        val url = progressiveUrl(buildUrl, 0)
        val response = http.send(
            newRequest(url, Duration.ofMinutes(5)).build(),
            HttpResponse.BodyHandlers.ofInputStream()
        )
        val size: Long?
        val more: Boolean
        val bytes: ByteArray
        var truncated = false
        response.body().use { input ->
            if (response.statusCode() !in 200..299) throw HttpStatusException(response.statusCode(), url)
            size = response.headers().firstValue("X-Text-Size").orElse(null)?.toLongOrNull()
            more = response.headers().firstValue("X-More-Data").orElse("") == "true"
            if (size != null && size > maxBytes) {
                bytes = ByteArray(0)
            } else {
                val (tail, dropped) = readTail(input, maxBytes)
                bytes = tail
                truncated = dropped
            }
        }
        if (size != null && size > maxBytes) {
            val chunk = progressiveText(buildUrl, size - maxBytes)
            return chunk.copy(text = chunk.text.substringAfter('\n'), truncated = true)
        }
        var text = String(bytes, StandardCharsets.UTF_8)
        if (truncated) text = text.substringAfter('\n')
        return LogChunk(text, size ?: bytes.size.toLong(), more, truncated)
    }

    private fun readTail(input: java.io.InputStream, maxBytes: Long): Pair<ByteArray, Boolean> {
        val chunks = ArrayDeque<ByteArray>()
        var total = 0L
        var dropped = false
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            chunks.addLast(buffer.copyOf(n))
            total += n
            while (chunks.size > 1 && total - chunks.first().size >= maxBytes) {
                total -= chunks.removeFirst().size
                dropped = true
            }
        }
        val all = ByteArray(total.toInt())
        var pos = 0
        for (c in chunks) {
            System.arraycopy(c, 0, all, pos, c.size)
            pos += c.size
        }
        if (total > maxBytes) {
            return all.copyOfRange((total - maxBytes).toInt(), all.size) to true
        }
        return all to dropped
    }

    fun getJson(url: String): JsonObject? {
        val body = get(url) ?: return null
        return JsonParser.parseString(body).asJsonObject
    }

    fun jobExists(jobUrl: String): Boolean =
        getJson("${jobUrl.trimEnd('/')}/api/json?tree=name") != null

    fun lastBuild(jobUrl: String): BuildInfo? {
        val json = getJson(
            "${jobUrl.trimEnd('/')}/api/json?tree=lastBuild[number,building,result,timestamp,duration,estimatedDuration,url]"
        ) ?: return null
        val lb = json.get("lastBuild")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return BuildInfo(
            number = lb.get("number")?.asInt ?: 0,
            building = lb.get("building")?.asBoolean ?: false,
            result = lb.get("result")?.takeIf { !it.isJsonNull }?.asString,
            timestamp = lb.get("timestamp")?.asLong ?: 0L,
            durationMs = lb.get("duration")?.asLong ?: 0L,
            estimatedDurationMs = lb.get("estimatedDuration")?.asLong ?: 0L,
            url = lb.get("url")?.asString ?: jobUrl
        )
    }

    /** Throws on connection/auth problems, returns normally when the server answers. */
    fun testConnection() {
        getJson("$baseUrl/api/json?tree=nodeName")
            ?: throw IllegalStateException("Server answered 404 — is this a Jenkins root URL?")
    }

    companion object {
        /** Client for the configured server, or null when no server is configured. */
        fun fromSettings(): JenkinsClient? {
            val settings = org.paulg786.jenkinsbranchmonitor.settings.PluginSettings.getInstance()
            if (settings.serverUrl.isBlank()) return null
            val token = org.paulg786.jenkinsbranchmonitor.settings.CredentialsStore.getToken(settings.serverUrl) ?: ""
            return JenkinsClient(settings.serverUrl, settings.username, token)
        }

        private val CONSOLE_NOTE = Regex("\u001B\\[8mha:[^\u001B]*\u001B\\[0m")
        private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

        /** Removes Jenkins console notes and ANSI color codes, normalizes line endings. */
        fun cleanLog(text: String): String =
            ANSI.replace(CONSOLE_NOTE.replace(text, ""), "")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
    }
}
