package org.example.project

import org.jooq.DSLContext

internal fun DSLContext.fetchCountSql(sql: String, vararg binds: Any?): Int =
    fetchOne("SELECT COUNT(*) FROM ($sql) AS count_query", *binds)!!
        .get(0, Number::class.java)!!
        .toInt()
