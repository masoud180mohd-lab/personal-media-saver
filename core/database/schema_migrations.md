# Room Database Migration Guide for Personal Media Saver

This document outlines the strict protocol for managing SQLite schema migrations in `:core:database` without losing local download history or saved metadata.

---

## 1. Schema Versioning Protocol

1. When adding, removing, or altering fields on `DownloadEntity`, `StreamOptionEntity`, or `WhatsAppStatusEntity`:
   - Increment the `@Database(version = N)` number in `MediaSaverDatabase.kt`.
   - Never use `.fallbackToDestructiveMigration()` in release builds once users have stored download records.

2. Create an explicit migration object in a dedicated migrations package:
   ```kotlin
   package com.mediasaver.core.database.migration

   import androidx.room.migration.Migration
   import androidx.sqlite.db.SupportSQLiteDatabase

   val MIGRATION_1_2 = object : Migration(1, 2) {
       override fun migrate(db: SupportSQLiteDatabase) {
           // Example: Adding a custom tag column
           db.execSQL("ALTER TABLE downloads ADD COLUMN customTag TEXT DEFAULT NULL")
       }
   }
   ```

3. Register the migration in `DatabaseModule.kt`:
   ```kotlin
   Room.databaseBuilder(context, MediaSaverDatabase::class.java, MediaSaverDatabase.DATABASE_NAME)
       .addMigrations(MIGRATION_1_2)
       .build()
   ```

---

## 2. Automated Migration Testing

When evolving schema versions:
- Enable Room schema exporting in `build.gradle.kts` if validating historical schemas:
  ```kotlin
  ksp {
      arg("room.schemaLocation", "$projectDir/schemas")
  }
  ```
- Use `androidx.room:room-testing` with `MigrationTestHelper` to verify migration integrity before shipping APK updates.

---

## 3. DataStore Preferences Versioning

DataStore Preferences operates with key-value semantics. Schema changes are backwards-compatible by default:
- Never rename existing preference keys in `PreferencesKeys` unless migrating values explicitly.
- Provide sensible defaults using the `?:` operator in `SettingsRepositoryImpl` when reading newly introduced preference keys.
- To deprecate a setting, remove its writer function while retaining a fallback reader for existing devices.
