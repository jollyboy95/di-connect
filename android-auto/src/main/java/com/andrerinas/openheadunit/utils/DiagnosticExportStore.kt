package com.andrerinas.openheadunit.utils

import android.content.Context
import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import androidx.core.content.FileProvider

/** Saves an app-owned report without depending on an OEM's document-picker activity. */
internal object DiagnosticExportStore {
    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveToDownloads(resolver: ContentResolver, fileName: String, report: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/DiAuto")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Downloads could not create the report")
        try {
            write(resolver, uri, report)
            val published = resolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
            if (published != 1) throw IOException("Downloads could not publish the report")
            return uri
        } catch (error: Exception) {
            // Only remove the entry created by this call; never leave a partial report behind.
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    /** Android 9/OEM fallback: no document picker and no storage permission required. */
    fun savePrivate(context: Context, fileName: String, report: String): Uri {
        val file = writePrivateReport(context, fileName, report)
        return FileProvider.getUriForFile(context, context.packageName + ".aa.fileprovider", file)
    }

    internal fun writePrivateReport(context: Context, fileName: String, report: String): File {
        val directory = File(context.cacheDir, "diagnostic-reports")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create report directory")
        val file = File(directory, fileName)
        try {
            file.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
        } catch (error: Exception) {
            file.delete()
            throw error
        }
        return file
    }

    fun write(resolver: ContentResolver, uri: Uri, report: String) {
        val stream = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("Report destination is unavailable")
        stream.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
    }
}
