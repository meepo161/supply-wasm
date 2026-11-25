package org.example.project.dto

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupplierInnTest {
    @Test
    fun `accepts valid ten and twelve digit inn`() {
        assertTrue(isValidInn("7707083893"))
        assertTrue(isValidInn("6658017928"))
        assertTrue(isValidInn("500100732259"))
    }

    @Test
    fun `rejects wrong checksum wrong length and non digits`() {
        assertFalse(isValidInn("7707083894"))
        assertFalse(isValidInn("500100732250"))
        assertFalse(isValidInn("770708389"))
        assertFalse(isValidInn("77070838a3"))
        assertFalse(isValidInn(""))
    }
}
