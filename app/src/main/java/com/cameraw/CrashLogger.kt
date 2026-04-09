package com.cameraw

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class CrashLogger(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val logFile = File(context.getExternalFilesDir(null), "CameraW_Crash_$timeStamp.txt")

            PrintWriter(FileWriter(logFile)).use { writer ->
                writer.println("=== CAMERAW FATAL CRASH REPORT ===")
                writer.println("Time: $timeStamp")
                writer.println("Thread: ${thread.name}")
                writer.println("\n--- FATAL EXCEPTION ---")
                throwable.printStackTrace(writer)

                writer.println("\n--- LOGCAT DUMP ---")
                val process = Runtime.getRuntime().exec("logcat -d -t 500 -v threadtime")
                process.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        writer.println(line)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CrashLogger", "Failed to write crash log", e)
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun install(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(CrashLogger(context))
        }

        fun logHandledException(context: Context, tag: String, message: String, throwable: Throwable) {
            thread(name = "SoftCrashLogger") {
                try {
                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val fileName = "CameraW_SoftCrash_$timeStamp.txt"

                    val tempFile = File(context.cacheDir, fileName)

                    PrintWriter(FileWriter(tempFile)).use { writer ->
                        writer.println("=== CAMERAW SOFT CRASH REPORT ===")
                        writer.println("Time: $timeStamp")
                        writer.println("Tag: $tag")
                        writer.println("Message: $message")
                        writer.println("\n--- CAUGHT EXCEPTION ---")
                        throwable.printStackTrace(writer)

                        writer.println("\n--- LOGCAT DUMP ---")
                        val process = Runtime.getRuntime().exec("logcat -d -t 500 -v threadtime")
                        process.inputStream.bufferedReader().use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                writer.println(line)
                            }
                        }
                    }

                    val resolver = context.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/CameraW")
                        }
                    }

                    val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { out ->
                            tempFile.inputStream().use { input -> input.copyTo(out) }
                        }
                        Log.i("CrashLogger", "Soft crash saved to Documents/CameraW/$fileName")
                    } else {
                        Log.e("CrashLogger", "Failed to insert soft crash into MediaStore")
                    }

                    tempFile.delete()

                } catch (e: Exception) {
                    Log.e("CrashLogger", "Failed to write soft crash log", e)
                }
            }
        }
    }
}