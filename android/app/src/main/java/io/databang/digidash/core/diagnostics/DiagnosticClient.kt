package io.databang.digidash.core.diagnostics

import io.databang.digidash.domain.model.EcuIdentity
import io.databang.digidash.domain.model.RawDtc
import io.databang.digidash.domain.model.RawMeasuringBlock
import kotlinx.coroutines.flow.Flow

/**
 * Transport-level configuration. In fake mode the dongle is ignored; the real
 * Deep OBD adapter will open a Bluetooth SPP socket to [dongleAddress]
 * (same class of ELM327/SPP dongles Deep OBD uses).
 */
data class ConnectionConfig(
    val useFakeBackend: Boolean = true,
    val dongleAddress: String? = null,
    val dongleName: String? = null,
)

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

sealed class DiagnosticError {
    data object DongleNotFound : DiagnosticError()
    data object EcuNoResponse : DiagnosticError()
    data object UnsupportedFunction : DiagnosticError()
    data class ProtocolError(val raw: String) : DiagnosticError()
    data class Timeout(val operation: String) : DiagnosticError()
    data class Unknown(val message: String) : DiagnosticError()

    fun userMessage(): String = when (this) {
        DongleNotFound -> "Dongle not found"
        EcuNoResponse -> "ECU does not respond"
        UnsupportedFunction -> "Unsupported by ECU"
        is ProtocolError -> "Protocol error"
        is Timeout -> "Timeout ($operation)"
        is Unknown -> "Unknown error"
    }
}

/** Carrier exception so DiagnosticError travels through kotlin.Result. */
class DiagnosticException(val error: DiagnosticError) : Exception(error.userMessage())

fun <T> diagnosticFailure(error: DiagnosticError): Result<T> =
    Result.failure(DiagnosticException(error))

fun Throwable.asDiagnosticError(): DiagnosticError =
    (this as? DiagnosticException)?.error ?: DiagnosticError.Unknown(message ?: "unexpected")

/**
 * Abstraction over the vehicle diagnostic link.
 *
 * Implementations: [io.databang.digidash.core.diagnostics.fake.FakeDiagnosticClient] (MVP),
 * later a Deep OBD / EdiabasLib adapter. UI code must never depend on a concrete client.
 */
interface DiagnosticClient {
    suspend fun connect(config: ConnectionConfig): Result<Unit>
    suspend fun disconnect()
    suspend fun identifyEcu(): Result<EcuIdentity>
    suspend fun readMeasuringBlock(group: Int): Result<RawMeasuringBlock>
    suspend fun readDtc(): Result<List<RawDtc>>
    suspend fun clearDtc(): Result<Unit>
    suspend fun enterBasicSettings(group: Int?): Result<Unit>
    suspend fun exitBasicSettings(): Result<Unit>
    fun connectionState(): Flow<ConnectionState>
}
