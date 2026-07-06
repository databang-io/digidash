package io.databang.digidash.core.diagnostics.fake

import io.databang.digidash.core.diagnostics.ConnectionConfig
import io.databang.digidash.core.diagnostics.ConnectionState
import io.databang.digidash.core.diagnostics.DiagnosticClient
import io.databang.digidash.core.diagnostics.DiagnosticError
import io.databang.digidash.core.diagnostics.diagnosticFailure
import io.databang.digidash.domain.model.EcuIdentity
import io.databang.digidash.domain.model.RawDtc
import io.databang.digidash.domain.model.RawField
import io.databang.digidash.domain.model.RawMeasuringBlock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** Mirrors samples/deepobd/raw-blocks-037906024AG.json. */
@Serializable
data class FakeSampleData(
    val ecu: String,
    val blocks: List<FakeSampleBlock>,
)

@Serializable
data class FakeSampleBlock(
    val group: Int,
    val fields: List<String>,
)

/** Fake behaviours used by tests and the developer settings screen. */
enum class FakeScenario { NORMAL, DONGLE_NOT_FOUND, ECU_NO_RESPONSE, TIMEOUT }

/**
 * Replays sample data captured from the target vehicle so the whole UI can be
 * developed and tested without a dongle.
 *
 * @param sampleJson content of samples/deepobd/raw-blocks-037906024AG.json
 * @param jitter when true, numeric values wiggle a little so the dashboard feels alive
 * @param operationDelayMillis simulated ECU latency (KWP1281 is slow)
 */
class FakeDiagnosticClient(
    sampleJson: String = DEFAULT_SAMPLE_JSON,
    var scenario: FakeScenario = FakeScenario.NORMAL,
    private val jitter: Boolean = false,
    private val operationDelayMillis: Long = 150,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : DiagnosticClient {

    private val sample: FakeSampleData =
        Json { ignoreUnknownKeys = true }.decodeFromString(sampleJson)

    private val state = MutableStateFlow(ConnectionState.DISCONNECTED)

    override suspend fun connect(config: ConnectionConfig): Result<Unit> {
        state.value = ConnectionState.CONNECTING
        delay(operationDelayMillis)
        return when (scenario) {
            FakeScenario.DONGLE_NOT_FOUND -> {
                state.value = ConnectionState.ERROR
                diagnosticFailure(DiagnosticError.DongleNotFound)
            }
            FakeScenario.ECU_NO_RESPONSE -> {
                state.value = ConnectionState.ERROR
                diagnosticFailure(DiagnosticError.EcuNoResponse)
            }
            else -> {
                state.value = ConnectionState.CONNECTED
                Result.success(Unit)
            }
        }
    }

    override suspend fun disconnect() {
        state.value = ConnectionState.DISCONNECTED
    }

    override suspend fun identifyEcu(): Result<EcuIdentity> {
        val failure = guard<EcuIdentity>("identifyEcu")
        if (failure != null) return failure
        return Result.success(
            EcuIdentity.fromRaw(
                partNumberRaw = "037 906 024 AG",
                component = "DIGIFANT 2E",
                serialNumber = "FAKE-0001",
                protocol = "KWP1281",
            )
        )
    }

    override suspend fun readMeasuringBlock(group: Int): Result<RawMeasuringBlock> {
        val failure = guard<RawMeasuringBlock>("readMeasuringBlock $group")
        if (failure != null) return failure
        val block = sample.blocks.find { it.group == group }
            ?: return diagnosticFailure(DiagnosticError.UnsupportedFunction)
        val fields = block.fields.mapIndexed { i, raw ->
            RawField(index = i + 1, raw = applyJitter(raw))
        }
        return Result.success(
            RawMeasuringBlock(group = group, fields = fields, timestampMillis = clock())
        )
    }

    override suspend fun readDtc(): Result<List<RawDtc>> {
        val failure = guard<List<RawDtc>>("readDtc")
        if (failure != null) return failure
        return Result.success(emptyList())
    }

    override suspend fun clearDtc(): Result<Unit> {
        val failure = guard<Unit>("clearDtc")
        if (failure != null) return failure
        return Result.success(Unit)
    }

    override suspend fun enterBasicSettings(group: Int?): Result<Unit> =
        diagnosticFailure(DiagnosticError.UnsupportedFunction)

    override suspend fun exitBasicSettings(): Result<Unit> =
        diagnosticFailure(DiagnosticError.UnsupportedFunction)

    override fun connectionState(): Flow<ConnectionState> = state

    private suspend fun <T> guard(operation: String): Result<T>? {
        delay(operationDelayMillis)
        if (scenario == FakeScenario.TIMEOUT) {
            return diagnosticFailure(DiagnosticError.Timeout(operation))
        }
        if (state.value != ConnectionState.CONNECTED) {
            return diagnosticFailure(DiagnosticError.EcuNoResponse)
        }
        return null
    }

    private fun applyJitter(raw: String): String {
        if (!jitter) return raw
        val numeric = raw.toDoubleOrNull() ?: return raw
        val wiggled = numeric * (1 + Random.nextDouble(-0.02, 0.02))
        return if (raw.contains('.')) String.format(java.util.Locale.US, "%.1f", wiggled)
        else wiggled.toLong().toString()
    }

    companion object {
        /** Same content as samples/deepobd/raw-blocks-037906024AG.json. */
        val DEFAULT_SAMPLE_JSON = """
            {
              "ecu": "037906024AG",
              "blocks": [
                { "group": 1, "fields": ["920", "21", "88", "2.6"] },
                { "group": 2, "fields": ["920", "24", "10", "2.6"] },
                { "group": 5, "fields": ["920", "2.5", "202", "2.6"] },
                { "group": 9, "fields": ["920", "41", "253", "52"] }
              ]
            }
        """.trimIndent()
    }
}
