package io.databang.digidash

import io.databang.digidash.core.logging.TripLogController
import io.databang.digidash.data.repository.DiagnosticSessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/**
 * App-scoped owner of the single diagnostic session and trip logger, so the
 * phone UI and the Android Auto surface reflect the same live connection.
 */
class SessionHolder(container: AppContainer) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val session = DiagnosticSessionRepository(
        clientProvider = { container.diagnosticClient },
        modelRepositoryProvider = { container.modelRepository() },
        interpreter = container.interpreter,
        scope = scope,
    )

    val tripLog = TripLogController(
        session = session,
        logRepository = container.logRepository,
        scope = scope,
    )
}
