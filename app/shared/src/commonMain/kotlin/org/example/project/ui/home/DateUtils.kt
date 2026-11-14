package org.example.project.ui.home

expect fun currentIsoDate(): String

fun String?.toIsoDate(): String? =
    this?.substringBefore("T")?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
