package org.gotson.komga.infrastructure.datasource

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource
import java.nio.file.Path
import java.sql.Connection

/**
 * ShabbyFork: fork builds before shabbyfork.sqlite added `LIBRARY.SCAN_FS_CHANGE` as an ordinary
 * Komga migration, which stops stock Komga from starting on the database. The repair moves it out.
 */
class ShabbyforkLegacySchemaRepairTest {
  @TempDir
  lateinit var dir: Path

  private lateinit var main: HikariDataSource

  @BeforeEach
  fun setUp() {
    val shabbyfork = dir.resolve("shabbyfork.sqlite").toString()
    main =
      HikariDataSource(
        HikariConfig().apply {
          dataSource = SQLiteDataSource().apply { url = "jdbc:sqlite:${dir.resolve("database.sqlite")}" }
          maximumPoolSize = 1
          connectionInitSql = "ATTACH DATABASE '$shabbyfork' AS shabbyfork"
        },
      )
    main.connection.use {
      it.exec("CREATE TABLE shabbyfork.LIBRARY_SETTINGS (LIBRARY_ID varchar NOT NULL PRIMARY KEY, SCAN_FS_CHANGE boolean NOT NULL DEFAULT 0)")
      it.exec("CREATE TABLE flyway_schema_history (installed_rank int PRIMARY KEY, version varchar)")
      it.exec("INSERT INTO flyway_schema_history VALUES (1, '20200706141854'), (2, '20260924200000'), (3, '20261020111142')")
    }
  }

  @AfterEach
  fun tearDown() {
    main.close()
  }

  @Test
  fun `given a database from an older fork build when repairing then the option moves and stock history remains`() {
    main.connection.use {
      it.exec("CREATE TABLE LIBRARY (ID varchar PRIMARY KEY, NAME varchar, SCAN_FS_CHANGE boolean NOT NULL DEFAULT 0)")
      it.exec("INSERT INTO LIBRARY VALUES ('a', 'A', 1), ('b', 'B', 0)")
    }

    ShabbyforkLegacySchemaRepair(main).run()

    main.connection.use {
      assertThat(it.strings("SELECT LIBRARY_ID || '=' || SCAN_FS_CHANGE FROM shabbyfork.LIBRARY_SETTINGS")).containsExactlyInAnyOrder("a=1", "b=0")
      assertThat(it.strings("SELECT name FROM pragma_table_info('LIBRARY')")).containsExactly("ID", "NAME")
      assertThat(it.strings("SELECT version FROM flyway_schema_history")).containsExactly("20200706141854", "20261020111142")
      assertThat(it.strings("SELECT ID FROM LIBRARY")).containsExactlyInAnyOrder("a", "b")
    }
  }

  @Test
  fun `given a repaired database when repairing again then nothing changes`() {
    main.connection.use {
      it.exec("CREATE TABLE LIBRARY (ID varchar PRIMARY KEY, SCAN_FS_CHANGE boolean NOT NULL DEFAULT 0)")
      it.exec("INSERT INTO LIBRARY VALUES ('a', 1)")
    }
    ShabbyforkLegacySchemaRepair(main).run()
    main.connection.use { it.exec("UPDATE shabbyfork.LIBRARY_SETTINGS SET SCAN_FS_CHANGE = 0") }

    ShabbyforkLegacySchemaRepair(main).run()

    main.connection.use {
      assertThat(it.strings("SELECT LIBRARY_ID || '=' || SCAN_FS_CHANGE FROM shabbyfork.LIBRARY_SETTINGS")).containsExactly("a=0")
    }
  }

  @Test
  fun `given a history entry whose column is already gone when repairing then only the entry is removed`() {
    main.connection.use { it.exec("CREATE TABLE LIBRARY (ID varchar PRIMARY KEY)") }

    ShabbyforkLegacySchemaRepair(main).run()

    main.connection.use {
      assertThat(it.strings("SELECT version FROM flyway_schema_history")).doesNotContain("20260924200000")
    }
  }

  @Test
  fun `given a fresh database when repairing then nothing happens`() {
    main.connection.use { it.exec("DROP TABLE flyway_schema_history") }

    ShabbyforkLegacySchemaRepair(main).run()

    main.connection.use {
      assertThat(it.strings("SELECT name FROM sqlite_master WHERE type = 'table'")).isEmpty()
    }
  }

  private fun Connection.exec(sql: String) {
    createStatement().use { it.execute(sql) }
  }

  private fun Connection.strings(sql: String): List<String> =
    createStatement().use { statement ->
      statement.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
    }
}
