package org.example.project

import org.example.project.ui.main.AppRoute
import org.example.project.ui.main.NavigationDestination
import org.example.project.ui.main.parseAppRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppRouteTest {
    @Test
    fun parsesSectionWithAndWithoutHash() {
        assertEquals(AppRoute(NavigationDestination.WMS), parseAppRoute("#/wms"))
        assertEquals(AppRoute(NavigationDestination.WMS), parseAppRoute("/wms"))
        assertEquals(AppRoute(NavigationDestination.PURCHASE_ORDERS), parseAppRoute("#/purchase-orders"))
    }

    @Test
    fun parsesNomenclatureCardOnlyForWarehouse() {
        assertEquals(AppRoute(NavigationDestination.WMS, 5), parseAppRoute("#/wms/nomenclature/5"))
        assertEquals(AppRoute(NavigationDestination.TASKS), parseAppRoute("#/tasks/nomenclature/5"))
        assertEquals(AppRoute(NavigationDestination.WMS), parseAppRoute("#/wms/nomenclature/abc"))
        assertEquals(AppRoute(NavigationDestination.WMS), parseAppRoute("#/wms/nomenclature/0"))
    }

    @Test
    fun unknownOrEmptyRouteIsNull() {
        assertNull(parseAppRoute(""))
        assertNull(parseAppRoute("#"))
        assertNull(parseAppRoute("#/unknown"))
    }

    @Test
    fun routeRoundTripsThroughHash() {
        NavigationDestination.entries.forEach { destination ->
            val route = AppRoute(destination)
            assertEquals(route, parseAppRoute(route.toHash()))
        }
        val card = AppRoute(NavigationDestination.WMS, 42)
        assertEquals("/wms/nomenclature/42", card.toHash())
        assertEquals(card, parseAppRoute(card.toHash()))
    }
}
