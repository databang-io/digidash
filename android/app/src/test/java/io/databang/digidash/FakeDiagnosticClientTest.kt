package io.databang.digidash

import io.databang.digidash.core.diagnostics.ConnectionConfig
import io.databang.digidash.core.diagnostics.DiagnosticError
import io.databang.digidash.core.diagnostics.asDiagnosticError
import io.databang.digidash.core.diagnostics.fake.FakeDiagnosticClient
import io.databang.digidash.core.diagnostics.fake.FakeScenario
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeDiagnosticClientTest {

    private fun client(scenario: FakeScenario = FakeScenario.NORMAL) =
        FakeDiagnosticClient(scenario = scenario, operationDelayMillis = 0)

    @Test
    fun `connect and identify returns normalized digifant identity`() = runTest {
        val client = client()
        assertTrue(client.connect(ConnectionConfig()).isSuccess)
        val identity = client.identifyEcu().getOrThrow()
        assertEquals("03790602400", identity.partNumberNormalized)
        assertEquals("DIGIFANT 2E (DEMO)", identity.component)
    }

    @Test
    fun `read group 1 replays sample fields`() = runTest {
        val client = client()
        client.connect(ConnectionConfig())
        val block = client.readMeasuringBlock(1).getOrThrow()
        assertEquals(4, block.fields.size)
        assertEquals("920", block.fields[0].raw)
        assertEquals("2.6", block.fields[3].raw)
    }

    @Test
    fun `read group 2 replays sample fields`() = runTest {
        val client = client()
        client.connect(ConnectionConfig())
        val block = client.readMeasuringBlock(2).getOrThrow()
        assertEquals("24", block.fields[1].raw)
    }

    @Test
    fun `unknown group is unsupported`() = runTest {
        val client = client()
        client.connect(ConnectionConfig())
        val error = client.readMeasuringBlock(42).exceptionOrNull()!!.asDiagnosticError()
        assertEquals(DiagnosticError.UnsupportedFunction, error)
    }

    @Test
    fun `dongle not found scenario fails connect`() = runTest {
        val client = client(FakeScenario.DONGLE_NOT_FOUND)
        val error = client.connect(ConnectionConfig()).exceptionOrNull()!!.asDiagnosticError()
        assertEquals(DiagnosticError.DongleNotFound, error)
    }

    @Test
    fun `timeout scenario times out reads`() = runTest {
        val client = client()
        client.connect(ConnectionConfig())
        client.scenario = FakeScenario.TIMEOUT
        val error = client.readMeasuringBlock(1).exceptionOrNull()!!.asDiagnosticError()
        assertTrue(error is DiagnosticError.Timeout)
    }

    @Test
    fun `reads without connect fail`() = runTest {
        val error = client().readMeasuringBlock(1).exceptionOrNull()!!.asDiagnosticError()
        assertEquals(DiagnosticError.EcuNoResponse, error)
    }

    @Test
    fun `basic settings can be entered and exited in demo mode`() = runTest {
        val client = client()
        client.connect(ConnectionConfig())
        assertTrue(client.enterBasicSettings(0).isSuccess)
        assertTrue(client.basicSettingsActive)
        assertTrue(client.exitBasicSettings().isSuccess)
        assertTrue(!client.basicSettingsActive)
    }

    @Test
    fun `group 11 shows advance near 6 deg in basic settings`() = runTest {
        val client = client()
        client.connect(ConnectionConfig())
        val idle = client.readMeasuringBlock(11).getOrThrow()
        assertEquals("12", idle.fields[1].raw)
        client.enterBasicSettings(0)
        val adjust = client.readMeasuringBlock(11).getOrThrow()
        assertEquals("6", adjust.fields[1].raw)
    }
}
