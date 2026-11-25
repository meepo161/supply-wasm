package org.example.project

import kotlin.random.Random

/** Случайный ИНН организации с верной контрольной цифрой: сервер отклоняет остальные. */
internal fun randomValidInn(): String {
    val weights = intArrayOf(2, 4, 10, 3, 5, 9, 4, 6, 8)
    val base = "77" + (1..7).joinToString("") { Random.nextInt(10).toString() }
    val check = base.indices.sumOf { (base[it] - '0') * weights[it] } % 11 % 10
    return base + check
}
