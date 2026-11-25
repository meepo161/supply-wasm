package org.example.project.ui.main

internal fun favoriteContentDescription(isFavorite: Boolean): String =
    if (isFavorite) "Убрать раздел из избранного" else "Добавить раздел в избранное"
