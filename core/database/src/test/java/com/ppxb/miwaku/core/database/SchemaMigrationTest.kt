package com.ppxb.miwaku.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the contract documented on [AppMigrations]: bumping [LibrarySchema.VERSION] without a
 * registered migration fails `testDebugUnitTest` instead of reaching a device.
 */
class SchemaMigrationTest {

    @Test
    fun migrationChainReachesCurrentVersion() {
        var version = 1
        while (version < LibrarySchema.VERSION) {
            val step =
                AppMigrations.ALL.singleOrNull {
                    it.startVersion == version && it.endVersion == version + 1
                }
            assertNotNull("缺少 $version -> ${version + 1} 的迁移，见 AppMigrations", step)
            version += 1
        }
        assertEquals(LibrarySchema.VERSION, version)
    }

    @Test
    fun noMigrationTargetsAVersionTheSchemaDoesNotDeclare() {
        val beyond =
            AppMigrations.ALL
                .filter { it.endVersion > LibrarySchema.VERSION }
                .map { "${it.startVersion}->${it.endVersion}" }
        assertTrue(
            "迁移目标版本超过 LibrarySchema.VERSION=${LibrarySchema.VERSION}: $beyond",
            beyond.isEmpty(),
        )
    }

    @Test
    fun noDuplicateMigrationForTheSameStep() {
        val duplicates =
            AppMigrations.ALL
                .groupBy { it.startVersion to it.endVersion }
                .filterValues { it.size > 1 }
                .keys
                .map { (from, to) -> "$from->$to" }
        assertTrue("同一步长注册了多条迁移: $duplicates", duplicates.isEmpty())
    }
}
