package com.shk.dumbauthenticator

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class StorageHelper(context: Context) {

    data class Account(
        val label: String,
        val secret: String,
        val algorithm: String = "SHA1",
        val digits: Int = 6,
        val period: Int = 30
    )

    private val prefs: SharedPreferences
    private val secureRandom = SecureRandom()

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            "totp_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }


    fun savePin(rawPin: String) {
        val salt = ByteArray(SALT_LENGTH).also { secureRandom.nextBytes(it) }
        val saltString = Base64.encodeToString(salt, Base64.DEFAULT)

        val hashedPin = pbkdf2HashBytes(rawPin, salt)

        prefs.edit()
            .putString(KEY_PIN_SALT, saltString)
            .putString(KEY_PIN_HASH, Base64.encodeToString(hashedPin, Base64.DEFAULT))
            .apply()
    }

    fun verifyPin(inputPin: String): Boolean {
        val storedHashB64 = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val storedSaltB64 = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val storedHash = Base64.decode(storedHashB64, Base64.DEFAULT)
        val salt = Base64.decode(storedSaltB64, Base64.DEFAULT)

        val candidate = pbkdf2HashBytes(inputPin, salt)
        val ok = MessageDigest.isEqual(candidate, storedHash)
        if (ok) {
            prefs.edit()
                .putInt(KEY_PIN_ATTEMPTS, 0)
                .putLong(KEY_PIN_LOCKED_UNTIL, 0L)
                .apply()
        } else {
            val attempts = prefs.getInt(KEY_PIN_ATTEMPTS, 0) + 1
            val edit = prefs.edit().putInt(KEY_PIN_ATTEMPTS, attempts)
            if (attempts >= PIN_MAX_ATTEMPTS) {
                val overshoot = attempts - PIN_MAX_ATTEMPTS
                val lockMs = (PIN_BASE_LOCK_MS shl overshoot.coerceAtMost(6))
                    .coerceAtMost(PIN_MAX_LOCK_MS)
                edit.putLong(KEY_PIN_LOCKED_UNTIL, System.currentTimeMillis() + lockMs)
            }
            edit.apply()
        }
        return ok
    }

    fun remainingLockSeconds(): Long {
        val until = prefs.getLong(KEY_PIN_LOCKED_UNTIL, 0L)
        val diffMs = until - System.currentTimeMillis()
        return if (diffMs <= 0) 0L else (diffMs + 999) / 1000
    }

    fun hasPinSet(): Boolean = prefs.contains(KEY_PIN_HASH)

    private fun pbkdf2HashBytes(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }


    fun getAllAccounts(): List<Account> = loadRawList()

    private fun loadRawList(): MutableList<Account> {
        val json = prefs.getString(KEY_ACCOUNTS, "[]") ?: "[]"
        val array = JSONArray(json)
        val list = mutableListOf<Account>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(Account(
                label = obj.getString("label"),
                secret = obj.getString("secret"),
                algorithm = obj.optString("algorithm", "SHA1"),
                digits = obj.optInt("digits", 6),
                period = obj.optInt("period", 30)
            ))
        }
        return list
    }

    private fun saveRawList(list: List<Account>) {
        val array = JSONArray()
        list.forEach { account ->
            val obj = JSONObject()
            obj.put("label", account.label)
            obj.put("secret", account.secret)
            obj.put("algorithm", account.algorithm)
            obj.put("digits", account.digits)
            obj.put("period", account.period)
            array.put(obj)
        }
        prefs.edit().putString(KEY_ACCOUNTS, array.toString()).apply()
    }

    fun saveAccount(account: Account) {
        val list = loadRawList()
        list.removeAll { it.label.equals(account.label, ignoreCase = true) }
        list.add(account)
        saveRawList(list)
    }

    fun deleteAccount(label: String) {
        val list = loadRawList()
        list.removeAll { it.label.equals(label, ignoreCase = true) }
        saveRawList(list)
    }


    fun encryptBackup(jsonData: String, password: String): String {
        val salt = ByteArray(SALT_LENGTH).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(GCM_IV_LENGTH).also { secureRandom.nextBytes(it) }

        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = SecretKeySpec(factory.generateSecret(spec).encoded, "AES")

        val cipher = Cipher.getInstance(TRANSFORMATION_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        val encryptedText = cipher.doFinal(jsonData.toByteArray(Charsets.UTF_8))

        val payload = Base64.encodeToString(salt + iv + encryptedText, Base64.NO_WRAP)
        return MAGIC_ENC + payload
    }

    fun decryptBackup(encryptedData: String, password: String): String {
        val body = encryptedData
            .removePrefix(MAGIC_ENC)
            .removePrefix(MAGIC_ENC_TOTP_APP)
            .trim()
        val combined = Base64.decode(body, Base64.DEFAULT)

        val salt = combined.sliceArray(0 until SALT_LENGTH)
        val iv = combined.sliceArray(SALT_LENGTH until SALT_LENGTH + GCM_IV_LENGTH)
        val encryptedText = combined.sliceArray(SALT_LENGTH + GCM_IV_LENGTH until combined.size)

        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = SecretKeySpec(factory.generateSecret(spec).encoded, "AES")

        val cipher = Cipher.getInstance(TRANSFORMATION_GCM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        return String(cipher.doFinal(encryptedText), Charsets.UTF_8)
    }

    fun exportAsJson(): String {
        val array = JSONArray()
        getAllAccounts().forEach { account ->
            val obj = JSONObject()
            obj.put("label", account.label)
            obj.put("secret", account.secret)
            obj.put("algorithm", account.algorithm)
            obj.put("digits", account.digits)
            obj.put("period", account.period)
            array.put(obj)
        }
        return array.toString(2)
    }

    fun importFromJson(jsonString: String): Int {
        return try {
            val trimmed = jsonString.trim()
            val array: JSONArray = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val root = JSONObject(trimmed)
                    root.optJSONArray("entries") ?: return -1
                }
                else -> return -1
            }

            val currentAccounts = loadRawList()
            var count = 0

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val label = obj.optString("label", "")
                    .ifEmpty { obj.optString("name", "") }
                    .ifEmpty { "Imported" }
                    .trim()
                val secret = obj.optString("secret", "")
                    .ifEmpty { obj.optString("seed", "") }
                    .trim()

                if (secret.isNotEmpty()) {
                    var finalLabel = label
                    var collisionCounter = 1
                    while (currentAccounts.any { it.label.equals(finalLabel, ignoreCase = true) }) {
                        finalLabel = "$label ($collisionCounter)"
                        collisionCounter++
                    }

                    val newAccount = Account(
                        finalLabel, secret,
                        obj.optString("algorithm", "SHA1"),
                        obj.optInt("digits", 6),
                        obj.optInt("period", 30)
                    )

                    saveAccount(newAccount)
                    currentAccounts.add(newAccount)
                    count++
                }
            }
            count
        } catch (_: Exception) { -1 }
    }

    companion object {
        const val MAGIC_ENC = "DUMB1\n"
        const val MAGIC_ENC_TOTP_APP = "TOTP1\n"

        fun isEncryptedBackup(s: String): Boolean =
            s.startsWith(MAGIC_ENC) || s.startsWith(MAGIC_ENC_TOTP_APP)

        fun parseOtpAuthUri(raw: String): Account? {
            return try {
                if (!raw.startsWith("otpauth://totp/", ignoreCase = true)) return null
                val uri = URI(raw)
                val rawPath = uri.rawPath ?: return null
                val label = URLDecoder.decode(rawPath.removePrefix("/"), "UTF-8").trim()
                val query = uri.rawQuery ?: return null
                val params = query.split("&").mapNotNull { param ->
                    val pair = param.split("=", limit = 2)
                    if (pair.isEmpty() || pair[0].isEmpty()) return@mapNotNull null
                    val key = pair[0].lowercase()
                    val value = if (pair.size > 1)
                        try { URLDecoder.decode(pair[1], "UTF-8") } catch (_: Exception) { "" }
                    else ""
                    key to value
                }.toMap()

                val secret = params["secret"]?.replace(" ", "")?.uppercase() ?: return null
                if (label.isEmpty() || secret.isEmpty()) return null

                val algorithm = (params["algorithm"] ?: "SHA1").uppercase().let {
                    if (it in listOf("SHA1", "SHA256", "SHA512")) it else "SHA1"
                }
                Account(
                    label = label,
                    secret = secret,
                    algorithm = algorithm,
                    digits = params["digits"]?.toIntOrNull() ?: 6,
                    period = params["period"]?.toIntOrNull() ?: 30
                )
            } catch (_: Exception) {
                null
            }
        }

        private const val KEY_ACCOUNTS = "accounts_json"
        private const val KEY_PIN_HASH = "app_pin_hash"
        private const val KEY_PIN_SALT = "app_pin_salt"
        private const val KEY_PIN_ATTEMPTS = "app_pin_attempts"
        private const val KEY_PIN_LOCKED_UNTIL = "app_pin_locked_until"

        private const val PBKDF2_ITERATIONS = 100000
        private const val SALT_LENGTH = 16
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val TRANSFORMATION_GCM = "AES/GCM/NoPadding"

        private const val PIN_MAX_ATTEMPTS = 5
        private const val PIN_BASE_LOCK_MS = 30_000L
        private const val PIN_MAX_LOCK_MS = 30L * 60L * 1000L
    }
}
