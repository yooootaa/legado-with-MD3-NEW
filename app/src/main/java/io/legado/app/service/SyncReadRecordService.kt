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
import io.legado.app.data.entities.readRecord.ReadRecord
import io.legado.app.data.entities.readRecord.ReadRecordDetail
import io.legado.app.data.entities.readRecord.ReadRecordSession
import io.legado.app.help.AppWebDav
import io.legado.app.lib.webdav.WebDav
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.nio.charset.Charset

/**
 * 同步阅读历史服务
 * 支持将阅读记录同步到 WebDAV 和从 WebDAV 下载
 */
class SyncReadRecordService : BaseService() {

    companion object {
        const val SYNC_TYPE_UPLOAD = "upload"
        const val SYNC_TYPE_DOWNLOAD = "download"
        const val SYNC_TYPE_SYNC = "sync"
        const val SYNC_TYPE_BOOK = "book"

        fun syncReadRecordByBook(bookName: String, bookAuthor: String) {
            val context = appCtx
            val intent = Intent(context, SyncReadRecordService::class.java).apply {
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

    private val groupKey = "${appCtx.packageName}.syncReadRecord"
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
            toastOnUi(getString(R.string.read_history_sync_running))
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
                updateNotification("开始同步阅读历史...", 0)
                when (syncType) {
                    SYNC_TYPE_UPLOAD -> uploadAllReadRecords()
                    SYNC_TYPE_DOWNLOAD -> downloadAllReadRecords()
                    SYNC_TYPE_SYNC -> syncReadRecords()
                    SYNC_TYPE_BOOK -> syncReadRecordByBook()
                }
                updateNotification("同步完成", 100)
                toastOnUi(getString(R.string.read_history_sync_finish))
            } catch (e: Exception) {
                AppLog.put("同步阅读历史失败\n${e.localizedMessage}", e)
                toastOnUi(getString(R.string.read_history_sync_failed))
            } finally {
                stopSelf()
            }
        }
    }

    private fun stopSync() {
        syncJob?.cancel()
        notificationManager.cancel(NotificationId.SyncReadRecordService)
        stopSelf()
    }

    /**
     * 上传所有阅读记录到 WebDAV
     */
    private suspend fun uploadAllReadRecords() {
        val records = appDb.readRecordDao.all
        totalCount = records.size
        syncProgress = 0
        for (record in records) {
            if (!lifecycleScope.isActive) return
            uploadReadRecord(record)
            syncProgress++
            updateNotification("正在上传: ${record.bookName}", (syncProgress * 100) / totalCount)
        }
    }

    /**
     * 从 WebDAV 下载所有阅读记录
     */
    private suspend fun downloadAllReadRecords() {
        val authorization = AppWebDav.authorization ?: return
        val readRecordUrl = AppWebDav.getReadRecordUrl()
        WebDav(readRecordUrl, authorization).makeAsDir()
        val files = WebDav(readRecordUrl, authorization).listFiles()
        val jsonFiles = files.filter { it.displayName.endsWith(".json") && !it.displayName.contains("_details") && !it.displayName.contains("_sessions") }
        totalCount = jsonFiles.size
        syncProgress = 0
        for (file in jsonFiles) {
            if (!lifecycleScope.isActive) return
            downloadReadRecord(file.displayName)
            syncProgress++
            updateNotification("正在下载: ${file.displayName}", (syncProgress * 100) / totalCount)
        }
    }

    /**
     * 双向同步阅读记录
     */
    private suspend fun syncReadRecords() {
        val authorization = AppWebDav.authorization ?: return
        val readRecordUrl = AppWebDav.getReadRecordUrl()
        WebDav(readRecordUrl, authorization).makeAsDir()

        val localRecords = appDb.readRecordDao.all.associateBy { getRecordKey(it) }
        val remoteFiles = WebDav(readRecordUrl, authorization).listFiles()
        val jsonFiles = remoteFiles.filter { it.displayName.endsWith(".json") && !it.displayName.contains("_details") && !it.displayName.contains("_sessions") }

        totalCount = localRecords.size + jsonFiles.size
        syncProgress = 0

        // 上传本地新增或更新的记录
        for (entry in localRecords.entries) {
            if (!lifecycleScope.isActive) return
            val key = entry.key
            val record = entry.value
            val remoteFile = remoteFiles.find { it.displayName == "${key}.json" }
            if (remoteFile == null || record.lastRead > remoteFile.lastModify) {
                uploadReadRecord(record)
            }
            syncProgress++
            updateNotification("正在同步: ${record.bookName}", (syncProgress * 100) / totalCount)
        }

        // 下载远程新增或更新的记录
        for (file in jsonFiles) {
            if (!lifecycleScope.isActive) return
            val key = file.displayName.removeSuffix(".json")
            val localRecord = localRecords[key]
            if (localRecord == null || file.lastModify > localRecord.lastRead) {
                downloadReadRecord(file.displayName)
            }
            syncProgress++
            updateNotification("正在同步: ${file.displayName}", (syncProgress * 100) / totalCount)
        }
    }

    /**
     * 上传单条阅读记录
     */
    private suspend fun uploadReadRecord(record: ReadRecord) {
        val authorization = AppWebDav.authorization ?: return
        val readRecordUrl = AppWebDav.getReadRecordUrl()
        val key = getRecordKey(record)

        // 上传主记录
        val recordJson = GSON.toJson(record)
        WebDav("${readRecordUrl}${key}.json", authorization)
            .upload(recordJson.toByteArray(Charset.defaultCharset()), "application/json")

        // 上传详情记录
        val details = appDb.readRecordDao.getDetailsByBook(record.deviceId, record.bookName, record.bookAuthor)
        if (details.isNotEmpty()) {
            val detailsJson = GSON.toJson(details)
            WebDav("${readRecordUrl}${key}_details.json", authorization)
                .upload(detailsJson.toByteArray(Charset.defaultCharset()), "application/json")
        }

        // 上传会话记录
        val sessions = appDb.readRecordDao.getSessionsByBook(record.deviceId, record.bookName, record.bookAuthor)
        if (sessions.isNotEmpty()) {
            val sessionsJson = GSON.toJson(sessions)
            WebDav("${readRecordUrl}${key}_sessions.json", authorization)
                .upload(sessionsJson.toByteArray(Charset.defaultCharset()), "application/json")
        }
    }

    /**
     * 下载单条阅读记录
     */
    private suspend fun downloadReadRecord(fileName: String) {
        val authorization = AppWebDav.authorization ?: return
        val readRecordUrl = AppWebDav.getReadRecordUrl()
        val key = fileName.removeSuffix(".json")

        kotlin.runCatching {
            // 下载主记录
            val recordBytes = WebDav("${readRecordUrl}${key}.json", authorization).download()
            val recordJson = String(recordBytes)
            val record = GSON.fromJson(recordJson, ReadRecord::class.java)

            // 检查是否需要合并
            val existingRecord = appDb.readRecordDao.getReadRecord(record.deviceId, record.bookName, record.bookAuthor)
            if (existingRecord != null) {
                // 合并记录
                val mergedRecord = existingRecord.copy(
                    readTime = existingRecord.readTime + record.readTime,
                    lastRead = maxOf(existingRecord.lastRead, record.lastRead)
                )
                appDb.readRecordDao.update(mergedRecord)
            } else {
                appDb.readRecordDao.insert(record)
            }

            // 下载详情记录
            val detailsFile = "${key}_details.json"
            if (WebDav("${readRecordUrl}${detailsFile}", authorization).exists()) {
                val detailsBytes = WebDav("${readRecordUrl}${detailsFile}", authorization).download()
                val detailsJson = String(detailsBytes)
                val detailsType = object : com.google.gson.reflect.TypeToken<List<ReadRecordDetail>>() {}.type
                val detailList = GSON.fromJson<List<ReadRecordDetail>>(detailsJson, detailsType)
                for (detail in detailList) {
                    val existingDetail = appDb.readRecordDao.getDetail(
                        detail.deviceId, detail.bookName, detail.bookAuthor, detail.date
                    )
                    if (existingDetail != null) {
                        existingDetail.readTime += detail.readTime
                        existingDetail.readWords += detail.readWords
                        existingDetail.firstReadTime = minOf(existingDetail.firstReadTime, detail.firstReadTime)
                        existingDetail.lastReadTime = maxOf(existingDetail.lastReadTime, detail.lastReadTime)
                        appDb.readRecordDao.insertDetail(existingDetail)
                    } else {
                        appDb.readRecordDao.insertDetail(detail)
                    }
                }
            }

            // 下载会话记录
            val sessionsFile = "${key}_sessions.json"
            if (WebDav("${readRecordUrl}${sessionsFile}", authorization).exists()) {
                val sessionsBytes = WebDav("${readRecordUrl}${sessionsFile}", authorization).download()
                val sessionsJson = String(sessionsBytes)
                val sessionsType = object : com.google.gson.reflect.TypeToken<List<ReadRecordSession>>() {}.type
                val sessionList = GSON.fromJson<List<ReadRecordSession>>(sessionsJson, sessionsType)
                for (session in sessionList) {
                    appDb.readRecordDao.insertSession(session)
                }
            }
        }.onFailure {
            AppLog.put("下载阅读记录失败: $fileName\n${it.localizedMessage}", it)
        }
    }

    /**
     * 获取记录唯一标识
     */
    private fun getRecordKey(record: ReadRecord): String {
        return "${record.bookName}_${record.bookAuthor}".replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5]"), "_")
    }

    override fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_sync)
            .setSubText(getString(R.string.sync_read_record))
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setOngoing(true)
            .build()
        startForeground(NotificationId.SyncReadRecordService, notification)
    }

    private fun updateNotification(content: String, progress: Int) {
        val notification = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_sync)
            .setSubText(getString(R.string.sync_read_record))
            .setContentText(content)
            .setProgress(100, progress, false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(groupKey)
            .setOngoing(true)
            .build()
        notificationManager.notify(NotificationId.SyncReadRecordService, notification)
    }

    /**
     * 同步指定书籍的阅读记录
     */
    private suspend fun syncReadRecordByBook() {
        val authorization = AppWebDav.authorization ?: return
        val readRecordUrl = AppWebDav.getReadRecordUrl()
        WebDav(readRecordUrl, authorization).makeAsDir()

        val key = "${bookName}_${bookAuthor}".replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5]"), "_")
        val remoteFiles = WebDav(readRecordUrl, authorization).listFiles()
        val remoteFile = remoteFiles.find { it.displayName == "${key}.json" }

        totalCount = 2
        syncProgress = 0

        // 获取本地所有设备的记录
        val localRecords = appDb.readRecordDao.all.filter { it.bookName == bookName && it.bookAuthor == bookAuthor }
        
        // 上传本地记录（如果有）
        if (localRecords.isNotEmpty()) {
            val localRecord = localRecords.first()
            if (remoteFile == null || localRecord.lastRead > remoteFile.lastModify) {
                uploadReadRecord(localRecord)
            }
            syncProgress++
            updateNotification("正在同步: ${localRecord.bookName}", (syncProgress * 100) / totalCount)
        }

        // 下载远程记录（如果有）
        if (remoteFile != null) {
            val hasLocalRecord = localRecords.isNotEmpty() && localRecords.any { remoteFile.lastModify <= it.lastRead }
            if (!hasLocalRecord) {
                downloadReadRecord("${key}.json")
            }
            syncProgress++
            updateNotification("正在同步: ${remoteFile.displayName}", (syncProgress * 100) / totalCount)
        }
    }

}