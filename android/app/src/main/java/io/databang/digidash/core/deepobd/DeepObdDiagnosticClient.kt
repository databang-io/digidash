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
    var ecuAddress: Int = 0x01,
    var kwpBaud: Int = 9600,
    var readOnly: Boolean = false,
) : DiagnosticClient {

    /** Live-tunable framing config used at next connect (debug bridge). */
    var kwpConfig: Kwp1281Config = Kwp1281Config()

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

        // Drain any residual probe bytes (voltage/version replies) so they are
        // not misread as the KWP1281 init response.
        runCatching {
            var drained = 0
            while (t.read(256, 120).isNotEmpty() && drained < 8) drained++
        }

        // Live KWP1281 init: 5-baud wake (firmware) + pump the ECU ID blocks.
        // If the keep-alive loop dies (socket/ECU drop), reflect it as ERROR so
        // the session repository can auto-reconnect without losing the dongle.
        val s = Kwp1281Session(
            t, baud = kwpBaud, ecuAddress = ecuAddress, config = kwpConfig,
            onLost = { if (state.value == ConnectionState.CONNECTED) state.value = ConnectionState.ERROR },
        )
        val idResult = s.connect()
        if (idResult.isFailure) {
            state.value = ConnectionState.ERROR
            return@withContext diagnosticFailure<Unit>(
                idResult.exceptionOrNull()?.let { DiagnosticError.ProtocolError(it.message ?: "init failed") }
                    ?: DiagnosticError.EcuNoResponse)
        }
        s.onMeasureBlock = { block -> _measurementFlow.tryEmit(block) }
        s.onEcuRestart = { n -> _ecuRestarts.tryEmit(n) }
        s.onGroupFailure = { g -> _groupFailures.tryEmit(g) }
        session = s
        identity = parseIdentity(idResult.getOrDefault(emptyList()), info)
        state.value = ConnectionState.CONNECTED
        Result.success(Unit)
    }

    override suspend fun disconnect() {
        runCatching { session?.close() }
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
        if (readOnly) return@withContext diagnosticFailure<Unit>(
            DiagnosticError.ProtocolError("Safe (read-only) mode is on — clearing is blocked"))
        val s = session ?: return@withContext diagnosticFailure<Unit>(DiagnosticError.EcuNoResponse)
        s.clearDtc().recover1()
    }

    override suspend fun enterBasicSettings(group: Int?): Result<Unit> = withContext(Dispatchers.IO) {
        if (readOnly) return@withContext diagnosticFailure<Unit>(
            DiagnosticError.ProtocolError("Safe (read-only) mode is on — Basic Settings blocked"))
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

    /** Debug: send raw bytes straight to the adapter and return the response. */
    /** Battery voltage from the adapter's FC telegram, via the session loop. */
    suspend fun adapterVoltage(): Double? = withContext(Dispatchers.IO) {
        session?.readAdapterVoltage()?.getOrNull()
    }

    // --- Continuous measuring stream (session-loop driven; the stream IS the
    // keep-alive while it runs). ---
    private val _measurementFlow =
        kotlinx.coroutines.flow.MutableSharedFlow<io.databang.digidash.domain.model.RawMeasuringBlock>(
            extraBufferCapacity = 32)
    val measurementFlow:
        kotlinx.coroutines.flow.SharedFlow<io.databang.digidash.domain.model.RawMeasuringBlock> =
        _measurementFlow

    private val _groupFailures =
        kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 16)
    val groupFailures: kotlinx.coroutines.flow.SharedFlow<Int> = _groupFailures

    /** Configure the measuring stream (empty list stops it). */
    fun configureMeasureStream(groups: List<Int>, basicSettings: Boolean = false) {
        session?.setMeasureStream(
            if (groups.isEmpty()) null
            else Kwp1281Session.StreamSpec(groups, basicSettings = basicSettings))
    }

    private val _ecuRestarts = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 8)
    /** Spontaneous ECU restart notifications (count so far this session). */
    val ecuRestarts: kotlinx.coroutines.flow.SharedFlow<Int> = _ecuRestarts

    /** Request a soft 0x00 resync of the live session (KaPoder recovery). */
    fun requestResync() { session?.requestResync() }

    /** Debug: tap RAW stream frames (group, title, data) for offline capture. */
    fun setStreamRawTap(tap: ((Int, Int, ByteArray) -> Unit)?) {
        session?.onStreamRaw = if (tap == null) null else { g, b -> tap(g, b.title, b.data) }
    }

    /** The ECU identity ASCII blocks captured at connect (for the dump header). */
    fun idBlocks(): List<String> = debugIdBlocks()

    /** Debug: KaPoder-style group read with 0x00 resync between attempts. */
    suspend fun debugGroupResync(group: Int): String = withContext(Dispatchers.IO) {
        session?.readGroupResync(group)?.getOrElse { "ERR ${it.message}" } ?: "no session"
    }

    /** Debug: send an arbitrary KW1281 block via the live session, dump all replies. */
    suspend fun debugBlock(title: Int, data: ByteArray): String = withContext(Dispatchers.IO) {
        session?.debugExchange(title, data)?.getOrElse { "ERR ${it.message}" } ?: "no session"
    }

    suspend fun debugRaw(bytes: ByteArray, timeoutMs: Long = 1500): ByteArray =
        withContext(Dispatchers.IO) {
            val t = transport ?: return@withContext ByteArray(0)
            t.write(bytes)
            t.read(256, timeoutMs)
        }

    /** Debug: is a transport currently open? */
    fun isTransportOpen(): Boolean = transport?.isConnected == true

    /** Debug: adapter battery voltage via the FC status telegram (×0.1 V). */
    suspend fun debugVoltage(): Double? = withContext(Dispatchers.IO) {
        val t = transport ?: return@withContext null
        t.write(AdapterProtocol.READ_VOLTAGE)
        val r = t.read(64, 1000)
        if (r.size < 6) null else (r[r.size - 2].toInt() and 0xFF) * 0.1
    }

    /** Debug: the raw ECU identification blocks captured at connect. */
    fun debugIdBlocks(): List<String> = session?.identificationBlocks().orEmpty()

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
        val partNorm = partToken?.let { EcuIdentity.normalizePartNumber(it) }
        // The component block is the one with letters that is not the part number
        // (e.g. "DIGIFANT 1.7      1576"). Collapse the padding whitespace.
        val compBlock = idBlocks.firstOrNull { blk ->
            blk.any { it.isLetter() } && EcuIdentity.normalizePartNumber(blk) != partNorm
        } ?: idBlocks.maxByOrNull { it.length }
        // "DIGIFANT 1.7      1576" -> component "DIGIFANT 1.7" + version "1576".
        val cleaned = compBlock?.replace(Regex("\\s+"), " ")?.trim()
        val softwareVersion = cleaned?.let { Regex("(\\d{3,})$").find(it)?.groupValues?.get(1) }
        val component = cleaned
            ?.let { c -> softwareVersion?.let { c.removeSuffix(it).trim() } ?: c }
            ?.take(40) ?: "ECU"
        return EcuIdentity.fromRaw(
            partNumberRaw = partToken ?: "unknown",
            component = component,
            softwareVersion = softwareVersion,
            protocol = "KWP1281",
        )
    }
}
