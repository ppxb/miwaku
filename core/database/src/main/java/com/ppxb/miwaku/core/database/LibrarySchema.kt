package com.ppxb.miwaku.core.database

/**
 * Schema identity kept outside the Room annotation so plain JVM tests can read it.
 *
 * A version bump is only complete together with three other edits: a matching entry in
 * [AppMigrations.ALL], the regenerated `schemas/.../<version>.json`, and a migration test.
 */
object LibrarySchema {
    const val DATABASE_NAME = "library.db"

    /** v1 is the first SQLite schema; earlier builds kept the same data in JSON and prefs. */
    const val VERSION = 1
}
