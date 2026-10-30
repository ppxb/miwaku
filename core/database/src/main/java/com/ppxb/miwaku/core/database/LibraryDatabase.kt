package com.ppxb.miwaku.core.database

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "comics")
data class ComicRecord(
    @PrimaryKey val id: String,
    val title: String = "",
    val author: String = "",
    val description: String = "",
    val tags: String = "[]",
    val coverUrl: String = "",
    val fetchedAt: Long = 0,
    val hasDetail: Boolean = false,
)

@Entity(tableName = "chapters", indices = [Index("albumId")])
data class ChapterRecord(
    @PrimaryKey val id: String,
    val albumId: String,
    val name: String = "",
    val sort: Int = 0,
    val imageHost: String = "",
    val hasManifest: Boolean = false,
)

@Entity(tableName = "pages", primaryKeys = ["chapterId", "index"])
data class PageRecord(val chapterId: String, val index: Int, val fileName: String, val splitCount: Int)

@Entity(tableName = "chapter_progress")
data class ProgressRecord(
    @PrimaryKey val chapterId: String,
    val pageIndex: Int? = null,
    val pageFilename: String? = null,
    val offsetFraction: Float = 0f,
    val lastReadAt: Long? = null,
    val readAt: Long? = null,
)

@Entity(tableName = "history")
data class HistoryRecord(
    @PrimaryKey val albumId: String,
    val chapterId: String? = null,
    val lastReadAt: Long? = null,
    val openedAt: Long? = null,
    val legacyOrder: Int = 0,
)

@Entity(tableName = "download_tasks", indices = [Index("albumId")])
data class TaskRecord(
    @PrimaryKey val chapterId: String,
    val albumId: String,
    val albumTitle: String,
    val chapterName: String,
    val chapterSort: Int = 0,
    val state: String = "Queued",
    val totalPages: Int = 0,
    val message: String? = null,
    val updatedAt: Long = 0,
    val queueOrder: Long = 0,
)

@Entity(tableName = "download_pages", primaryKeys = ["chapterId", "index"])
data class DownloadPageRecord(
    val chapterId: String,
    val index: Int,
    val fileName: String,
    val relativePath: String,
    val bytes: Long,
)

@Entity(tableName = "migration_state")
data class MigrationRecord(@PrimaryKey val name: String)

data class TaskWithCount(@Embedded val task: TaskRecord, val donePages: Int)

data class ResumeRecord(
    val albumId: String,
    val chapterId: String,
    val pageIndex: Int?,
    val pageFilename: String?,
    val offsetFraction: Float,
    val readAt: Long?,
)

@Dao
interface LibraryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun seedComic(record: ComicRecord)
    @Upsert fun putComic(record: ComicRecord)
    @Query("SELECT * FROM comics WHERE id = :id") fun comic(id: String): ComicRecord?
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun seedChapter(record: ChapterRecord)
    @Query("UPDATE chapters SET name = :name, sort = :sort WHERE id = :id")
    fun updateChapterSummary(id: String, name: String, sort: Int)
    @Query("UPDATE chapters SET imageHost = :host, hasManifest = 1 WHERE id = :id")
    fun setManifest(id: String, host: String)
    @Query("SELECT * FROM chapters WHERE id = :id") fun chapter(id: String): ChapterRecord?
    @Query("SELECT * FROM chapters WHERE albumId = :id ORDER BY sort, id") fun chapters(id: String): List<ChapterRecord>
    @Upsert fun putPages(pages: List<PageRecord>)
    @Query("DELETE FROM pages WHERE chapterId = :id") fun clearPages(id: String)
    @Query("SELECT * FROM pages WHERE chapterId = :id ORDER BY `index`") fun pages(id: String): List<PageRecord>
    @Upsert fun putProgress(record: ProgressRecord)
    @Query("SELECT * FROM chapter_progress WHERE chapterId = :id") fun progress(id: String): ProgressRecord?
    @Query("SELECT p.* FROM chapter_progress p JOIN chapters c ON c.id = p.chapterId WHERE c.albumId = :id")
    fun observeProgress(id: String): Flow<List<ProgressRecord>>
    @Query("SELECT p.* FROM chapter_progress p JOIN chapters c ON c.id = p.chapterId WHERE c.albumId = :id")
    fun progressForComic(id: String): List<ProgressRecord>
    @Upsert fun putHistory(record: HistoryRecord)
    @Query("SELECT * FROM history WHERE albumId = :id") fun history(id: String): HistoryRecord?
    @Query("SELECT h.albumId, h.chapterId, p.pageIndex, p.pageFilename, p.offsetFraction, p.readAt FROM history h JOIN chapter_progress p ON h.chapterId = p.chapterId WHERE h.albumId = :id")
    fun observeResume(id: String): Flow<ResumeRecord?>
    @Query("SELECT c.* FROM history h JOIN comics c ON h.albumId = c.id WHERE h.openedAt IS NOT NULL OR h.legacyOrder > 0 OR h.lastReadAt IS NOT NULL ORDER BY MAX(COALESCE(h.lastReadAt,0),COALESCE(h.openedAt,0)) DESC,h.legacyOrder DESC LIMIT 50")
    fun observeHistory(): Flow<List<ComicRecord>>
    @Query("UPDATE history SET openedAt = NULL, legacyOrder = 0, lastReadAt = NULL") fun clearHistory()
    @Query("SELECT * FROM migration_state WHERE name = :name") fun migration(name: String): MigrationRecord?
    @Insert fun migrated(record: MigrationRecord)
    @Upsert fun putTask(record: TaskRecord)
    @Query("SELECT * FROM download_tasks WHERE chapterId = :id") fun task(id: String): TaskRecord?
    @Query("SELECT t.*, (SELECT COUNT(*) FROM download_pages p WHERE p.chapterId = t.chapterId) AS donePages FROM download_tasks t ORDER BY queueOrder, chapterId")
    fun observeTasks(): Flow<List<TaskWithCount>>
    @Query("SELECT * FROM download_tasks ORDER BY queueOrder, chapterId") fun tasks(): List<TaskRecord>
    @Query("SELECT COALESCE(MAX(queueOrder), 0) FROM download_tasks") fun lastQueueOrder(): Long
    @Query("DELETE FROM download_pages WHERE chapterId = :id AND `index` = :index") fun removeDownloadedPage(id: String, index: Int)
    @Query("UPDATE download_tasks SET state = 'Queued' WHERE state = 'Running'") fun recoverRunning()
    @Query("SELECT c.* FROM chapters c LEFT JOIN chapter_progress p ON p.chapterId = c.id WHERE c.albumId = :id AND p.readAt IS NULL ORDER BY c.sort,c.id")
    fun unread(id: String): List<ChapterRecord>
    @Query("DELETE FROM download_tasks WHERE chapterId = :id") fun removeTask(id: String)
    @Upsert fun putDownloadedPage(record: DownloadPageRecord)
    @Query("SELECT * FROM download_pages WHERE chapterId = :id ORDER BY `index`") fun downloadedPages(id: String): List<DownloadPageRecord>
    @Query("DELETE FROM download_pages WHERE chapterId = :id") fun clearDownloadedPages(id: String)
    @Query("SELECT chapterId FROM download_tasks WHERE state = 'Completed'") fun observeDownloaded(): Flow<List<String>>
}

@Database(
    entities = [ComicRecord::class, ChapterRecord::class, PageRecord::class, ProgressRecord::class,
        HistoryRecord::class, TaskRecord::class, DownloadPageRecord::class, MigrationRecord::class],
    version = LibrarySchema.VERSION,
    exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao

    companion object {
        /**
         * No destructive fallback on purpose: a missing migration must fail loudly instead of
         * dropping reading progress. Schema changes are registered in [AppMigrations].
         */
        fun open(context: Context): LibraryDatabase =
            Room.databaseBuilder(context.applicationContext, LibraryDatabase::class.java, LibrarySchema.DATABASE_NAME)
                .addMigrations(*AppMigrations.ALL)
                .build()
    }
}
