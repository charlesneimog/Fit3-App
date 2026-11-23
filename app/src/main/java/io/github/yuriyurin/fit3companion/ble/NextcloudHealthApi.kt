package io.github.yuriyurin.fit3companion.ble

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.yuriyurin.fit3companion.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class NextcloudHealthStatus {
    DISCONNECTED,
    CHECKING,
    CONNECTED,
    UPLOADING,
    UPLOADED,
    NO_SUPPORTED_DATA,
    AUTHENTICATION_FAILED,
    INVALID_SERVER,
    APP_UNAVAILABLE,
    NETWORK_ERROR,
    SERVER_ERROR,
}

data class NextcloudHealthState(
    val authenticated: Boolean = false,
    val serverUrl: String? = null,
    val username: String? = null,
    val busy: Boolean = false,
    val pendingUpload: Boolean = false,
    val lastUploadAt: Long? = null,
    val status: NextcloudHealthStatus = NextcloudHealthStatus.DISCONNECTED,
    val detail: String? = null,
)

internal data class NextcloudHealthWrite(
    val method: String,
    val path: String,
    val body: Map<String, Any>,
)

/**
 * HTTPS client for the public OCS API exposed by Nextcloud Health v3.
 *
 * Fit3 data is mapped only where Nextcloud has an equivalent metric: daily steps, pulse, and
 * oxygen saturation. The device-specific app password is encrypted with Android Keystore before
 * it is persisted, allowing background uploads without retaining the account password.
 */
class NextcloudHealthApi(context: Context) {
    companion object {
        const val API_PATH = "/ocs/v2.php/apps/health/api/v2"
        private const val PREFERENCES = "fit3_nextcloud_health"
        private const val KEY_ALIAS = "fit3_nextcloud_health_credentials_v1"
        private const val MAX_RESPONSE_CHARS = 128 * 1024
        private const val MAX_PENDING_CHARS = 256 * 1024
        private const val RETRY_INTERVAL_MILLIS = 5 * 60_000L
        private const val EARLIEST_MEASUREMENT_MILLIS = 946_684_800_000L

        internal fun normalizeServerUrl(value: String): String? {
            val raw = value.trim()
            if (raw.isEmpty() || raw.any { it.isISOControl() }) return null
            return runCatching {
                val uri = URI(raw)
                require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
                    uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                    (uri.port == -1 || uri.port in 1..65535))
                val path = uri.normalize().path.orEmpty().trimEnd('/')
                URI("https", null, uri.host.lowercase(), uri.port,
                    path.ifEmpty { null }, null, null).toASCIIString().trimEnd('/')
            }.getOrNull()
        }

        internal fun operationId(metricKey: String, measuredAt: Long): String {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest("Fit3-App\u0000$metricKey\u0000$measuredAt".toByteArray(Charsets.UTF_8))
                .copyOf(16)
            // Nextcloud Health currently accepts UUID v4 operation IDs. These version/variant bits
            // keep a deterministic ID standards-shaped so retries return the existing measurement.
            bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
            val buffer = ByteBuffer.wrap(bytes)
            return UUID(buffer.long, buffer.long).toString()
        }

        internal fun writes(
            snapshot: BleSnapshot,
            capturedAt: Long = System.currentTimeMillis(),
        ): List<NextcloudHealthWrite> = buildList {
            val health = snapshot.health
            val stepDate = health.stepDay?.let { day ->
                runCatching { LocalDate.ofEpochDay(day) }.getOrNull()
                    ?.takeIf { it.year in 2000..2100 }
            }
            health.steps?.takeIf { it in 0..250_000 }?.let { steps ->
                if (stepDate != null) add(NextcloudHealthWrite(
                    method = "PUT",
                    path = "$API_PATH/daily-values/steps/$stepDate",
                    body = linkedMapOf("numericValue" to steps, "unit" to "steps"),
                ))
            }
            fun measurement(metricKey: String, value: Int?, measuredAt: Long?, unit: String,
                            range: IntRange) {
                val at = measuredAt?.takeIf {
                    it in EARLIEST_MEASUREMENT_MILLIS..(capturedAt + 5 * 60_000L)
                } ?: return
                val number = value?.takeIf { it in range } ?: return
                add(NextcloudHealthWrite(
                    method = "POST",
                    path = "$API_PATH/measurements",
                    body = linkedMapOf(
                        "metricKey" to metricKey,
                        "numericValue" to number,
                        "unit" to unit,
                        "recordedAt" to Instant.ofEpochMilli(at).toString(),
                        "context" to "manual",
                        "source" to "mobile",
                        "operationId" to operationId(metricKey, at),
                    ),
                ))
            }
            measurement("pulse", health.heartRate, health.heartRateAt, "bpm", 25..250)
            measurement("oxygen_saturation", health.spo2, health.spo2At, "percent", 50..100)
        }
    }

    private data class Credentials(val serverUrl: String, val username: String, val appPassword: String)
    private data class EncryptedSecret(val cipherText: String, val iv: String)
    private data class Response(val code: Int, val body: String) {
        private fun json(): JSONObject? = runCatching { JSONObject(body) }.getOrNull()
        fun successfulOcs(): Boolean {
            if (code !in 200..299) return false
            val meta = json()?.optJSONObject("ocs")?.optJSONObject("meta") ?: return false
            val statusCode = meta.optInt("statuscode", -1)
            return meta.optString("status").equals("ok", ignoreCase = true) ||
                statusCode == 100 || statusCode in 200..299
        }
        fun detail(): String? {
            val json = json()
            return json?.optJSONObject("ocs")?.optJSONObject("meta")?.optString("message")
                ?.takeIf { it.isNotBlank() && !it.equals("OK", ignoreCase = true) }
                ?: json?.optString("detail")?.takeIf { it.isNotBlank() }
                ?: json?.optString("message")?.takeIf { it.isNotBlank() }
        }
    }

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun hasCredentials(): Boolean = credentials() != null

    fun localState(
        status: NextcloudHealthStatus? = null,
        busy: Boolean = false,
        detail: String? = null,
    ): NextcloudHealthState {
        val credential = credentials()
        val lastUpload = preferences.getLong("last_upload", -1L).takeIf { it >= 0 }
        return NextcloudHealthState(
            authenticated = credential != null,
            serverUrl = credential?.serverUrl ?: preferences.getString("server_url", null),
            username = credential?.username ?: preferences.getString("username", null),
            busy = busy,
            pendingUpload = preferences.contains("pending_upload"),
            lastUploadAt = lastUpload,
            status = status ?: when {
                credential == null -> NextcloudHealthStatus.DISCONNECTED
                lastUpload != null -> NextcloudHealthStatus.UPLOADED
                else -> NextcloudHealthStatus.CONNECTED
            },
            detail = detail,
        )
    }

    fun connect(serverUrl: String, username: String, appPassword: String): NextcloudHealthState {
        val normalized = normalizeServerUrl(serverUrl)
            ?: return attemptState(serverUrl.trim(), username.trim(), NextcloudHealthStatus.INVALID_SERVER)
        val cleanUsername = username.trim()
        val cleanPassword = appPassword.trim()
        preferences.edit().putString("server_url", normalized).putString("username", cleanUsername).apply()
        if (cleanUsername.isEmpty() || cleanUsername.length > 320 || ':' in cleanUsername ||
            cleanPassword.isEmpty() || cleanPassword.length > 2048) {
            return attemptState(normalized, cleanUsername, NextcloudHealthStatus.AUTHENTICATION_FAILED)
        }
        val candidate = Credentials(normalized, cleanUsername, cleanPassword)
        return try {
            val response = request(candidate, "$API_PATH/configuration", "GET", null)
            when {
                response.successfulOcs() -> {
                    val encrypted = encrypt(cleanPassword)
                    preferences.edit()
                        .putString("server_url", normalized)
                        .putString("username", cleanUsername)
                        .putString("secret", encrypted.cipherText)
                        .putString("secret_iv", encrypted.iv)
                        .apply()
                    localState(NextcloudHealthStatus.CONNECTED)
                }
                response.code in setOf(401, 403) -> attemptState(normalized, cleanUsername,
                    NextcloudHealthStatus.AUTHENTICATION_FAILED, response.detail())
                response.code == 404 -> attemptState(normalized, cleanUsername,
                    NextcloudHealthStatus.APP_UNAVAILABLE, response.detail())
                else -> attemptState(normalized, cleanUsername, NextcloudHealthStatus.SERVER_ERROR,
                    httpDetail(response))
            }
        } catch (error: IOException) {
            attemptState(normalized, cleanUsername, NextcloudHealthStatus.NETWORK_ERROR,
                error.safeMessage())
        } catch (error: Exception) {
            attemptState(normalized, cleanUsername, NextcloudHealthStatus.SERVER_ERROR,
                error.safeMessage())
        }
    }

    fun checkConnection(): NextcloudHealthState {
        val credential = credentials() ?: return localState()
        return try {
            val response = request(credential, "$API_PATH/configuration", "GET", null)
            when {
                response.successfulOcs() -> localState(NextcloudHealthStatus.CONNECTED)
                response.code in setOf(401, 403) -> {
                    clearSecret(clearPending = false)
                    localState(NextcloudHealthStatus.AUTHENTICATION_FAILED, detail = response.detail())
                }
                response.code == 404 -> localState(NextcloudHealthStatus.APP_UNAVAILABLE,
                    detail = response.detail())
                else -> localState(NextcloudHealthStatus.SERVER_ERROR, detail = httpDetail(response))
            }
        } catch (error: IOException) {
            localState(NextcloudHealthStatus.NETWORK_ERROR, detail = error.safeMessage())
        } catch (error: Exception) {
            localState(NextcloudHealthStatus.SERVER_ERROR, detail = error.safeMessage())
        }
    }

    fun disconnect(): NextcloudHealthState {
        clearSecret(clearPending = true)
        return localState(NextcloudHealthStatus.DISCONNECTED)
    }

    /** Queueing is synchronous so a newer Fit3 sync is not lost while a request is in flight. */
    fun queue(snapshot: BleSnapshot): NextcloudHealthState {
        if (!hasCredentials()) return localState(NextcloudHealthStatus.DISCONNECTED)
        val writes = writes(snapshot)
        if (writes.isEmpty()) return if (preferences.contains("pending_upload")) localState()
            else localState(NextcloudHealthStatus.NO_SUPPORTED_DATA)
        val encoded = encodeWrites(writes)
        require(encoded.length <= MAX_PENDING_CHARS) { "Nextcloud Health upload is too large" }
        preferences.edit().putString("pending_upload", encoded).apply()
        return localState()
    }

    fun uploadPending(): NextcloudHealthState {
        val credential = credentials() ?: return localState(NextcloudHealthStatus.DISCONNECTED)
        val encoded = preferences.getString("pending_upload", null)
            ?: return localState(NextcloudHealthStatus.NO_SUPPORTED_DATA)
        val pending = runCatching { decodeWrites(encoded) }.getOrElse { error ->
            preferences.edit().remove("pending_upload").apply()
            return localState(NextcloudHealthStatus.SERVER_ERROR, detail = error.safeMessage())
        }
        preferences.edit().putLong("last_attempt", System.currentTimeMillis()).apply()
        return try {
            for (write in pending) {
                val response = request(credential, write.path, write.method, bodyJson(write.body).toString())
                when {
                    response.successfulOcs() -> Unit
                    response.code in setOf(401, 403) -> {
                        clearSecret(clearPending = false)
                        return localState(NextcloudHealthStatus.AUTHENTICATION_FAILED,
                            detail = response.detail())
                    }
                    response.code == 404 -> return localState(NextcloudHealthStatus.APP_UNAVAILABLE,
                        detail = response.detail())
                    else -> return localState(NextcloudHealthStatus.SERVER_ERROR,
                        detail = httpDetail(response))
                }
            }
            val editor = preferences.edit().putLong("last_upload", System.currentTimeMillis())
            if (preferences.getString("pending_upload", null) == encoded) editor.remove("pending_upload")
            editor.apply()
            localState(NextcloudHealthStatus.UPLOADED)
        } catch (error: IOException) {
            localState(NextcloudHealthStatus.NETWORK_ERROR, detail = error.safeMessage())
        } catch (error: Exception) {
            localState(NextcloudHealthStatus.SERVER_ERROR, detail = error.safeMessage())
        }
    }

    fun retryPendingIfDue(now: Long = System.currentTimeMillis()): NextcloudHealthState {
        if (!hasCredentials() || !preferences.contains("pending_upload")) return localState()
        val lastAttempt = preferences.getLong("last_attempt", 0L)
        return if (now - lastAttempt >= RETRY_INTERVAL_MILLIS) uploadPending() else localState()
    }

    private fun attemptState(serverUrl: String, username: String, status: NextcloudHealthStatus,
                             detail: String? = null) = NextcloudHealthState(
        serverUrl = serverUrl.takeIf { it.isNotBlank() },
        username = username.takeIf { it.isNotBlank() },
        pendingUpload = preferences.contains("pending_upload"),
        lastUploadAt = preferences.getLong("last_upload", -1L).takeIf { it >= 0 },
        status = status,
        detail = detail,
    )

    private fun credentials(): Credentials? {
        return runCatching {
            val server = preferences.getString("server_url", null)?.let(::normalizeServerUrl)
                ?: return@runCatching null
            val username = preferences.getString("username", null)?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            val secret = preferences.getString("secret", null) ?: return@runCatching null
            val iv = preferences.getString("secret_iv", null) ?: return@runCatching null
            Credentials(server, username, decrypt(EncryptedSecret(secret, iv)))
        }.getOrNull()
    }

    private fun clearSecret(clearPending: Boolean) {
        preferences.edit().remove("secret").remove("secret_iv").apply {
            if (clearPending) remove("pending_upload").remove("last_attempt")
        }.apply()
    }

    private fun encrypt(value: String): EncryptedSecret {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return EncryptedSecret(
            Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8))),
            Base64.getEncoder().encodeToString(cipher.iv),
        )
    }

    private fun decrypt(secret: EncryptedSecret): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(),
            GCMParameterSpec(128, Base64.getDecoder().decode(secret.iv)))
        return String(cipher.doFinal(Base64.getDecoder().decode(secret.cipherText)), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }

    private fun encodeWrites(writes: List<NextcloudHealthWrite>): String = JSONObject().apply {
        put("version", 1)
        put("writes", JSONArray().apply {
            writes.forEach { write -> put(JSONObject().apply {
                put("method", write.method)
                put("path", write.path)
                put("body", bodyJson(write.body))
            }) }
        })
    }.toString()

    private fun decodeWrites(encoded: String): List<NextcloudHealthWrite> {
        require(encoded.length <= MAX_PENDING_CHARS)
        val root = JSONObject(encoded)
        require(root.optInt("version") == 1)
        val array = root.getJSONArray("writes")
        require(array.length() in 1..3)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val method = item.getString("method").also { require(it == "PUT" || it == "POST") }
            val path = item.getString("path").also {
                require(it.startsWith("$API_PATH/") && ".." !in it && "://" !in it &&
                    '?' !in it && '#' !in it)
            }
            val json = item.getJSONObject("body")
            val body = linkedMapOf<String, Any>()
            json.keys().forEach { key ->
                val value = json.get(key)
                require(value is String || value is Int || value is Long || value is Double)
                body[key] = value
            }
            NextcloudHealthWrite(method, path, body)
        }
    }

    private fun bodyJson(body: Map<String, Any>): JSONObject = JSONObject().apply {
        body.forEach { (key, value) -> put(key, value) }
    }

    @Throws(IOException::class)
    private fun request(credentials: Credentials, path: String, method: String, body: String?): Response {
        require(path.startsWith("$API_PATH/") || path == "$API_PATH/configuration")
        val connection = (URL(credentials.serverUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("OCS-APIRequest", "true")
            setRequestProperty("User-Agent", "Fit3-App/${BuildConfig.VERSION_NAME}")
            val basic = Base64.getEncoder().encodeToString(
                "${credentials.username}:${credentials.appPassword}".toByteArray(Charsets.UTF_8))
            setRequestProperty("Authorization", "Basic $basic")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val bytes = body.toByteArray(Charsets.UTF_8)
                setFixedLengthStreamingMode(bytes.size)
                outputStream.use { it.write(bytes) }
            }
        }
        return try {
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    if (result.length + read > MAX_RESPONSE_CHARS)
                        throw IOException("Nextcloud response is too large")
                    result.append(buffer, 0, read)
                }
                result.toString()
            }.orEmpty()
            Response(code, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun httpDetail(response: Response): String = response.detail()?.let {
        "HTTP ${response.code}: $it"
    } ?: "HTTP ${response.code}"

    private fun Throwable.safeMessage(): String = message?.take(240) ?: javaClass.simpleName
}
