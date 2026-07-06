package io.databang.digidash

import io.databang.digidash.core.ecumodel.EcuModel
import io.databang.digidash.core.interpret.DtcInterpreter
import io.databang.digidash.domain.model.DtcSeverity
import io.databang.digidash.domain.model.RawDtc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DtcInterpreterTest {

    private val model = EcuModel(
        ecuModelVersion = 1,
        ecuPartNumber = "037906024AG",
        displayName = "test",
        protocol = "KWP1281",
        dtcCatalog = mapOf(
            "00515" to "Camshaft Position (Hall) Sensor G40 - no signal",
            "00522" to "Coolant Temperature Sensor G62 - open/short",
        ),
    )

    @Test
    fun `known critical code gets title and critical severity`() {
        val dtc = DtcInterpreter.interpret(RawDtc("00515", "27-10"), model)
        assertEquals("Camshaft Position (Hall) Sensor G40 - no signal", dtc.title)
        assertEquals(DtcSeverity.CRITICAL, dtc.severity)
    }

    @Test
    fun `known non-critical code is warning`() {
        val dtc = DtcInterpreter.interpret(RawDtc("00522", "35-00"), model)
        assertEquals(DtcSeverity.WARNING, dtc.severity)
    }

    @Test
    fun `unknown code has no title and info severity`() {
        val dtc = DtcInterpreter.interpret(RawDtc("01247", "00-00"), model)
        assertNull(dtc.title)
        assertEquals(DtcSeverity.INFO, dtc.severity)
    }

    @Test
    fun `no model still returns raw code`() {
        val dtc = DtcInterpreter.interpret(RawDtc("00515"), null)
        assertEquals("00515", dtc.code)
        assertEquals(DtcSeverity.CRITICAL, dtc.severity)
    }
}
