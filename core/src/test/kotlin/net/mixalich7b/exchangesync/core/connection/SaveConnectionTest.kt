package net.mixalich7b.exchangesync.core.connection

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SaveConnectionTest {
    @Test
    fun `invalid draft neither probes nor replaces the saved profile`() =
        runTest {
            val previous = profile(email = "previous@example.test")
            val repository = FakeRepository(previous)
            val verifier = FakeVerifier(ConnectionCheckResult.Success)

            val result = SaveConnection(repository, verifier).execute(ConnectionDraft())

            assertTrue(result is SaveConnectionResult.Invalid)
            assertEquals(ConnectionField.entries.toSet(), (result as SaveConnectionResult.Invalid).errors.keys)
            assertEquals(emptyList<ConnectionProfile>(), verifier.profiles)
            assertEquals(0, repository.replaceAttempts)
            assertEquals(previous, repository.current)
        }

    @Test
    fun `every probe failure is returned without replacing the saved profile`() =
        runTest {
            ConnectionFailure.entries
                .filterNot { failure -> failure == ConnectionFailure.PERSISTENCE }
                .forEach { failure ->
                    val previous = profile(email = "previous@example.test")
                    val repository = FakeRepository(previous)
                    val verifier = FakeVerifier(ConnectionCheckResult.Failure(failure))

                    val result = SaveConnection(repository, verifier).execute(validDraft())

                    assertEquals(SaveConnectionResult.Failed(failure), result, failure.code)
                    assertEquals(listOf(profile()), verifier.profiles, failure.code)
                    assertEquals(0, repository.replaceAttempts, failure.code)
                    assertEquals(previous, repository.current, failure.code)
                }
        }

    @Test
    fun `successful probe replaces the single profile exactly once`() =
        runTest {
            val repository = FakeRepository(profile(email = "previous@example.test"))
            val verifier = FakeVerifier(ConnectionCheckResult.Success)

            val result = SaveConnection(repository, verifier).execute(validDraft())

            assertEquals(SaveConnectionResult.Saved(profile()), result)
            assertEquals(listOf(profile()), verifier.profiles)
            assertEquals(1, repository.replaceAttempts)
            assertEquals(profile(), repository.current)
        }

    @Test
    fun `persistence failure is reported and previous profile remains unchanged`() =
        runTest {
            val previous = profile(email = "previous@example.test")
            val repository = FakeRepository(previous, failReplacement = true)
            val verifier = FakeVerifier(ConnectionCheckResult.Success)

            val result = SaveConnection(repository, verifier).execute(validDraft())

            assertEquals(SaveConnectionResult.Failed(ConnectionFailure.PERSISTENCE), result)
            assertEquals(1, repository.replaceAttempts)
            assertEquals(previous, repository.current)
        }

    private class FakeRepository(
        initial: ConnectionProfile?,
        private val failReplacement: Boolean = false,
    ) : ConnectionProfileRepository {
        var current: ConnectionProfile? = initial
        var replaceAttempts: Int = 0

        override suspend fun load(): ConnectionProfile? = current

        override suspend fun replace(profile: ConnectionProfile) {
            replaceAttempts += 1
            if (failReplacement) error("simulated atomic write failure")
            current = profile
        }
    }

    private class FakeVerifier(
        private val result: ConnectionCheckResult,
    ) : ConnectionVerifier {
        val profiles = mutableListOf<ConnectionProfile>()

        override suspend fun verify(profile: ConnectionProfile): ConnectionCheckResult {
            profiles += profile
            return result
        }
    }

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
}
