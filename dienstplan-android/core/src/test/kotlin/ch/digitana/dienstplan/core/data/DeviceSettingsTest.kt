package ch.digitana.dienstplan.core.data

import ch.digitana.dienstplan.core.crdt.Shift
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceSettingsTest {

    private val anna = "000000000000000a"

    @Test
    fun `Rundlauf mit allen Feldern`() {
        val settings = DeviceSettings(
            myMemberId = anna,
            notifyOnChanges = true,
            seenShifts = mapOf(LocalDate.of(2026, 10, 7) to Shift.FRUEH, LocalDate.of(2026, 10, 8) to Shift.URLAUB),
            notifiedShifts = mapOf(LocalDate.of(2026, 10, 7) to Shift.SPAET),
        )
        assertEquals(settings, DeviceSettingsCodec.decode(DeviceSettingsCodec.encode(settings)))
        assertEquals(DeviceSettings(), DeviceSettingsCodec.decode(DeviceSettingsCodec.encode(DeviceSettings())))
    }

    @Test
    fun `ungueltige Daten werden abgelehnt`() {
        val bad = listOf(
            """[]""",
            """{"v":2,"notify":false}""",
            """{"v":1,"me":"anna","notify":false}""",
            """{"v":1,"me":5,"notify":false}""",
            """{"v":1,"notify":"ja"}""",
            """{"v":1,"notify":true,"seen":{"2026-02-30":"F"}}""",
            """{"v":1,"notify":true,"seen":{"1999-12-31":"F"}}""",
            """{"v":1,"notify":true,"seen":{"2026-10-07":"Q"}}""",
            """{"v":1,"notify":true,"seen":{"2026-10-07":1}}""",
            """{"v":1,"notify":true,"seen":[]}""",
        )
        for (json in bad) {
            assertThrows<IllegalArgumentException>(json) { DeviceSettingsCodec.decode(json.toByteArray()) }
        }
    }

    @Test
    fun `Repository speichert nur Aenderungen und setzt beim Loeschen zurueck`() = runTest {
        val store = MemoryStore()
        val repository = SettingsRepository(store, Dispatchers.Unconfined)
        repository.load()
        assertEquals(DeviceSettings(), repository.settings.value)

        repository.update { it.copy(myMemberId = anna) }
        repository.update { it.copy(myMemberId = anna) }
        assertEquals(1, store.saves)

        val reloaded = SettingsRepository(store, Dispatchers.Unconfined).apply { load() }
        assertEquals(anna, reloaded.settings.value.myMemberId)

        repository.clear()
        assertNull(store.saved)
        assertEquals(DeviceSettings(), repository.settings.value)
    }

    private class MemoryStore : SettingsStore {
        var saved: DeviceSettings? = null
        var saves = 0
        override fun load(): DeviceSettings? = saved
        override fun save(settings: DeviceSettings) {
            saved = settings
            saves++
        }
        override fun clear() {
            saved = null
        }
    }
}
