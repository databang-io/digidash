package io.databang.digidash.core.ecumodel

import io.databang.digidash.domain.model.EcuIdentity
import kotlinx.serialization.json.Json

/**
 * A place ECU model JSON files can be read from: bundled assets, a local cache,
 * or a public git repository served over raw HTTPS.
 * Paths are relative to the `ecu_models` root, e.g. `index.json`, `vw/037906024AG.json`.
 */
fun interface EcuModelSource {
    suspend fun read(relativePath: String): String
}

interface EcuModelRepository {
    suspend fun loadIndex(): EcuModelIndex
    suspend fun findByPartNumber(partNumber: String): EcuModel?
    suspend fun loadModel(file: String): EcuModel
}

/**
 * Loads ECU models from an ordered list of sources: the first source that returns
 * a file wins. Typical order is [remote git repo (if configured), bundled assets],
 * so a community model repository can override the shipped bootstrap models while
 * the app keeps working offline.
 */
class DefaultEcuModelRepository(
    private val sources: List<EcuModelSource>,
) : EcuModelRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun loadIndex(): EcuModelIndex =
        json.decodeFromString(readFirst("index.json"))

    override suspend fun findByPartNumber(partNumber: String): EcuModel? {
        val normalized = EcuIdentity.normalizePartNumber(partNumber)
        val entry = loadIndex().models.find {
            EcuIdentity.normalizePartNumber(it.ecuPartNumber) == normalized
        } ?: return null
        return loadModel(entry.file)
    }

    override suspend fun loadModel(file: String): EcuModel =
        json.decodeFromString(readFirst(file))

    private suspend fun readFirst(relativePath: String): String {
        var lastError: Exception? = null
        for (source in sources) {
            try {
                return source.read(relativePath)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw EcuModelNotFoundException(relativePath, lastError)
    }
}

class EcuModelNotFoundException(path: String, cause: Throwable?) :
    Exception("ECU model file not found in any source: $path", cause)
