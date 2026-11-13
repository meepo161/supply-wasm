import org.example.project.build.FlywayMigrateTask

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
}

group = "org.example.project"
version = "1.0.0"

application {
    mainClass = "org.example.project.ApplicationKt"
}

val jooqCodegen = configurations.create("jooqCodegen") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    api(project(":core"))

    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)

    implementation(libs.postgres)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)

    implementation(libs.jooq)
    jooqCodegen(libs.jooq.codegen)
    jooqCodegen(libs.jooq.meta)
    jooqCodegen(libs.postgres)

    implementation(libs.koin.ktor)
    implementation(libs.koin.logger)

    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
}

kotlin {
    sourceSets.main {
        kotlin.srcDir(layout.buildDirectory.dir("generated/jooq/src/main/kotlin"))
    }
}

val dbUrl = providers.gradleProperty("erp.db.url").orElse("jdbc:postgresql://localhost:5432/erp")
val dbUser = providers.gradleProperty("erp.db.user").orElse("erp")
val dbPassword = providers.gradleProperty("erp.db.password").orElse("erp")

 tasks.register<FlywayMigrateTask>("flywayMigrate") {
    group = "database"
    description = "Apply Flyway migrations to the configured PostgreSQL database"
    url = dbUrl
    user = dbUser
    password = dbPassword
    locations = providers.gradleProperty("erp.db.locations")
        .orElse("filesystem:src/main/resources/db/migration")
    baselineOnMigrate = providers.gradleProperty("erp.db.baselineOnMigrate")
        .map(String::toBoolean)
        .orElse(true)
}

tasks.register<JavaExec>("generateJooq") {
    group = "jooq"
    description = "Generate jOOQ classes from the migrated PostgreSQL schema"
    dependsOn(tasks.named("flywayMigrate"))

    classpath = jooqCodegen
    mainClass.set("org.jooq.codegen.GenerationTool")

    val configFile = layout.buildDirectory.file("jooq-config.xml").get().asFile
    args = listOf(configFile.absolutePath)

    doFirst {
        configFile.parentFile.mkdirs()
        configFile.writeText(
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <configuration xmlns="http://www.jooq.org/xsd/jooq-codegen-3.19.0.xsd">
              <jdbc>
                <driver>org.postgresql.Driver</driver>
                <url>${dbUrl.get()}</url>
                <user>${dbUser.get()}</user>
                <password>${dbPassword.get()}</password>
              </jdbc>
              <generator>
                <database>
                  <name>org.jooq.meta.postgres.PostgresDatabase</name>
                  <includes>.*</includes>
                  <excludes>flyway_schema_history</excludes>
                  <inputSchema>public</inputSchema>
                </database>
                <target>
                  <packageName>org.example.project.generated.jooq</packageName>
                  <directory>${layout.buildDirectory.dir("generated/jooq/src/main/kotlin").get().asFile.absolutePath.replace("\\", "/")}</directory>
                </target>
              </generator>
            </configuration>
            """.trimIndent()
        )
    }
}
