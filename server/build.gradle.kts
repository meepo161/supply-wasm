import org.example.project.gradle.FlywayMigrateTask
import org.example.project.gradle.GenerateJooqTask

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
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
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverSerializationJson)
    implementation(libs.ktor.serverStatusPages)
    implementation(libs.ktor.serverCors)
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serverWebsockets)

    implementation(libs.postgres)
    implementation(libs.hikaricp)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)

    implementation(libs.jooq)
    jooqCodegen(libs.jooq.codegen)
    jooqCodegen(libs.jooq.meta)
    jooqCodegen(libs.postgres)

    implementation(libs.koin.ktor)
    implementation(libs.koin.logger)

    implementation(libs.bcrypt)

    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.ktor.clientWebsockets)
    testImplementation(libs.kotlin.testJunit)
}

val jooqTargetDir = layout.buildDirectory.dir("generated/jooq/src/main/kotlin")

kotlin {
    sourceSets.main {
        kotlin.srcDir(jooqTargetDir)
    }
}

sourceSets {
    main {
        java.srcDir(jooqTargetDir)
    }
}

tasks.named<JavaCompile>("compileJava").configure {
    dependsOn("generateJooq")
}

tasks.named("compileKotlin").configure {
    dependsOn(tasks.named("generateJooq"))
}

tasks.test {
    maxParallelForks = 1
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}

val erpDbUrl = providers.gradleProperty("erp.db.url").orElse("jdbc:postgresql://localhost:5433/erp")
val erpDbUser = providers.gradleProperty("erp.db.user").orElse("erp")
val erpDbPassword = providers.gradleProperty("erp.db.password").orElse("erp")

val migrationDir = layout.projectDirectory.dir("src/main/resources/db/migration")

tasks.register<FlywayMigrateTask>("flywayMigrate") {
    group = "database"
    description = "Apply Flyway migrations to the configured PostgreSQL database"
    url = erpDbUrl
    user = erpDbUser
    password = erpDbPassword
    locations = providers.gradleProperty("erp.db.locations")
        .orElse(providers.provider { "filesystem:${migrationDir.asFile.absolutePath}" })
    baselineOnMigrate = providers.gradleProperty("erp.db.baselineOnMigrate")
        .map(String::toBoolean)
        .orElse(true)
}

tasks.register<GenerateJooqTask>("generateJooq") {
    group = "jooq"
    description = "Generate jOOQ classes from the migrated PostgreSQL schema"
    dependsOn(tasks.named("flywayMigrate"))
    inputs.dir(migrationDir)

    dbUrl = erpDbUrl
    dbUser = erpDbUser
    dbPassword = erpDbPassword
    outputDir = jooqTargetDir
    toolClasspath = jooqCodegen
}
