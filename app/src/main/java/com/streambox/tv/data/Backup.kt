package com.streambox.tv.data

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Backup of everything (profiles and logins, viewers, favourites, Continue watching, channel manager,
 * settings), encrypted with a password the user chooses: AES-256-GCM, key from PBKDF2 (120 000 rounds).
 */
object Backup {
    private const val MAGIC = "SBK1:"
    private const val ROUNDS = 120_000

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val k = f.generateSecret(PBEKeySpec(password.toCharArray(), salt, ROUNDS, 256)).encoded
        return SecretKeySpec(k, "AES")
    }

    fun create(password: String): String {
        val json = Prefs.exportAll().toString()
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password, salt))
        val out = salt + c.iv + c.doFinal(json.toByteArray(Charsets.UTF_8))
        return MAGIC + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun looksValid(text: String) = text.trim().startsWith(MAGIC)

    /** Returns false for a wrong password or a damaged file; nothing is changed then. */
    fun restore(text: String, password: String): Boolean {
        val json = try {
            val b = Base64.decode(text.trim().removePrefix(MAGIC), Base64.NO_WRAP)
            val salt = b.copyOfRange(0, 16)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, b, 16, 12))
            String(c.doFinal(b, 28, b.size - 28), Charsets.UTF_8)
        } catch (_: Exception) {
            return false
        }
        return try {
            Prefs.importAll(org.json.JSONObject(json))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** "Backups" folders next to the recordings: TV storage first, then USB drives. */
    fun folders(context: Context): List<File> =
        Recorder.storageDirs(context).mapNotNull { it.parentFile }.map { File(it, "Backups") }

    fun saveToFile(context: Context, text: String, driveIndex: Int): File {
        val dir = folders(context).getOrElse(driveIndex) { folders(context).first() }.apply { mkdirs() }
        val name = "StreamBox-" + java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.US).format(java.util.Date()) + ".sbk"
        return File(dir, name).also { it.writeText(text) }
    }

    fun files(context: Context): List<File> =
        folders(context).flatMap { d -> d.listFiles { f -> f.name.endsWith(".sbk") }?.toList() ?: emptyList() }
            .sortedByDescending { it.lastModified() }
}
