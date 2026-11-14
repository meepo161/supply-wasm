package org.example.project.gradle

import org.flywaydb.core.Flyway
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

abstract class FlywayMigrateTask : DefaultTask() {

    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val user: Property<String>

    @get:Input
    abstract val password: Property<String>

    @get:Input
    abstract val locations: Property<String>

    @get:Input
    abstract val baselineOnMigrate: Property<Boolean>

    init {
        baselineOnMigrate.convention(true)
    }

    @TaskAction
    fun migrate() {
        val flyway = Flyway.configure()
            .dataSource(url.get(), user.get(), password.get())
            .locations(locations.get())
            .baselineOnMigrate(baselineOnMigrate.get())
            .load()
        flyway.migrate()
    }
}

abstract class GenerateJooqTask : DefaultTask() {

    @get:Input
    abstract val dbUrl: Property<String>

    @get:Input
    abstract val dbUser: Property<String>

    @get:Input
    abstract val dbPassword: Property<String>

    @get:Input
    abstract val targetPackage: Property<String>

    @get:Input
    abstract val inputSchema: Property<String>

    @get:Input
    abstract val excludes: Property<String>

    @get:Classpath
    abstract val toolClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    init {
        targetPackage.convention("org.example.project.generated.jooq")
        inputSchema.convention("public")
        excludes.convention("flyway_schema_history")
    }

    @TaskAction
    fun generate() {
        val configFile = File(temporaryDir, "jooq-config.xml")
        configFile.parentFile.mkdirs()

        val targetDir = outputDir.get().asFile.absolutePath.replace("\\", "/")

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
                  <excludes>${excludes.get()}</excludes>
                  <inputSchema>${inputSchema.get()}</inputSchema>
                </database>
                <target>
                  <packageName>${targetPackage.get()}</packageName>
                  <directory>$targetDir</directory>
                </target>
              </generator>
            </configuration>
            """.trimIndent()
        )

        execOperations.javaexec {
            classpath = toolClasspath
            mainClass.set("org.jooq.codegen.GenerationTool")
            args = listOf(configFile.absolutePath)
        }
    }
}
