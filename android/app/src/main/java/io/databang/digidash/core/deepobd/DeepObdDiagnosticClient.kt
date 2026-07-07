package io.databang.digidash.core.deepobd

import io.databang.digidash.core.diagnostics.ConnectionConfig
import io.databang.digidash.core.diagnostics.ConnectionState
import io.databang.digidash.core.diagnostics.DiagnosticClient
import io.databang.digidash.core.diagnostics.DiagnosticError
import io.databang.digidash.core.diagnostics.asDiagnosticError
import io.databang.digidash.core.diagnostics.diagnosticFailure
import io.databang.digidash.domain.model.EcuIdentity
import io.databang.digidash.domain.model.RawDtc
import io.databang.digidash.domain.model.RawMeasuringBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Real diagnostic client that talks to a Deep OBD-style adapter over SPP and
 * runs a live KWP1281 session ([Kwp1281Session]). Built from
 * docs/DeepOBD-Observed-API.md; expected to need on-vehicle tuning (ticket 14),
 * which is why [readOnly] can block writes and a raw-traffic capture can be
 * enabled via [transportFactory]. Never throws into the caller — every path
 * returns a typed [DiagnosticError].
 *
 * @param transportFactory builds an SppTransport for the selected dongle MAC
 *   (optionally wrapped in [LoggingSppTransport] for a byte capture)
 * @param readOnly when true, clear-DTC and Basic Settings are refused (safe mode)
 */
class DeepObdDiagnosticClient(
    private val transportFactory: (address: String) -> SppTransport,
    private val ecuAddress: Int = 0x01,
    private val kwpBaud: Int = 9600,
    var readOnly: Boolean = false,
) : DiagnosticClient {

    private val state = MutableStateFlow(ConnectionState.DISCONNECTED)
    private var transport: SppTransport? = null
    private var adapter: AdapterInfo? = null
    private var session: Kwp1281Session? = null
    private var identity: EcuIdentity? = null

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
            state.value = ConnectionState.ERROR
            runCatching { t.close() }
            return@withContext diagnosticFailure<Unit>(DiagnosticError.UnsupportedFunction)
        }
        if (info.type == AdapterType.UNKNOWN) {
            state.value = ConnectionState.ERROR
            runCatching { t.close() }
            return@withContext diagnosticFailure<Unit>(DiagnosticError.EcuNoResponse)
        }

        // Live KWP1281 init: 5-baud wake (firmware) + pump the ECU ID blocks.
        val s = Kwp1281Session(t, baud = kwpBaud, ecuAddress = ecuAddress)
        val idResult = s.connect()
        if (idResult.isFailure) {
            state.value = ConnectionState.ERROR
            return@withContext diagnosticFailure<Unit>(
                idResult.exceptionOrNull()?.let { DiagnosticError.ProtocolError(it.message ?: "init failed") }
                    ?: DiagnosticError.EcuNoResponse)
        }
        session = s
        identity = parseIdentity(idResult.getOrDefault(emptyList()), info)
        state.value = ConnectionState.CONNECTED
        Result.success(Unit)
    }

    override suspend fun disconnect() {
        runCatching { transport?.close() }
        transport = null
        session = null
        identity = null
        state.value = ConnectionState.DISCONNECTED
    }

    override suspend fun identifyEcu(): Result<EcuIdentity> = withContext(Dispatchers.IO) {
        identity?.let { Result.success(it) }
            ?: diagnosticFailure(DiagnosticError.EcuNoResponse)
    }

    override suspend fun readMeasuringBlock(group: Int): Result<RawMeasuringBlock> =
        withContext(Dispatchers.IO) {
            val s = session ?: return@withContext diagnosticFailure<RawMeasuringBlock>(DiagnosticError.EcuNoResponse)
            s.readGroup(group).recover1()
        }

    override suspend fun readDtc(): Result<List<RawDtc>> = withContext(Dispatchers.IO) {
        val s = session ?: return@withContext diagnosticFailure<List<RawDtc>>(DiagnosticError.EcuNoResponse)
        s.readDtc().recover1()
    }

    override suspend fun clearDtc(): Result<Unit> = withContext(Dispatchers.IO) {
        if (readOnly) return@withContext diagnosticFailure<Unit>(DiagnosticError.UnsupportedFunction)
        val s = session ?: return@withContext diagnosticFailure<Unit>(DiagnosticError.EcuNoResponse)
        s.clearDtc().recover1()
    }

    override suspend fun enterBasicSettings(group: Int?): Result<Unit> = withContext(Dispatchers.IO) {
        if (readOnly) return@withContext diagnosticFailure<Unit>(DiagnosticError.UnsupportedFunction)
        val s = session ?: return@withContext diagnosticFailure<Unit>(DiagnosticError.EcuNoResponse)
        s.enterBasicSettings(group ?: 0).map { }.recover1()
    }

    override suspend fun exitBasicSettings(): Result<Unit> = withContext(Dispatchers.IO) {
        val s = session ?: return@withContext Result.success(Unit)
        s.exitBasicSettings().recover1()
    }

    override fun connectionState(): Flow<ConnectionState> = state

    /** Adapter info exposed for the UI (firmware/battery from probe). */
    fun adapterInfo(): AdapterInfo? = adapter

    /** Map a raw session failure onto a typed DiagnosticError result. */
    private fun <T> Result<T>.recover1(): Result<T> = recoverCatching {
        throw io.databang.digidash.core.diagnostics.DiagnosticException(
            (it as? io.databang.digidash.core.diagnostics.DiagnosticException)?.error
                ?: DiagnosticError.ProtocolError(it.message ?: "session error")
        )
    }

    /**
     * Extract a VAG part number + component from the ECU ID ASCII blocks.
     * KWP1281 ID blocks vary; we look for a token that matches a part-number
     * shape and use the longest remaining line as the component name.
     */
    private fun parseIdentity(idBlocks: List<String>, info: AdapterInfo): EcuIdentity {
        val joined = idBlocks.joinToString(" ")
        val partToken = Regex("\\b\\d{3}[\\s-]?\\d{3}[\\s-]?\\d{3}[\\s-]?[A-Z]{0,2}\\b")
            .find(joined)?.value
        val component = idBlocks.maxByOrNull { it.length }?.take(40) ?: "ECU"
        return EcuIdentity.fromRaw(
            partNumberRaw = partToken ?: "unknown",
            component = component,
            protocol = "KWP1281",
        )
    }
}
