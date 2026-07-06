package io.databang.digidash.core.ecumodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HTTPS text fetcher so tests can substitute a fake. */
fun interface HttpFetcher {
    suspend fun fetchText(url: String): String
}

class UrlConnectionFetcher(
    private val timeoutMillis: Int = 10_000,
) : HttpFetcher {
    override suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.requestMethod = "GET"
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw RemoteEcuModelException("HTTP $code for $url")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

class RemoteEcuModelException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Reads ECU model files from a public git repository exposed over raw HTTPS,
 * e.g. `https://raw.githubusercontent.com/<user>/<repo>/main/ecu_models`.
 * GitLab (`.../-/raw/main/ecu_models`) and any static file host work the same way.
 *
 * Every successful fetch is written to [cacheDir] so previously loaded models
 * stay available offline; on fetch failure the cached copy is returned.
 */
class RemoteEcuModelSource(
    baseUrl: String,
    private val fetcher: HttpFetcher = UrlConnectionFetcher(),
    private val cacheDir: File? = null,
) : EcuModelSource {

    private val baseUrl = baseUrl.trimEnd('/')

    override suspend fun read(relativePath: String): String {
        val url = "$baseUrl/$relativePath"
        return try {
            fetcher.fetchText(url).also { writeCache(relativePath, it) }
        } catch (e: Exception) {
            readCache(relativePath)
                ?: throw RemoteEcuModelException("Cannot fetch $url and no cached copy", e)
        }
    }

    private fun writeCache(relativePath: String, content: String) {
        val dir = cacheDir ?: return
        runCatching {
            val file = File(dir, relativePath)
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
    }

    private fun readCache(relativePath: String): String? {
        val dir = cacheDir ?: return null
        val file = File(dir, relativePath)
        return if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    }
}
