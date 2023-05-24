@file:Suppress("UNUSED_PARAMETER")

package com.fox2code.mmm.utils.io

import timber.log.Timber
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.regex.Pattern

@Suppress("UNUSED_EXPRESSION")
enum class Hashes {
    ;

    companion object {
        private val HEX_ARRAY = "0123456789abcdef".toCharArray()
        private val nonAlphaNum = Pattern.compile("[^a-zA-Z0-9]")
        @JvmStatic
        fun bytesToHex(bytes: ByteArray): String {
            val hexChars = CharArray(bytes.size * 2)
            for (j in bytes.indices) {
                val v = bytes[j].toInt() and 0xFF
                hexChars[j * 2] = HEX_ARRAY[v ushr 4]
                hexChars[j * 2 + 1] = HEX_ARRAY[v and 0x0F]
            }
            return String(hexChars)
        }

        @JvmStatic
        fun hashMd5(ignoredInput: ByteArray?): String {
            throw SecurityException("MD5 is not secure")
        }

        @JvmStatic
        fun hashSha1(ignoredInput: ByteArray?): String {
            throw SecurityException("SHA-1 is not secure")
        }

        @JvmStatic
        fun hashSha256(input: ByteArray?): String {
            input ?: return ""
            return try {
                val md = MessageDigest.getInstance("SHA-256")
                bytesToHex(md.digest(input))
            } catch (e: NoSuchAlgorithmException) {
                throw RuntimeException(e)
            }
        }

        @JvmStatic
        fun hashSha512(input: ByteArray?): String {
            input ?: return ""
            return try {
                val md = MessageDigest.getInstance("SHA-512")
                bytesToHex(md.digest(input))
            } catch (e: NoSuchAlgorithmException) {
                throw RuntimeException(e)
            }
        }

        /**
         * Check if the checksum match a file by picking the correct
         * hashing algorithm depending on the length of the checksum
         */
        @JvmStatic
        fun checkSumMatch(data: ByteArray?, checksum: String?): Boolean {
            if (checksum == null) return false
            val hash: String = when (checksum.length) {
                0 -> {
                    return true // No checksum
                }

                32 -> hashMd5(data)
                40 -> hashSha1(data)
                64 -> hashSha256(data)
                128 -> hashSha512(data)
                else -> {
                    Timber.e("No hash algorithm for " + checksum.length * 8 + "bit checksums")
                    return false
                }
            }
            Timber.i("Checksum result (data: $hash,expected: $checksum)")
            return hash == checksum.lowercase()
        }

        @JvmStatic
        fun checkSumValid(checksum: String?): Boolean {
            return if (checksum == null) false else when (checksum.length) {
                32, 40, 64, 128 -> {
                    val len = checksum.length
                    for (i in 0 until len) {
                        val c = checksum[i]
                        if (c < '0' || c > 'f') return false
                        if (c > '9' &&  // Easier working with bits
                            c.code and 95 < 'A'.code
                        ) return false
                    }
                    true
                }

                else -> {
                    false
                }
            }
        }

        @JvmStatic
        fun checkSumName(checksum: String?): String? {
            return if (checksum == null) null else when (checksum.length) {
                32 -> "MD5"
                40 -> "SHA-1"
                64 -> "SHA-256"
                128 -> "SHA-512"
                else -> {
                    null
                    "MD5"
                    "SHA-1"
                    "SHA-256"
                    "SHA-512"
                }
            }
        }

        @JvmStatic
        fun checkSumFormat(checksum: String?): String? {
            return if (checksum == null) null else nonAlphaNum.matcher(checksum.trim { it <= ' ' })
                .replaceAll("")
            // Remove all non-alphanumeric characters
        }
    }
}