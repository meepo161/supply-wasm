package org.example.project

import org.example.project.ui.main.favoriteContentDescription
import kotlin.test.Test
import kotlin.test.assertEquals

class MainPresentationTest {
    @Test
    fun favoriteActionDescribesTheResultForTheCurrentSection() {
        assertEquals("Добавить раздел в избранное", favoriteContentDescription(false))
        assertEquals("Убрать раздел из избранного", favoriteContentDescription(true))
    }
}
