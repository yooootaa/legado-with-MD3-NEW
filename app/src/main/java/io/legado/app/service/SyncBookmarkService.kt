package io.legado.app.service

import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.AppWebDav
import io.legado.app.lib.webdav.WebDav
import io.legado.app.utils.GSON
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.nio.charset.Charset

/**
 * 同步书签服务
 * 支持将书签同步到 WebDAV 和从 WebDAV 下载
 */
class SyncBookmarkService : BaseService() {

    companion object {
        const val SYNC_TYPE_UPLOAD = "upload"
        const val SYNC_TYPE_DOWNLOAD = "download"
        const val SYNC_TYPE_SYNC = "sync"
        const val SYNC_TYPE_BOOK = "book"

        /**
         * 上传单个书签到服务器
         * @param bookmark 要上传的书签
         * @param autoSync 是否是自动同步（不显示通知）
         */
        fun uploadSingleBookmark(bookmark: Bookmark, autoSync: Boolean = false) {
            if (!AppConfig.autoSyncBookmark && autoSync) {
                return
            }
            GlobalScope.launch(IO) {
                try {
                    if (!AppWebDav.isOk || !NetworkUtils.isAvailable()) {
                        return@launch
                    }
                    val authorization = AppWebDav.authorization ?: return@launch
                    val bookmarkUrl = AppWebDav.getBookmarkUrl()
                    val key = getBookmarkKey(bookmark)

                    val bookmarkJson = GSON.toJson(bookmark)
                    WebDav("${bookmarkUrl}${key}.json", authorization)
                        .upload(bookmarkJson.toByteArray(Charset.defaultCharset()), "application/json")
                } catch (e: Exception) {
                    AppLog.put("上传书签失败\n${e.localizedMessage}", e)
                }
            }
        }

        /**
         * 从服务器删除书签
         * @param bookmark 要删除的书签
         * @param autoSync 是否是自动同步（不显示通知）
         */
        fun deleteSingleBookmark(bookmark: Bookmark, autoSync: Boolean = false) {
            if (!AppConfig.autoSyncBookmark && autoSync) {
                return
            }
            GlobalScope.launch(IO) {
                try {
                    if (!AppWebDav.isOk || !NetworkUtils.isAvailable()) {
                        return@launch
                    }
                    val authorization = AppWebDav.authorization ?: return@launch
                    val bookmarkUrl = AppWebDav.getBookmarkUrl()
                    val key = getBookmarkKey(bookmark)

                    WebDav("${bookmarkUrl}${key}.json", authorization).delete()
                } catch (e: Exception) {
                    AppLog.put("删除服务器书签失败\n${e.localizedMessage}", e)
                }
            }
        }

        /**
         * 获取书签唯一标识
         */
        internal fun getBookmarkKey(bookmark: Bookmark): String {
            return "${bookmark.bookName}_${bookmark.bookAuthor}_${bookmark.chapterIndex}_${bookmark.chapterPos}".replace(
                Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5]"), "_"
            )
        }

        /**
         * 同步指定书籍的书签
         */
        fun syncBookmarksByBook(bookName: String, bookAuthor: String) {
            val context = appCtx
            val intent = Intent(context, SyncBookmarkService::class.java).apply {
                action = IntentAction.start
                putExtra("syncType", SYNC_TYPE_BOOK)
                putExtra("bookName", bookName)
                putExtra("bookAuthor", bookAuthor)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }

    private lateinit var bookName: String
    private lateinit var bookAuthor: String

    private val groupKey = "${appCtx.packageName}.syncBookmark"
    private var syncJob: Job? = null
    private var syncType = SYNC_TYPE_SYNC
    private var syncProgress = 0
    private var totalCount = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> {
                syncType = intent.getStringExtra("syncType") ?: SYNC_TYPE_SYNC
                if (syncType == SYNC_TYPE_BOOK) {
                    bookName = intent.getStringExtra("bookName") ?: ""
                    bookAuthor = intent.getStringExtra("bookAuthor") ?: ""
                }
                startSync()
            }
            IntentAction.stop -> {
                stopSync()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startSync() {
        if (syncJob?.isActive == true) {
            toastOnUi(getString(R.string.sync_bookmark_running))
            return
        }
        syncJob = lifecycleScope.launch(IO) {
            try {
                if (!AppWebDav.isOk) {
                    toastOnUi(getString(R.string.webdav_not_configured))
                    stopSelf()
                    return@launch
                }
                if (!NetworkUtils.isAvailable()) {
                    toastOnUi(getString(R.string.network_unavailable))
                    stopSelf()
                    return@launch
                }
                updateNotification(getString(R.string.sync_bookmark_start), 0)
                when (syncType) {
                    SYNC_TYPE_UPLOAD -> uploadAllBookmarks()
                    SYNC_TYPE_DOWNLOAD -> downloadAllBookmarks()
                    SYNC_TYPE_SYNC -> syncBookmarks()
                    SYNC_TYPE_BOOK -> syncBookmarksByBook()
                }
                // getString(R.string.sync_bookmark_s)
                updateNotification(getString(R.string.sync_bookmark_t), 100)
                toastOnUi(getString(R.string.sync_bookmark_finish))
            } catch (e: Exception) {
                AppLog.put("同步书签失败\n${e.localizedMessage}", e)
                toastOnUi(getString(R.string.sync_bookmark_failed)+": ${e.localizedMessage}")
            } finally {
                stopSelf()
            }
        }
    }

    private fun stopSync() {
        syncJob?.cancel()
        notificationManager.cancel(NotificationId.SyncBookmarkService)
        stopSelf()
    }

    /**
     * 上传所有书签到 WebDAV
     */
    private suspend fun uploadAllBookmarks() {
        val bookmarks = appDb.bookmarkDao.all
        totalCount = bookmarks.size
        syncProgress = 0
        for (bookmark in bookmarks) {
            if (!lifecycleScope.isActive) return
            uploadBookmark(bookmark)
            syncProgress++
            updateNotification("正在上传: ${bookmark.bookName}", (syncProgress * 100) / totalCount)
        }
    }

    /**
     * 从 WebDAV 下载所有书签
     */
    private suspend fun downloadAllBookmarks() {
        val authorization = AppWebDav.authorization ?: return
        val bookmarkUrl = AppWebDav.getBookmarkUrl()
        WebDav(bookmarkUrl, authorization).makeAsDir()
        val files = WebDav(bookmarkUrl, authorization).listFiles()
        val jsonFiles = files.filter { it.displayName.endsWith(".json") }
        totalCount = jsonFiles.size
        syncProgress = 0
        for (file in jsonFiles) {
            if (!lifecycleScope.isActive) return
            downloadBookmarkFile(file.displayName)
            syncProgress++
            updateNotification("正在下载: ${file.displayName}", (syncProgress * 100) / totalCount)
        }
    }

    /**
     * 双向同步书签
     */
    private suspend fun syncBookmarks() {
        val authorization = AppWebDav.authorization ?: return
        val bookmarkUrl = AppWebDav.getBookmarkUrl()
        WebDav(bookmarkUrl, authorization).makeAsDir()

        val localBookmarks = appDb.bookmarkDao.all.associateBy { getBookmarkKey(it) }
        val remoteFiles = WebDav(bookmarkUrl, authorization).listFiles()
        val jsonFiles = remoteFiles.filter { it.displayName.endsWith(".json") }

        totalCount = localBookmarks.size + jsonFiles.size
        syncProgress = 0

        // 上传本地新增或更新的书签
        for (entry in localBookmarks.entries) {
            if (!lifecycleScope.isActive) return
            val key = entry.key
            val bookmark = entry.value
            val remoteFile = remoteFiles.find { it.displayName == "${key}.json" }
            if (remoteFile == null || bookmark.time > remoteFile.lastModify) {
                uploadBookmark(bookmark)
            }
            syncProgress++
            updateNotification("正在同步: ${bookmark.bookName}", (syncProgress * 100) / totalCount)
        }

        // 下载远程新增或更新的书签
        for (file in jsonFiles) {
            if (!lifecycleScope.isActive) return
            val key = file.displayName.removeSuffix(".json")
            val localBookmark = localBookmarks[key]
            if (localBookmark == null || file.lastModify > localBookmark.time) {
                downloadBookmarkFile(file.displayName)
            }
            syncProgress++
            updateNotification("正在同步: ${file.displayName}", (syncProgress * 100) / totalCount)
        }
    }

    /**
     * 上传单个书签
     */
    private suspend fun uploadBookmark(bookmark: Bookmark) {
        val authorization = AppWebDav.authorization ?: return
        val bookmarkUrl = AppWebDav.getBookmarkUrl()
        val key = getBookmarkKey(bookmark)

        val bookmarkJson = GSON.toJson(bookmark)
        WebDav("${bookmarkUrl}${key}.json", authorization)
            .upload(bookmarkJson.toByteArray(Charset.defaultCharset()), "application/json")
    }

    /**
     * 下载书签文件
     * 规则：用本地和服务器上的版本对比，哪个最新用哪个
     * - 如果本地没有书签，服务器有则覆盖本地（下载）
     * - 如果本地有书签，服务器没有则覆盖服务器版本（上传）
     */
    private suspend fun downloadBookmarkFile(fileName: String) {
        val authorization = AppWebDav.authorization ?: return
        val bookmarkUrl = AppWebDav.getBookmarkUrl()

        kotlin.runCatching {
            val bytes = WebDav("${bookmarkUrl}$fileName", authorization).download()
            val json = String(bytes)
            val bookmark = GSON.fromJson(json, Bookmark::class.java)

            val existingBookmark = appDb.bookmarkDao.all.find {
                it.bookName == bookmark.bookName &&
                it.bookAuthor == bookmark.bookAuthor &&
                it.chapterIndex == bookmark.chapterIndex &&
                it.chapterPos == bookmark.chapterPos
            }

            if (existingBookmark != null) {
                // 本地和服务器都有，用最新的版本覆盖
                if (bookmark.time >= existingBookmark.time) {
                    appDb.bookmarkDao.update(bookmark)
                } else {
                    // 本地版本更新，上传到服务器
                    uploadBookmark(existingBookmark)
                }
            } else {
                // 本地没有，服务器有，下载到本地
                appDb.bookmarkDao.insert(bookmark)
            }
        }.onFailure {
            AppLog.put("下载书签失败: $fileName\n${it.localizedMessage}", it)
        }
    }

    override fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_sync)
            .setSubText(getString(R.string.sync_bookmark))
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setOngoing(true)
            .build()
        startForeground(NotificationId.SyncBookmarkService, notification)
    }

    private fun updateNotification(content: String, progress: Int) {
        val notification = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_sync)
            .setSubText(getString(R.string.sync_bookmark))
            .setContentText(content)
            .setProgress(100, progress, false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(groupKey)
            .setOngoing(true)
            .build()
        notificationManager.notify(NotificationId.SyncBookmarkService, notification)
    }

    /**
     * 同步指定书籍的书签
     */
    private suspend fun syncBookmarksByBook() {
        val authorization = AppWebDav.authorization ?: return
        val bookmarkUrl = AppWebDav.getBookmarkUrl()
        WebDav(bookmarkUrl, authorization).makeAsDir()

        val localBookmarks = appDb.bookmarkDao.getByBook(bookName, bookAuthor)
        val remoteFiles = WebDav(bookmarkUrl, authorization).listFiles()
        val jsonFiles = remoteFiles.filter { it.displayName.endsWith(".json") }

        totalCount = localBookmarks.size + jsonFiles.size
        syncProgress = 0

        // 上传本地书签
        for (bookmark in localBookmarks) {
            if (!lifecycleScope.isActive) return
            val key = Companion.getBookmarkKey(bookmark)
            val remoteFile = remoteFiles.find { it.displayName == "${key}.json" }
            if (remoteFile == null || bookmark.time > remoteFile.lastModify) {
                uploadBookmark(bookmark)
            }
            syncProgress++
            updateNotification("正在同步: ${bookmark.bookName}", (syncProgress * 100) / totalCount)
        }

        // 下载远程书签（只下载当前书籍的）
        for (file in jsonFiles) {
            if (!lifecycleScope.isActive) return
            val key = file.displayName.removeSuffix(".json")
            // 检查是否是当前书籍的书签
            if (key.startsWith("${bookName}_${bookAuthor}_")) {
                val localBookmark = localBookmarks.find { Companion.getBookmarkKey(it) == key }
                if (localBookmark == null || file.lastModify > localBookmark.time) {
                    downloadBookmarkFile(file.displayName)
                }
            }
            syncProgress++
            updateNotification("正在同步: ${file.displayName}", (syncProgress * 100) / totalCount)
        }
    }

}
