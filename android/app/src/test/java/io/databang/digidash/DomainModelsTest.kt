package io.databang.digidash

import io.databang.digidash.domain.model.DashboardCardState
import io.databang.digidash.domain.model.EcuIdentity
import io.databang.digidash.domain.model.RawField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DomainModelsTest {

    @Test
    fun `part number normalization removes spaces hyphens dots and uppercases`() {
        assertEquals("037906024AG", EcuIdentity.normalizePartNumber("037 906 024 AG"))
        assertEquals("037906024AG", EcuIdentity.normalizePartNumber("037-906-024-ag"))
        assertEquals("037906024AG", EcuIdentity.normalizePartNumber("037.906.024.Ag"))
        assertEquals("037906024AG", EcuIdentity.normalizePartNumber("037906024AG"))
    }

    @Test
    fun `raw field exposes numeric view when parseable`() {
        assertEquals(920.0, RawField(1, "920").numeric!!, 0.0)
        assertEquals(13.9, RawField(1, "13.9").numeric!!, 0.0)
        assertNull(RawField(1, "Idle").numeric)
        assertNull(RawField(1, "").numeric)
    }

    @Test
    fun `unavailable card shows NA not zero`() {
        val card = DashboardCardState.unavailable("rpm", "Engine speed", "rpm")
        assertEquals("N/A", card.valueText)
    }
}
