package io.databang.digidash

import io.databang.digidash.core.ecumodel.EcuField
import io.databang.digidash.core.ecumodel.EcuGroup
import io.databang.digidash.core.ecumodel.EcuModel
import io.databang.digidash.core.ecumodel.Formula
import io.databang.digidash.core.ecumodel.ThresholdRange
import io.databang.digidash.core.ecumodel.Thresholds
import io.databang.digidash.core.interpret.DefaultMeasurementInterpreter
import io.databang.digidash.domain.model.MeasurementStatus
import io.databang.digidash.domain.model.RawField
import io.databang.digidash.domain.model.RawMeasuringBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeasurementInterpreterTest {

    private val interpreter = DefaultMeasurementInterpreter()

    private fun model(fields: List<EcuField>) = EcuModel(
        ecuModelVersion = 1,
        ecuPartNumber = "037906024AG",
        displayName = "test",
        protocol = "KWP1281",
        groups = mapOf("001" to EcuGroup(label = "test group", fields = fields)),
    )

    private fun block(vararg raw: String) = RawMeasuringBlock(
        group = 1,
        fields = raw.mapIndexed { i, value -> RawField(i + 1, value) },
        timestampMillis = 123L,
    )

    @Test
    fun `scale_offset formula converts value`() {
        val model = model(
            listOf(
                EcuField(
                    index = 1, key = "coolant_temp", name = "Coolant", unit = "°C",
                    formula = Formula(type = "scale_offset", scale = 0.75, offset = -48.0),
                )
            )
        )
        val result = interpreter.interpret(block("181"), model)
        assertEquals(87.8, result.measurements[0].value!!, 0.05)
    }

    @Test
    fun `enum formula maps raw to label`() {
        val model = model(
            listOf(
                EcuField(
                    index = 1, key = "idle_state", name = "Idle", unit = "",
                    formula = Formula(type = "enum", map = mapOf("1" to "Idle", "0" to "Part throttle")),
                )
            )
        )
        assertEquals("Idle", interpreter.interpret(block("1"), model).measurements[0].displayValue)
    }

    @Test
    fun `missing field becomes NA unavailable, never zero`() {
        val model = model(
            listOf(
                EcuField(index = 1, key = "rpm", name = "RPM", unit = "rpm"),
                EcuField(index = 2, key = "coolant_temp", name = "Coolant", unit = "°C"),
            )
        )
        // Only one raw field arrives; spec declares two.
        val result = interpreter.interpret(block("920"), model)
        val missing = result.measurements[1]
        assertEquals("N/A", missing.displayValue)
        assertEquals(MeasurementStatus.UNAVAILABLE, missing.status)
        assertNull(missing.value)
    }

    @Test
    fun `thresholds drive status`() {
        val thresholds = Thresholds(
            normal = ThresholdRange(min = 80.0, max = 100.0),
            warning = ThresholdRange(min = 70.0, max = 105.0),
            critical = ThresholdRange(min = 110.0),
        )
        val model = model(
            listOf(
                EcuField(
                    index = 1, key = "coolant_temp", name = "Coolant", unit = "°C",
                    thresholds = thresholds,
                )
            )
        )
        assertEquals(
            MeasurementStatus.NORMAL,
            interpreter.interpret(block("88"), model).measurements[0].status,
        )
        assertEquals(
            MeasurementStatus.WARNING,
            interpreter.interpret(block("104"), model).measurements[0].status,
        )
        assertEquals(
            MeasurementStatus.CRITICAL,
            interpreter.interpret(block("112"), model).measurements[0].status,
        )
    }

    @Test
    fun `no thresholds means unknown status`() {
        val model = model(listOf(EcuField(index = 1, key = "rpm", name = "RPM", unit = "rpm")))
        assertEquals(
            MeasurementStatus.UNKNOWN,
            interpreter.interpret(block("920"), model).measurements[0].status,
        )
    }

    @Test
    fun `text raw value keeps raw string`() {
        val model = model(listOf(EcuField(index = 1, key = "idle_state", name = "Idle")))
        val m = interpreter.interpret(block("Idle"), model).measurements[0]
        assertEquals("Idle", m.displayValue)
        assertEquals("Idle", m.rawString)
    }

    @Test
    fun `field without spec still shows raw value`() {
        val model = model(emptyList())
        val m = interpreter.interpret(block("42"), model).measurements[0]
        assertEquals("42", m.displayValue)
        assertEquals("raw_001_1", m.key)
    }
}
