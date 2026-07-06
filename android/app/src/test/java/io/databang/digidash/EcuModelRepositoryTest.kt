package io.databang.digidash

import io.databang.digidash.core.ecumodel.DefaultEcuModelRepository
import io.databang.digidash.core.ecumodel.EcuModelNotFoundException
import io.databang.digidash.core.ecumodel.EcuModelSource
import io.databang.digidash.core.ecumodel.RemoteEcuModelSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

/** Reads the real bundled models from the repo checkout. */
private class DiskSource(private val root: File) : EcuModelSource {
    override suspend fun read(relativePath: String): String {
        val file = File(root, relativePath)
        if (!file.isFile) throw FileNotFoundException(relativePath)
        return file.readText()
    }
}

private fun bundledModelsRoot(): File = File("src/main/assets/ecu_models")

class EcuModelRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val repository = DefaultEcuModelRepository(listOf(DiskSource(bundledModelsRoot())))

    @Test
    fun `index lists the digifant bootstrap model`() = runTest {
        val index = repository.loadIndex()
        assertEquals(1, index.version)
        assertEquals("037906024AG", index.models.single().ecuPartNumber)
    }

    @Test
    fun `raw part number with spaces resolves to model`() = runTest {
        val model = repository.findByPartNumber("037 906 024 AG")
        assertNotNull(model)
        assertEquals("VW Digifant 2E - 037 906 024 AG", model!!.displayName)
        assertEquals("KWP1281", model.protocol)
    }

    @Test
    fun `unknown part number returns null`() = runTest {
        assertNull(repository.findByPartNumber("8D0 907 558 A"))
    }

    @Test
    fun `trip card fields are ordered`() = runTest {
        val model = repository.findByPartNumber("037906024AG")!!
        val keys = model.tripCardFields().map { it.second.key }
        assertEquals(
            listOf(
                "rpm", "coolant_temp", "battery_voltage", "idle_state",
                "injection_time", "lambda_state", "ignition_advance",
            ),
            keys,
        )
    }

    @Test
    fun `first source wins and later sources are fallback`() = runTest {
        val remoteIndex = """{"version": 2, "models": []}"""
        val remote = EcuModelSource { path ->
            if (path == "index.json") remoteIndex else throw FileNotFoundException(path)
        }
        val repo = DefaultEcuModelRepository(listOf(remote, DiskSource(bundledModelsRoot())))
        assertEquals(2, repo.loadIndex().version)
        // Model file missing remotely -> served by the bundled fallback.
        assertNotNull(repo.loadModel("vw/037906024AG.json"))
    }

    @Test
    fun `missing everywhere throws EcuModelNotFound`() {
        val repo = DefaultEcuModelRepository(emptyList())
        assertThrows(EcuModelNotFoundException::class.java) {
            kotlinx.coroutines.runBlocking { repo.loadIndex() }
        }
    }

    @Test
    fun `remote source caches fetches and serves cache when offline`() = runTest {
        val cacheDir = tmp.newFolder("cache")
        var online = true
        val source = RemoteEcuModelSource(
            baseUrl = "https://example.test/ecu_models/",
            fetcher = { url ->
                if (!online) throw java.io.IOException("offline")
                """{"version": 7, "models": [], "fetched_from": "$url"}"""
            },
            cacheDir = cacheDir,
        )
        val first = source.read("index.json")
        assertEquals(true, first.contains("https://example.test/ecu_models/index.json"))

        online = false
        val cached = source.read("index.json")
        assertEquals(first, cached)
    }
}
