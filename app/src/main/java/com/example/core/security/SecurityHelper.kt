package com.example.core.security

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

object SecurityHelper {

    fun hashSecret(secret: String, saltHex: String? = null): String {
        val salt = saltHex ?: generateSaltHex()
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$salt:$secret".toByteArray(Charsets.UTF_8))
        val hashHex = bytes.joinToString("") { "%02x".format(it) }
        return "$salt$$hashHex"
    }

    fun verifySecret(secret: String, storedHash: String): Boolean {
        val parts = storedHash.split('$')
        if (parts.size != 2) return false
        val expected = hashSecret(secret, parts[0])
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            storedHash.toByteArray(Charsets.UTF_8)
        )
    }

    private fun generateSaltHex(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Prevents directory traversal / Zip-Slip escapes outside a site's isolated root directory.
     */
    fun resolveSafeChild(rootDir: File, relativeOrUnsafePath: String): File? {
        val canonicalRoot = rootDir.canonicalFile
        val cleaned = relativeOrUnsafePath
            .replace('\\', '/')
            .trimStart('/')
        val candidate = File(canonicalRoot, cleaned).canonicalFile
        val rootPath = canonicalRoot.path
        val candPath = candidate.path
        return if (candPath == rootPath || candPath.startsWith("$rootPath${File.separator}")) {
            candidate
        } else {
            null
        }
    }

    fun sanitizeSlug(input: String): String {
        val slug = input.lowercase()
            .replace(Regex("[^a-z0-9_-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
        return if (slug.isBlank()) "site-${System.currentTimeMillis() % 10000}" else slug
    }
}
