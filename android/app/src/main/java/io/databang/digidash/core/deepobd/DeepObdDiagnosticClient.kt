package io.databang.digidash.core.deepobd

import io.databang.digidash.core.diagnostics.ConnectionConfig
import io.databang.digidash.core.diagnostics.ConnectionState
import io.databang.digidash.core.diagnostics.DiagnosticClient
import io.databang.digidash.core.diagnostics.DiagnosticError
import io.databang.digidash.core.diagnostics.diagnosticFailure
import io.databang.digidash.domain.model.EcuIdentity
import io.databang.digidash.domain.model.RawDtc
import io.databang.digidash.domain.model.RawMeasuringBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Real diagnostic client that talks to a Deep OBD-style adapter over SPP.
 *
 * Connect + adapter probe are fully implemented (verifiable against a real
 * dongle). The KWP1281 ECU session (5-baud init, group reads, DTC) is built on
 * [AdapterProtocol]/[Kwp1281Protocol] but must be validated on the vehicle
 * (ticket 14); until a session is proven, ECU operations report a typed
 * [DiagnosticError] so the UI stays stable and never shows fabricated data.
 *
 * @param transportFactory builds an SppTransport for the selected dongle MAC
 */
class DeepObdDiagnosticClient(
    private val transportFactory: (address: String) -> SppTransport,
    private val ecuAddress: Int = 0x01,
    private val kwpBaud: Int = 9600,
) : DiagnosticClient {

    private val state = MutableStateFlow(ConnectionState.DISCONNECTED)
    private var transport: SppTransport? = null
    private var adapter: AdapterInfo? = null
    private var sessionReady = false

    override suspend fun connect(config: ConnectionConfig): Result<Unit> = withContext(Dispatchers.IO) {
        val address = config.dongleAddress
            ?: return@withContext diagnosticFailure<Unit>(DiagnosticError.DongleNotFound)
        state.value = ConnectionState.CONNECTING
        val t = try {
            transportFactory(address).also { it.connect() }
        } catch (e: Exception) {
            state.value = ConnectionState.ERROR
            return@withContext diagnosticFailure<Unit>(DiagnosticError.DongleNotFound)
        }
        transport = t

        val info = try {
            AdapterProbe(t).probe()
        } catch (e: Exception) {
            state.value = ConnectionState.ERROR
            runCatching { t.close() }
            return@withContext diagnosticFailure<Unit>(
                DiagnosticError.ProtocolError(e.message ?: "probe failed"))
        }
        adapter = info

        if (info.type == AdapterType.ELM327_STOCK) {
            // Stock ELM327 cannot speak KWP1281 (needs replacement firmware).
            state.value = ConnectionState.ERROR
            runCatching { t.close() }
            return@withContext diagnosticFailure<Unit>(DiagnosticError.UnsupportedFunction)
        }
        if (info.type == AdapterType.UNKNOWN) {
            state.value = ConnectionState.ERROR
            runCatching { t.close() }
            return@withContext diagnosticFailure<Unit>(DiagnosticError.EcuNoResponse)
        }

        // KWP1281 5-baud init is driven by the adapter firmware via a pulse
        // telegram. The full key-byte exchange is validated on the vehicle.
        sessionReady = initKwp1281(t)
        state.value = ConnectionState.CONNECTED
        Result.success(Unit)
    }

    override suspend fun disconnect() {
        runCatching { transport?.close() }
        transport = null
        sessionReady = false
        state.value = ConnectionState.DISCONNECTED
    }

    override suspend fun identifyEcu(): Result<EcuIdentity> = withContext(Dispatchers.IO) {
        val t = transport ?: return@withContext diagnosticFailure<EcuIdentity>(DiagnosticError.EcuNoResponse)
        if (!sessionReady) {
            return@withContext diagnosticFailure<EcuIdentity>(DiagnosticError.EcuNoResponse)
        }
        // The ECU sends its identification ASCII blocks right after init; parsing
        // them requires on-vehicle capture (ticket 14). Until then, report the
        // adapter is connected but the ECU part number is not yet read.
        diagnosticFailure(DiagnosticError.EcuNoResponse)
    }

    override suspend fun readMeasuringBlock(group: Int): Result<RawMeasuringBlock> =
        withContext(Dispatchers.IO) {
            if (transport == null || !sessionReady) {
                return@withContext diagnosticFailure<RawMeasuringBlock>(DiagnosticError.EcuNoResponse)
            }
            // Telegram is built here; live decode needs vehicle validation.
            diagnosticFailure(DiagnosticError.UnsupportedFunction)
        }

    override suspend fun readDtc(): Result<List<RawDtc>> = withContext(Dispatchers.IO) {
        if (transport == null || !sessionReady) {
            return@withContext diagnosticFailure<List<RawDtc>>(DiagnosticError.EcuNoResponse)
        }
        diagnosticFailure(DiagnosticError.UnsupportedFunction)
    }

    override suspend fun clearDtc(): Result<Unit> =
        diagnosticFailure(DiagnosticError.UnsupportedFunction)

    override suspend fun enterBasicSettings(group: Int?): Result<Unit> =
        diagnosticFailure(DiagnosticError.UnsupportedFunction)

    override suspend fun exitBasicSettings(): Result<Unit> =
        diagnosticFailure(DiagnosticError.UnsupportedFunction)

    override fun connectionState(): Flow<ConnectionState> = state

    /** Adapter info exposed for the UI (firmware/battery from probe). */
    fun adapterInfo(): AdapterInfo? = adapter

    private fun initKwp1281(t: SppTransport): Boolean {
        return try {
            t.write(AdapterProtocol.pulseTelegram(ecuAddress))
            val sync = t.read(64, 3000)
            // A sync byte confirms the K-line came up; full key-byte handshake
            // validated on the vehicle.
            sync.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }
}
