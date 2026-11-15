package com.skbprofitracker.utils.data

import com.skbprofitracker.SkbMod
import com.skbprofitracker.events.EventBus
import com.skbprofitracker.events.models.GameClosedEvent
import com.skbprofitracker.utils.CommonUtils
import com.skbprofitracker.utils.FileUtils
import com.skbprofitracker.utils.gui.SkbGui
import com.skbprofitracker.utils.enums.Alignment
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.ConcurrentModificationException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object PersistentDataManager {
    private val configDir: File = FabricLoader.getInstance().configDir.toFile()
    private val skbprofitrackerConfigDir: File = File(configDir, "skbprofitracker")

    private const val DATA_FILE_NAME = "data.json"
    private const val OVERLAY_COORDS_FILE_NAME = "overlayCoordsData.json"
    private const val CONFIG_FILE_NAME = "config.jsonc"
    private const val BACKUP_DIR_NAME = "backup"
    private const val MAX_BACKUPS = 20

    private val backupFileNames = listOf(
        DATA_FILE_NAME,
        CONFIG_FILE_NAME,
        OVERLAY_COORDS_FILE_NAME
    )

    val backupDir: File = File(skbprofitrackerConfigDir, BACKUP_DIR_NAME) // MC/config/skbprofitracker/backup

    var skbprofitrackerData: SkbData = SkbData()
    private val skbprofitrackerDataFile: File = File(skbprofitrackerConfigDir, DATA_FILE_NAME)

    private var overlayCoordsData: MutableMap<String, OverlayCoordsData> = mutableMapOf()
    private val overlayCoordsFile: File = File(skbprofitrackerConfigDir, OVERLAY_COORDS_FILE_NAME)

    private val saveLock = Any()
    private val saveStateLock = Any() // For debouncing intensive file saves
    private val saveDebounceMs = 1000L
    private var isSkbDataSaveScheduled = false
    private var lastSkbDataSaveAtMs = 0L

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(Date::class.java, UtcDateTypeAdapter)
        .create()
        
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Skb-Data-Saver").apply {
            isDaemon = true
        }
    }
    
    fun init() {
        loadSkbDataFromFile()
        loadOverlayCoordsDataFromFile()
        EventBus.subscribe(GameClosedEvent::class, ::onGameClosed)
    }

    private fun onGameClosed(@Suppress("UNUSED_PARAMETER") event: GameClosedEvent) {
        saveAndBackupDataSync()
    }

    private fun saveAndBackupDataSync() {
        CommonUtils.runWithCatching("Failed to save and backup data on game close") {
            val startNanos = System.nanoTime()

            FileUtils.saveJsonToFileSync(overlayCoordsFile, overlayCoordsData, gson, saveLock, "Overlay coords")
            forceSaveSkbDataToFileSync()
            backupFiles()

            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
            val elapsedSec = elapsedMs / 1000.0
            SkbMod.LOGGER.info("[Skb] Save and backup data finished in ${elapsedMs}ms (${elapsedSec}s).")
        }
    }

    private fun backupFiles() {
        val filesToBackup = backupFileNames.map { File(skbprofitrackerConfigDir, it) }
        val missing = filesToBackup.filter { !it.exists() }
        if (missing.isNotEmpty()) {
            SkbMod.LOGGER.warn("[Skb] Not all config files exist to backup. Missing: ${missing.map { it.name }.joinToString(", ")}")
        }

        val backupDir = File(skbprofitrackerConfigDir, BACKUP_DIR_NAME)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd-HHmmss")
        val zipName = "skbprofitracker-backup-${dateFormat.format(Date())}.zip"
        val zipFile = File(backupDir, zipName)
        CommonUtils.runWithCatching("Failed to backup config files") {
            backupDir.mkdirs()
            FileOutputStream(zipFile).use { fileOut ->
                ZipOutputStream(fileOut).use { zipOut ->
                    for (file in filesToBackup) {
                        CommonUtils.runWithCatching("Failed to backup config file ${file.name}") {
                            ZipEntry(file.name).let { zipOut.putNextEntry(it) }
                            FileInputStream(file).use { it.copyTo(zipOut) }
                            zipOut.closeEntry()
                        }
                    }
                }
            }
            SkbMod.LOGGER.info("[Skb] Config backup succeeded: ${zipFile.absolutePath}")
            pruneOldBackups(backupDir)
        }
    }

    private fun pruneOldBackups(backupDir: File) {
        val pattern = Regex("^skbprofitracker-backup-\\d{4}-\\d{2}-\\d{2}-\\d{6}\\.zip$")
        val backups = backupDir.listFiles()?.filter { it.isFile && pattern.matches(it.name) } ?: return
        if (backups.size <= MAX_BACKUPS) return
        val sorted = backups.sortedByDescending { it.lastModified() }
        for (oldBackup in sorted.drop(MAX_BACKUPS)) {
            CommonUtils.runWithCatching("Failed to delete old backup ${oldBackup.name}") {
                if (oldBackup.delete()) {
                    SkbMod.LOGGER.info("[Skb] Removed old backup: ${oldBackup.name}")
                } else {
                    SkbMod.LOGGER.warn("[Skb] Could not delete old backup: ${oldBackup.absolutePath}")
                }
            }
        }
    }
    
    fun getOverlayCoordsData(key: String): OverlayCoordsData {
        return overlayCoordsData[key] ?: OverlayCoordsData()
    }
    
    fun updateOverlayCoordsData(key: String, x: Int, y: Int, scale: Float, alignment: Alignment) {
        synchronized(saveLock) {
            overlayCoordsData[key] = OverlayCoordsData(x, y, scale, alignment)
            saveOverlayCoordsDataToFileAsync()
        }
    }
    
    private fun loadOverlayCoordsDataFromFile() {
        val type = object : TypeToken<Map<String, OverlayCoordsData>>() {}.type
        val loaded: Map<String, OverlayCoordsData>? = FileUtils.loadJsonFromFile(overlayCoordsFile, type, gson, "Overlay coords")
        overlayCoordsData = loaded?.toMutableMap() ?: mutableMapOf()
        SkbGui.applyOverlayCoordsToAllGuis()

        if (loaded != null) {
            SkbMod.LOGGER.info("[Skb] Loaded ${overlayCoordsData.size} overlay coordinate entries")
        }
    }
    
    private fun loadSkbDataFromFile() {
        val type = object : TypeToken<SkbData>() {}.type
        val loaded: SkbData? = FileUtils.loadJsonFromFile(skbprofitrackerDataFile, type, gson, "Skb data")
        skbprofitrackerData = loaded ?: SkbData()
        if (loaded != null) {
            SkbMod.LOGGER.info("[Skb] Successfully loaded Skb data entries")
        }
    }

    private fun saveOverlayCoordsDataToFileAsync() {
        FileUtils.saveJsonToFileAsync(overlayCoordsFile, overlayCoordsData, gson, executor, saveLock, "Overlay coords")
    }

    /*
     * Schedules save of skbprofitrackerData to JSON file asynchronously.
     * Debounces saves to prevent too often file writes.
     */
    fun saveSkbDataToFileAsync() {
        synchronized(saveStateLock) {
            if (isSkbDataSaveScheduled) return

            val now = System.currentTimeMillis()
            val delayMs = (saveDebounceMs - (now - lastSkbDataSaveAtMs)).coerceAtLeast(0L)
            isSkbDataSaveScheduled = true
            
            executor.schedule({
                val json = serializeSkbDataToJson()
                if (json != null) {
                    FileUtils.saveJsonTextToFileAsync(skbprofitrackerDataFile, json, executor, saveLock, "Skb data")
                    synchronized(saveStateLock) {
                        isSkbDataSaveScheduled = false
                        lastSkbDataSaveAtMs = System.currentTimeMillis()
                    }
                } else {
                    SkbMod.LOGGER.error("[Skb] Failed to serialize Skb data, skipped saving.")
                    synchronized(saveStateLock) {
                        isSkbDataSaveScheduled = false
                    }
                }
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    /*
     * Forces immediate save of skbprofitrackerData to JSON file synchronously. Applicable for cases when we need to save data immediately, e.g. on game closed.
     * No debouncing is applied.
     */
    fun forceSaveSkbDataToFileSync() {
        val json = serializeSkbDataToJson()
        if (json != null) {
            FileUtils.saveJsonTextToFileSync(skbprofitrackerDataFile, json, saveLock, "Skb data")
        } else {
            SkbMod.LOGGER.error("[Skb] Failed to serialize Skb data, skipped force saving.")
        }
    }

    private fun serializeSkbDataToJson(): String? {
        return try {
            gson.toJson(skbprofitrackerData)
        } catch (ex: ConcurrentModificationException) {
            SkbMod.LOGGER.error("[Skb] ConcurrentModificationException occurred while serializing Skb data.", ex)
            null
        } catch (ex: Exception) {
            SkbMod.LOGGER.error("[Skb] Failed to serialize Skb data.", ex)
            null
        }
    }
}
