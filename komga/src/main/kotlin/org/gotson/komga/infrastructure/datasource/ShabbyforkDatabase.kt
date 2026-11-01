package org.gotson.komga.infrastructure.datasource

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import org.flywaydb.core.Flyway
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.sqlite.SQLiteDataSource
import java.sql.Connection
import javax.sql.DataSource

private val log = KotlinLogging.logger {}

/**
 * ShabbyFork: the fork keeps its own data in `shabbyfork.sqlite`, never in `database.sqlite`.
 *
 * Stock Komga of the same version must always be able to start on the same config dir, and it
 * cannot if `database.sqlite` carries anything it does not know: Flyway refuses to start on a
 * history entry it does not ship. So fork tables live in this second file, with migrations of
 * their own under `shabbyfork/migration/sqlite`, and the main connections ATTACH it as the
 * `shabbyfork` schema so fork data can still be joined to Komga's in one query. Stock never
 * attaches it, so to stock Komga the file simply does not exist.
 */
object ShabbyforkSchema {
  const val NAME = "shabbyfork"

  fun attachSql(database: KomgaProperties.Database) = "ATTACH DATABASE '${database.file.replace("'", "''")}' AS $NAME"
}

@Configuration
class ShabbyforkDatabaseConfiguration(
  private val komgaProperties: KomgaProperties,
) {
  /**
   * Used only to migrate the file. Everything else reaches fork tables through the main
   * connections, where the file is attached. A single connection also keeps a shared in-memory
   * database (the dev profile) alive for as long as the application runs.
   */
  @Bean("shabbyforkDataSource")
  fun shabbyforkDataSource(): DataSource {
    val database = komgaProperties.shabbyforkDb
    val dataSource =
      DataSourceBuilder
        .create()
        .driverClassName("org.sqlite.JDBC")
        .url("jdbc:sqlite:${database.file}")
        .type(SQLiteDataSource::class.java)
        .build()
    database.journalMode?.let { dataSource.setJournalMode(it.name) }
    database.busyTimeout?.let { dataSource.config.busyTimeout = it.toMillis().toInt() }

    return HikariDataSource(
      HikariConfig().apply {
        this.dataSource = dataSource
        this.poolName = "SqliteShabbyforkPool"
        this.maximumPoolSize = 1
      },
    )
  }

  /**
   * Runs instead of Spring Boot's plain `migrate()` of the main database: the fork file is
   * migrated first, then anything early fork builds left in `database.sqlite` is moved out,
   * which has to happen before Komga's own Flyway validates it.
   */
  @Bean
  fun shabbyforkFlywayMigrationStrategy(
    @Qualifier("shabbyforkDataSource") shabbyforkDataSource: DataSource,
  ) = FlywayMigrationStrategy { mainFlyway ->
    Flyway
      .configure()
      .locations("classpath:shabbyfork/migration/sqlite")
      .dataSource(shabbyforkDataSource)
      .load()
      .migrate()
    ShabbyforkLegacySchemaRepair(mainFlyway.configuration.dataSource).run()
    mainFlyway.migrate()
  }
}

/**
 * ShabbyFork: undoes what fork builds before `shabbyfork.sqlite` did to `database.sqlite`.
 *
 * Those builds added their schema changes as ordinary Komga migrations. Each one is moved here:
 * its data is copied into the fork file, its column dropped, and its Flyway history entry
 * removed, after which `database.sqlite` is exactly what stock Komga expects. A database that
 * never ran such a build, or was already repaired, is left alone.
 *
 * The copy runs before the main-file transaction, because a transaction does not span attached
 * files atomically in WAL mode. That is safe: the copy is idempotent, and nothing is removed
 * from `database.sqlite` until it has succeeded.
 */
class ShabbyforkLegacySchemaRepair(
  private val mainDataSource: DataSource,
) {
  private class LegacyMigration(
    val version: String,
    val table: String,
    val column: String,
    val copySql: String,
  )

  private val legacyMigrations =
    listOf(
      LegacyMigration(
        version = "20260924200000",
        table = "LIBRARY",
        column = "SCAN_FS_CHANGE",
        copySql =
          "INSERT OR REPLACE INTO ${ShabbyforkSchema.NAME}.LIBRARY_SETTINGS (LIBRARY_ID, SCAN_FS_CHANGE) " +
            "SELECT ID, SCAN_FS_CHANGE FROM main.LIBRARY",
      ),
    )

  fun run() {
    mainDataSource.connection.use { connection ->
      if (!connection.hasTable("flyway_schema_history")) return
      legacyMigrations
        .filter { connection.isApplied(it.version) }
        .forEach { connection.repair(it) }
    }
  }

  private fun Connection.repair(migration: LegacyMigration) {
    log.info { "Moving fork migration ${migration.version} out of database.sqlite, so stock Komga can run on it" }
    val hasColumn = hasColumn(migration.table, migration.column)
    if (hasColumn) createStatement().use { it.executeUpdate(migration.copySql) }

    val autoCommit = autoCommit
    this.autoCommit = false
    try {
      createStatement().use { statement ->
        if (hasColumn) statement.executeUpdate("ALTER TABLE main.${migration.table} DROP COLUMN ${migration.column}")
      }
      prepareStatement("DELETE FROM main.flyway_schema_history WHERE version = ?").use {
        it.setString(1, migration.version)
        it.executeUpdate()
      }
      commit()
    } catch (e: Exception) {
      rollback()
      throw e
    } finally {
      this.autoCommit = autoCommit
    }
  }

  private fun Connection.hasTable(table: String): Boolean =
    prepareStatement("SELECT 1 FROM main.sqlite_master WHERE type = 'table' AND name = ?").use {
      it.setString(1, table)
      it.executeQuery().use { rs -> rs.next() }
    }

  private fun Connection.hasColumn(
    table: String,
    column: String,
  ): Boolean =
    createStatement().use { statement ->
      statement.executeQuery("PRAGMA main.table_info($table)").use { rs ->
        generateSequence { if (rs.next()) rs.getString("name") else null }.any { it.equals(column, ignoreCase = true) }
      }
    }

  private fun Connection.isApplied(version: String): Boolean =
    prepareStatement("SELECT 1 FROM main.flyway_schema_history WHERE version = ?").use {
      it.setString(1, version)
      it.executeQuery().use { rs -> rs.next() }
    }
}
