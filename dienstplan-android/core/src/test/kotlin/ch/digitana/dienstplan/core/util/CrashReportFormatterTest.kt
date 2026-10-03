package ch.digitana.dienstplan.core.util

import ch.digitana.dienstplan.core.crypto.SecureRandomBytes
import ch.digitana.dienstplan.core.group.JoinCode
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashReportFormatterTest {

    @Test
    fun `Bericht enthaelt Stacktrace, aber keine Geheimnisse`() {
        val secret = SecureRandomBytes.next(32)
        val code = JoinCode.encode(Hex.encode(SecureRandomBytes.next(32)))
        val hex = Hex.encode(secret)
        val error = IllegalStateException(
            "Kaputt mit $code und Schlüssel $hex und Token aGVsbG8gd29ybGQgdGhpcyBpcyBzZWNyZXQ9",
            RuntimeException("Ursache $hex"),
        )
        val report = CrashReportFormatter.format(error, "main", "1.0.0 (1)", "16 (API 36)", "Pixel 9", 1_790_000_000_000)
        assertTrue(report.contains("java.lang.IllegalStateException"))
        assertTrue(report.contains("Caused by: java.lang.RuntimeException"))
        assertTrue(report.contains("CrashReportFormatterTest"))
        assertTrue(report.contains("DP3-[entfernt]"))
        assertFalse(report.contains(code))
        assertFalse(report.contains(code.substring(4, 20)))
        assertFalse(report.contains(hex))
        assertFalse(report.contains("aGVsbG8gd29ybGQgdGhpcyBpcyBzZWNyZXQ9"))
    }

    @Test
    fun `Meldungen von JSON-Parsern entfallen ganz`() {
        val error = kotlinx.serialization.SerializationException("Unexpected token at offset 3: 'Anna Muster'")
        val report = CrashReportFormatter.format(error, "main", "1", "16", "x", 0)
        assertTrue(report.contains("kotlinx.serialization.SerializationException"))
        assertFalse(report.contains("Anna"))
    }

    @Test
    fun `Klassennamen bleiben lesbar`() {
        val text = "at ch.digitana.dienstplan.core.group.GroupSyncEngine.reconcile(GroupSyncEngine.kt:123)"
        assertTrue(CrashReportFormatter.sanitize(text) == text)
    }
}
