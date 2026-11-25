package org.example.project

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import org.example.project.dto.ApiError
import org.example.project.ui.main.InFlightRequest
import org.example.project.ui.userText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainShellLogicTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun concurrentReadsShareOneRequestAndLaterReadGoesToServerAgain() {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val request = InFlightRequest {
            calls++
            gate.await()
            calls
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val first = scope.async { request.get() }
        val second = scope.async { request.get() }
        val third = scope.async { request.get() }
        assertEquals(1, calls)
        gate.complete(Unit)
        assertEquals(listOf(1, 1, 1), listOf(first, second, third).map { it.getCompleted() })
        val later = scope.async { request.get() }
        assertEquals(2, calls)
        assertEquals(2, later.getCompleted())
    }

    @Test
    fun capacityAndQuantityErrorsAreInRussian() {
        val capacity = ApiError("VALIDATION_ERROR", "Capacity must be positive", mapOf("capacity" to "not_positive"))
        assertEquals("Исправьте: Ёмкость: должно быть больше нуля.", capacity.userText())
        val quantity = ApiError("VALIDATION_ERROR", "Quantity must be positive")
        assertEquals("Количество должно быть больше нуля.", quantity.userText())
    }

    @Test
    fun commonWarehouseAndSupplyMessagesAreTranslated() {
        val transfer = ApiError("CONFLICT", "Transfer can only be shipped from CREATED status")
        assertTrue(transfer.userText().contains("Отправить перемещение"))
        val demand = ApiError("CONFLICT", "Only DRAFT demand can be submitted")
        assertTrue(demand.userText().contains("черновик потребности"))
    }
}
