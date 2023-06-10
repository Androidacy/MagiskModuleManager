package com.fox2code.mmm.utils.io

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import com.fox2code.mmm.MainApplication
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileInputStream
import com.topjohnwu.superuser.io.SuFileOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.io.FileUtils
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Objects
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** @noinspection ResultOfMethodCallIgnored
 */
enum class Files {
    ;

    companion object {
        private val is64bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()

        // stolen from https://stackoverflow.com/a/25005243
        @JvmStatic
        fun getFileName(context: Context, uri: Uri): String {
            var result: String? = null
            if (uri.scheme == "content") {
                context.contentResolver.query(uri, null, null, null, null).use { cursor ->
                    if (cursor != null && cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            result = cursor.getString(index)
                        }
                    }
                }
            }
            if (result == null) {
                result = uri.path
                val cut = Objects.requireNonNull<String?>(result).lastIndexOf('/')
                if (cut != -1) {
                    result = result!!.substring(cut + 1)
                }
            }
            return result!!
        }

        // based on https://stackoverflow.com/a/63018108
        @JvmStatic
        fun getFileSize(context: Context, uri: Uri): Long? {
            var result: Long? = null
            try {
                val scheme = uri.scheme
                if (scheme == "content") {
                    val returnCursor = context.contentResolver.query(uri, null, null, null, null)
                    val sizeIndex =
                        returnCursor?.getColumnIndex(OpenableColumns.SIZE)
                    returnCursor!!.moveToFirst()
                    val size = sizeIndex.let { it?.let { it1 -> returnCursor.getLong(it1) } }
                    returnCursor.close()
                    result = size
                }
                if (scheme == "file") {
                    result = File(Objects.requireNonNull(uri.path)).length()
                }
            } catch (e: Exception) {
                Timber.e(Log.getStackTraceString(e))
                return null
            }
            return result
        }

        @JvmStatic
        @Throws(IOException::class)
        fun write(file: File, bytes: ByteArray?) {
            // make the dir if necessary
            Objects.requireNonNull(file.parentFile).mkdirs()
            FileOutputStream(file).use { outputStream ->
                outputStream.write(bytes)
                outputStream.flush()
            }
        }

        @JvmStatic
        @Throws(IOException::class)
        fun read(file: File?): ByteArray {
            FileInputStream(file).use { inputStream -> return readAllBytes(inputStream) }
        }

        @JvmStatic
        @Throws(IOException::class)
        fun writeSU(file: File, bytes: ByteArray?) {
            // make the dir if necessary
            Objects.requireNonNull(file.parentFile).mkdirs()
            SuFileOutputStream.open(file).use { outputStream ->
                outputStream.write(bytes)
                outputStream.flush()
            }
        }

        @JvmStatic
        @Throws(IOException::class)
        fun readSU(file: File): ByteArray {
            if (file.isFile && file.canRead()) {
                try { // Read as app if su not required
                    return read(file)
                } catch (ignored: IOException) {
                }
            }
            SuFileInputStream.open(file).use { inputStream -> return readAllBytes(inputStream) }
        }

        @JvmStatic
        fun existsSU(file: File): Boolean {
            return file.exists() || SuFile(file.absolutePath).exists()
        }

        @JvmStatic
        @Throws(IOException::class)
        fun copy(inputStream: InputStream, outputStream: OutputStream) {
            var nRead: Int
            val data = ByteArray(16384)
            while (inputStream.read(data, 0, data.size).also { nRead = it } != -1) {
                outputStream.write(data, 0, nRead)
            }
            outputStream.flush()
        }

        @JvmStatic
        fun closeSilently(closeable: Closeable?) {
            try {
                closeable?.close()
            } catch (ignored: IOException) {
            }
        }

        @JvmStatic
        fun makeBuffer(capacity: Long): ByteArrayOutputStream {
            // Cap buffer to 1 Gib (or 512 Mib for 32bit) to avoid memory errors
            return makeBuffer(
                capacity.coerceAtMost((if (is64bit) 0x40000000 else 0x20000000).toLong()).toInt()
            )
        }

        private fun makeBuffer(capacity: Int): ByteArrayOutputStream {
            return object : ByteArrayOutputStream(0x20.coerceAtLeast(capacity)) {
                override fun toByteArray(): ByteArray {
                    return if (buf.size == count) buf else super.toByteArray()
                }
            }
        }

        @JvmStatic
        @Throws(IOException::class)
        fun readAllBytes(inputStream: InputStream): ByteArray {
            val buffer = makeBuffer(inputStream.available())
            copy(inputStream, buffer)
            return buffer.toByteArray()
        }

        @JvmStatic
        fun fixJavaZipHax(bytes: ByteArray) {
            if (bytes.size > 8 && bytes[0x6].toInt() == 0x0 && bytes[0x7].toInt() == 0x0 && bytes[0x8].toInt() == 0x8) bytes[0x7] =
                0x8 // Known hax to prevent java zip file read
        }

        @JvmStatic
        @Throws(IOException::class)
        fun patchModuleSimple(bytes: ByteArray, outputStream: OutputStream?) {
            fixJavaZipHax(bytes)
            patchModuleSimple(ByteArrayInputStream(bytes), outputStream)
        }

        @Throws(IOException::class)
        fun patchModuleSimple(inputStream: InputStream?, outputStream: OutputStream?) {
            val zipInputStream = ZipInputStream(inputStream)
            val zipOutputStream = ZipOutputStream(outputStream)
            var nRead: Int
            val data = ByteArray(16384)
            var zipEntry: ZipEntry
            while (zipInputStream.nextEntry.also { zipEntry = it } != null) {
                val name = zipEntry.name
                val i = name.indexOf('/', 1)
                if (i == -1) continue
                val newName = name.substring(i + 1)
                if (newName.startsWith(".git")) continue  // Skip metadata
                zipOutputStream.putNextEntry(ZipEntry(newName))
                while (zipInputStream.read(data, 0, data.size).also { nRead = it } != -1) {
                    zipOutputStream.write(data, 0, nRead)
                }
                zipOutputStream.flush()
                zipOutputStream.closeEntry()
                zipInputStream.closeEntry()
            }
            zipOutputStream.finish()
            zipOutputStream.flush()
            zipOutputStream.close()
            zipInputStream.close()
        }

        @JvmStatic
        fun fixSourceArchiveShit(rawModule: ByteArray?) {
            // unzip the module, check if it has just one folder within. if so, switch to the folder and zip up contents, and replace the original file with that
            try {
                val tempDir = File(MainApplication.INSTANCE!!.cacheDir, "temp")
                if (tempDir.exists()) {
                    FileUtils.deleteDirectory(tempDir)
                }
                if (!tempDir.mkdirs()) {
                    throw IOException("Unable to create temp dir")
                }
                val tempFile = File(tempDir, "module.zip")
                write(tempFile, rawModule)
                val tempUnzipDir = File(tempDir, "unzip")
                if (!tempUnzipDir.mkdirs()) {
                    throw IOException("Unable to create temp unzip dir")
                }
                // unzip
                Timber.d("Unzipping module to %s", tempUnzipDir.absolutePath)
                try {
                    ZipFile(tempFile).use { zipFile ->
                        var files = zipFile.entries
                        // check if there is only one folder in the top level
                        var folderCount = 0
                        while (files.hasMoreElements()) {
                            val entry = files.nextElement()
                            if (entry.isDirectory) {
                                folderCount++
                            }
                        }
                        if (folderCount == 1) {
                            files = zipFile.entries
                            while (files.hasMoreElements()) {
                                val entry = files.nextElement()
                                if (entry.isDirectory) {
                                    continue
                                }
                                val file = File(tempUnzipDir, entry.name)
                                if (!Objects.requireNonNull(file.parentFile).exists()) {
                                    if (!file.parentFile?.mkdirs()!!) {
                                        throw IOException("Unable to create parent dir")
                                    }
                                }
                                ZipArchiveOutputStream(file).use { zipArchiveOutputStream ->
                                    zipArchiveOutputStream.putArchiveEntry(entry)
                                    zipFile.getInputStream(entry).use { inputStream ->
                                        copy(
                                            inputStream,
                                            zipArchiveOutputStream
                                        )
                                    }
                                    zipArchiveOutputStream.closeArchiveEntry()
                                }
                            }
                            // zip up the contents of the folder but not the folder itself
                            val filesInFolder = Objects.requireNonNull(tempUnzipDir.listFiles())
                            // create a new zip file
                            try {
                                ZipArchiveOutputStream(FileOutputStream("new.zip")).use { archive ->
                                    for (files2 in filesInFolder) {
                                        // create a new ZipArchiveEntry and add it to the ZipArchiveOutputStream
                                        val entry = ZipArchiveEntry(files2, files2.name)
                                        archive.putArchiveEntry(entry)
                                        FileInputStream(files2).use { input ->
                                            copy(
                                                input,
                                                archive
                                            )
                                        }
                                        archive.closeArchiveEntry()
                                    }
                                }
                            } catch (e: IOException) {
                                Timber.e(e, "Unable to zip up module")
                            }
                        } else {
                            Timber.d("Module does not have a single folder in the top level, skipping")
                        }
                    }
                } catch (e: IOException) {
                    Timber.e(e, "Unable to unzip module")
                }
            } catch (e: IOException) {
                Timber.e(e, "Unable to create temp dir")
            }
        }
    }
}