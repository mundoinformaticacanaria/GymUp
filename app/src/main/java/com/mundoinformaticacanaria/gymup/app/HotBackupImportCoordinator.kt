package com.mundoinformaticacanaria.gymup.app

import com.mundoinformaticacanaria.gymup.data.backup.BackupImportResult
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Application-lifetime primitive for the #56 hot-import UI gate.
 *
 * The owner must supply a supervised application scope, NOT rememberCoroutineScope or an
 * Activity scope. [readersDisposed] must be called only after the repository-backed UI
 * has actually left composition; until then the import callback cannot run.
 *
 * The production UI must close its repository-backed composition before acknowledging
 * the gate. This protects Compose readers, not other processes or physical power loss.
 */
class HotBackupImportCoordinator(
    private val scope: CoroutineScope,
    private val importArchive: suspend (ByteArray) -> BackupImportResult,
    private val recover: suspend () -> Unit,
) {
    sealed interface State {
        data object Idle : State
        data object ClosingReaders : State
        data object Importing : State
        data object Recovering : State
        data class Completed(val outcome: Outcome, val generation: Long) : State
        data class Blocked(val message: String) : State
    }

    sealed interface Outcome {
        data class Result(val value: BackupImportResult) : Outcome
        data class Failed(val message: String) : Outcome
    }

    private val active = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val readerGate = AtomicReference<CompletableDeferred<Unit>?>(null)
    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()
    private var originalFailure: String = "No se pudo importar el backup."

    /** Reject concurrent imports and imports while recovery is blocked. */
    fun start(archive: ByteArray): Boolean {
        if (!scope.isActive || mutableState.value is State.Blocked ||
            !active.compareAndSet(false, true)
        ) return false

        val closed = CompletableDeferred<Unit>()
        readerGate.set(closed)
        mutableState.value = State.ClosingReaders
        scope.launch {
            try {
                closed.await()
                mutableState.value = State.Importing
                val result = importArchive(archive)
                mutableState.value = State.Completed(Outcome.Result(result), generation.incrementAndGet())
            } catch (failure: Exception) {
                originalFailure = failure.message ?: "No se pudo importar el backup."
                mutableState.value = State.Recovering
                // A cancellation must not expose readers before rollback/recovery completes.
                withContext(NonCancellable) {
                    try {
                        recover()
                        mutableState.value = State.Completed(
                            Outcome.Failed(originalFailure),
                            generation.incrementAndGet(),
                        )
                    } catch (recoveryFailure: Exception) {
                        mutableState.value = State.Blocked(
                            recoveryFailure.message ?: "Recuperación de backup pendiente",
                        )
                    }
                }
            } finally {
                readerGate.compareAndSet(closed, null)
                active.set(false)
            }
        }
        return true
    }

    /** Called by the top-level UI after its data-consuming composition is disposed. */
    fun readersDisposed() {
        if (mutableState.value == State.ClosingReaders) readerGate.get()?.complete(Unit)
    }

    /** Keep readers closed until a failed recovery is explicitly retried successfully. */
    fun retryRecovery(): Boolean {
        if (!scope.isActive || mutableState.value !is State.Blocked ||
            !active.compareAndSet(false, true)
        ) return false

        mutableState.value = State.Recovering
        scope.launch {
            try {
                recover()
                mutableState.value = State.Completed(
                    Outcome.Failed(originalFailure),
                    generation.incrementAndGet(),
                )
            } catch (failure: Exception) {
                mutableState.value = State.Blocked(
                    failure.message ?: "Recuperación de backup pendiente",
                )
            } finally {
                active.set(false)
            }
        }
        return true
    }
}
