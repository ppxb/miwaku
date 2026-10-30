package com.ppxb.miwaku.core.database

import androidx.room.migration.Migration

/**
 * Ordered schema migrations that Room applies when the on-disk version is older than
 * [LibrarySchema.VERSION].
 *
 * There is nothing to migrate from yet: v1 is the first SQLite schema and the pre-SQLite data
 * lives in JSON/prefs, which `LegacyLibraryImport` reads once instead of migrating.
 *
 * The next schema change ships all four parts, or it does not ship:
 * 1. append `Migration(n, n + 1)` here and never edit an already released migration;
 * 2. bump [LibrarySchema.VERSION];
 * 3. commit the regenerated `schemas/.../<version>.json`;
 * 4. keep `chapter_progress`, `history` and `comics` intact — they are user data, not caches.
 *
 * [SchemaMigrationTest] fails when a version step has no migration or a migration targets a
 * version the schema does not declare, so a forgotten entry is caught by `testDebugUnitTest`
 * instead of by a user staring at the startup screen.
 */
object AppMigrations {
    val ALL: Array<Migration> = emptyArray()
}
