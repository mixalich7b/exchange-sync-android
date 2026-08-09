package net.mixalich7b.exchangesync.core.connection

import java.time.Instant
import kotlinx.coroutines.test.runTest
import net.mixalich7b.exchangesync.core.sync.ProfileSynchronizationActivator
import net.mixalich7b.exchangesync.core.sync.SyncLifecycleOutcome
import net.mixalich7b.exchangesync.core.sync.SyncProblem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SaveConnectionTest {
    @Test
    fun `invalid verification result neither probes nor replaces the saved profile`() =
        runTest {
            val previous = profile(email = "previous@example.test")
            val repository = FakeRepository(previous)
            val verify = RecordingVerifyAction(VerifyConnectionResult.Invalid(allFieldErrors()))
            val activator = RecordingActivator()

            val result = SaveConnection(repository, verify, activator).execute(ConnectionDraft())

            assertEquals(listOf(ConnectionDraft()), verify.drafts)
            assertEquals(0, repository.replaceAttempts)
            assertEquals(previous, repository.current)
            assertEquals(0, activator.attempts)
            assertEquals(SaveConnectionResult.Invalid(allFieldErrors()), result)
        }

    @Test
    fun `verification failure is returned without replacing the saved profile`() =
        runTest {
            val previous = profile(email = "previous@example.test")
            val repository = FakeRepository(previous)
            val verify = RecordingVerifyAction(VerifyConnectionResult.Failed(ConnectionFailure.ACCESS_DENIED))
            val activator = RecordingActivator()

            val result = SaveConnection(repository, verify, activator).execute(validDraft())

            assertEquals(listOf(validDraft()), verify.drafts)
            assertEquals(0, repository.replaceAttempts)
            assertEquals(previous, repository.current)
            assertEquals(0, activator.attempts)
            assertEquals(SaveConnectionResult.Failed(ConnectionFailure.ACCESS_DENIED), result)
        }

    @Test
    fun `first verified profile persists before activating synchronization and preserves TLS diagnostics`() =
        runTest {
            val trace = mutableListOf<String>()
            val diagnostics = diagnostics()
            val repository = FakeRepository(null, trace = trace)
            val verify = RecordingVerifyAction(VerifyConnectionResult.Verified(profile(), diagnostics))
            val activator = RecordingActivator(trace = trace, onActivate = repository::atomicCommit)

            val result = SaveConnection(repository, verify, activator).execute(validDraft())

            assertEquals(listOf(validDraft()), verify.drafts)
            assertEquals(0, repository.replaceAttempts)
            assertEquals(profile(), repository.current)
            assertEquals(1, activator.attempts)
            assertEquals(listOf("sync:activate", "profile+generation:commit"), trace)
            assertEquals(SaveConnectionResult.Saved(profile(), diagnostics), result)
        }

    @Test
    fun `changed verified profile activates a new synchronization generation`() =
        runTest {
            val repository = FakeRepository(profile(email = "previous@example.test"))
            val activator = RecordingActivator(onActivate = repository::atomicCommit)

            val result =
                SaveConnection(
                    repository,
                    RecordingVerifyAction(VerifyConnectionResult.Verified(profile(), diagnostics())),
                    activator,
                ).execute(validDraft())

            assertEquals(SaveConnectionResult.Saved(profile(), diagnostics()), result)
            assertEquals(profile(), repository.current)
            assertEquals(0, repository.replaceAttempts)
            assertEquals(1, activator.attempts)
        }

    @Test
    fun `unchanged verified profile is rechecked without persistence or synchronization activation`() =
        runTest {
            val repository = FakeRepository(profile())
            val activator = RecordingActivator()

            val result =
                SaveConnection(
                    repository,
                    RecordingVerifyAction(VerifyConnectionResult.Verified(profile(), diagnostics())),
                    activator,
                ).execute(validDraft())

            assertEquals(SaveConnectionResult.Saved(profile(), diagnostics()), result)
            assertEquals(0, repository.replaceAttempts)
            assertEquals(0, activator.attempts)
        }

    @Test
    fun `post-persistence lifecycle failure keeps the verified profile saved`() =
        runTest {
            val previous = profile(email = "previous@example.test")
            val repository = FakeRepository(previous)
            val activator =
                RecordingActivator(
                    outcome = SyncLifecycleOutcome.Blocked(2, SyncProblem.BACKGROUND_SCHEDULING),
                    onActivate = repository::atomicCommit,
                )

            val result =
                SaveConnection(
                    repository,
                    RecordingVerifyAction(VerifyConnectionResult.Verified(profile(), diagnostics())),
                    activator,
                ).execute(validDraft())

            assertEquals(SaveConnectionResult.Saved(profile(), diagnostics()), result)
            assertEquals(profile(), repository.current)
            assertEquals(1, activator.attempts)
        }

    @Test
    fun `unexpected post-persistence lifecycle exception does not roll back the verified profile`() =
        runTest {
            val repository = FakeRepository(null)
            val activator = RecordingActivator(fail = true, onActivate = repository::atomicCommit)

            val result =
                SaveConnection(
                    repository,
                    RecordingVerifyAction(VerifyConnectionResult.Verified(profile(), diagnostics())),
                    activator,
                ).execute(validDraft())

            assertEquals(SaveConnectionResult.Saved(profile(), diagnostics()), result)
            assertEquals(profile(), repository.current)
            assertTrue(activator.attempts == 1)
        }

    @Test
    fun `persistence failure is reported after successful verification`() =
        runTest {
            val previous = profile(email = "previous@example.test")
            val repository = FakeRepository(previous)
            val verify = RecordingVerifyAction(VerifyConnectionResult.Verified(profile(), diagnostics()))
            val activator = RecordingActivator(persistenceFailure = true)

            val result = SaveConnection(repository, verify, activator).execute(validDraft())

            assertEquals(listOf(validDraft()), verify.drafts)
            assertEquals(0, repository.replaceAttempts)
            assertEquals(previous, repository.current)
            assertEquals(1, activator.attempts)
            assertEquals(SaveConnectionResult.Failed(ConnectionFailure.PERSISTENCE), result)
        }

    private class FakeRepository(
        initial: ConnectionProfile?,
        private val failReplacement: Boolean = false,
        private val trace: MutableList<String> = mutableListOf(),
    ) : ConnectionProfileRepository {
        var current: ConnectionProfile? = initial
        var replaceAttempts: Int = 0

        override suspend fun load(): ConnectionProfile? = current

        override suspend fun replace(profile: ConnectionProfile) {
            replaceAttempts += 1
            if (failReplacement) error("simulated atomic write failure")
            current = profile
            trace += "profile:persist"
        }

        fun atomicCommit(profile: ConnectionProfile) {
            current = profile
            trace += "profile+generation:commit"
        }
    }

    private class RecordingActivator(
        private val outcome: SyncLifecycleOutcome = SyncLifecycleOutcome.Scheduled(1),
        private val fail: Boolean = false,
        private val trace: MutableList<String> = mutableListOf(),
        private val onActivate: (ConnectionProfile) -> Unit = {},
        private val persistenceFailure: Boolean = false,
    ) : ProfileSynchronizationActivator {
        var attempts: Int = 0
        val profiles = mutableListOf<ConnectionProfile>()

        override suspend fun activateProfile(profile: ConnectionProfile): SyncLifecycleOutcome {
            attempts += 1
            profiles += profile
            trace += "sync:activate"
            if (persistenceFailure) {
                throw net.mixalich7b.exchangesync.core.sync.ProfileActivationPersistenceException(
                    IllegalStateException("simulated atomic write failure"),
                )
            }
            onActivate(profile)
            if (fail) error("simulated post-persistence lifecycle failure")
            return outcome
        }
    }

    private class RecordingVerifyAction(
        private val result: VerifyConnectionResult,
    ) : VerifyConnectionAction {
        val drafts = mutableListOf<ConnectionDraft>()
        override suspend fun execute(draft: ConnectionDraft): VerifyConnectionResult {
            drafts += draft
            return result
        }
    }

    private fun allFieldErrors(): Map<ConnectionField, FieldError> =
        ConnectionField.entries.associateWith { FieldError.REQUIRED }

    private fun validDraft(): ConnectionDraft =
        ConnectionDraft(
            email = "calendar@example.test",
            account = "DOMAIN\\calendar",
            serverHost = "exchange.example.test",
            clientCertificateAlias = "work-certificate",
        )

    private fun profile(email: String = "calendar@example.test"): ConnectionProfile =
        ConnectionProfile(
            email = email,
            account = "DOMAIN\\calendar",
            serverHost = "exchange.example.test",
            clientCertificateAlias = "work-certificate",
        )

    private fun diagnostics(): TlsConnectionDiagnostics =
        TlsConnectionDiagnostics(
            terminalHost = "exchange.example.test",
            certificates =
                listOf(
                    TlsCertificateDiagnostic(
                        subject = "CN=exchange.example.test",
                        issuer = "CN=Example CA",
                        serialNumber = "01",
                        validFrom = Instant.parse("2026-01-01T00:00:00Z"),
                        validUntil = Instant.parse("2027-01-01T00:00:00Z"),
                        sha256Fingerprint = "AA:BB",
                    ),
                ),
        )
}
