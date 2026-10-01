package ch.digitana.dienstplan.core.mls

import ch.digitana.dienstplan.mls.IngestOutcome
import ch.digitana.dienstplan.mls.MlsEngine
import ch.digitana.dienstplan.mls.MlsException
import ch.digitana.dienstplan.mls.isValidIdentitySecret
import ch.digitana.dienstplan.mls.publicKeyOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Die Rust-Bibliothek lässt sich aus Kotlin aufrufen (JNA + UniFFI), der Ablauf stimmt. */
class MlsBindingsTest {

    private val relays = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net")
    private val random = SecureRandom()

    private fun identity(): ByteArray {
        while (true) {
            val candidate = ByteArray(32).also(random::nextBytes)
            if (isValidIdentitySecret(candidate)) return candidate
        }
    }

    private fun engine(dir: File, name: String): MlsEngine =
        MlsEngine.open(File(dir, "$name.db").path, ByteArray(32).also(random::nextBytes), identity())

    @Test
    fun `Team gruenden, Geraet einladen, Nachrichten austauschen`(@TempDir dir: File) {
        engine(dir, "alice").use { alice ->
            engine(dir, "bob").use { bob ->
                val team = alice.createTeam("Pflege", relays)
                assertEquals(listOf(alice.publicKey()), team.admins)

                val invitation = alice.invite(team.groupId, bob.keyPackageEvent(relays))
                alice.confirmPublished(team.groupId)
                val invite = bob.receiveInvite(invitation.welcomeEvents.single())!!
                assertEquals("Pflege", invite.groupName)
                assertEquals(alice.publicKey(), invite.inviter)
                val joined = bob.acceptInvite(invite.inviteId)
                assertEquals(team.groupId, joined.groupId)

                val message = alice.encrypt(team.groupId, 30078u, """{"v":3}""")
                val outcomes = bob.ingest(listOf(message))
                val app = outcomes.single() as IngestOutcome.AppMessage
                assertEquals(alice.publicKey(), app.sender)
                assertEquals("""{"v":3}""", app.content)
                assertEquals(30078.toUShort(), app.kind)
            }
        }
    }

    @Test
    fun `Fehler kommen als MlsException an`(@TempDir dir: File) {
        engine(dir, "alice").use { alice ->
            assertThrows<MlsException.InvalidInput> { alice.encrypt("zz", 1u, "x") }
            assertThrows<MlsException.InvalidInput> { alice.createTeam("x", listOf("http://unsicher.example")) }
        }
        assertThrows<MlsException.InvalidInput> { MlsEngine.open(File(dir, "x.db").path, ByteArray(31), identity()) }
    }

    @Test
    fun `Identitaetsschluessel`() {
        val secret = identity()
        assertTrue(isValidIdentitySecret(secret))
        assertFalse(isValidIdentitySecret(ByteArray(32)))
        assertEquals(64, publicKeyOf(secret).length)
    }
}
