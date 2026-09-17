package com.odiousapps.z2mdash.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Client for MX3Launcher's generic credential relay (credential_start/
 * status/view.php) - lets this app hand a broker's hostname/username/
 * password to another device/app via a short code and QR, the same
 * OAuth-device-flow-style short-code/poll pattern MX3Launcher's own TV
 * pairing already uses (see that project's SoundbarPairing.kt), but for
 * arbitrary key/value fields instead of a fixed url+secret pair.
 *
 * All functions here perform blocking network I/O - callers must run
 * them off the main thread (a coroutine on Dispatchers.IO).
 */
object CredentialShareClient {

    // mx3launcher.odiousapps.com is the same self-service site MX3Launcher's
    // TV-pairing flow uses - a separate domain from any individual user's
    // own home server, so no broker credentials ever touch that server.
    private const val START_URL = "https://mx3launcher.odiousapps.com/credential_start.php"
    private const val STATUS_URL = "https://mx3launcher.odiousapps.com/credential_status.php"
    private const val VIEW_URL_BASE = "https://mx3launcher.odiousapps.com/credential_view.php"

    data class ShareSession(val code: String, val token: String, val expiresInSeconds: Int)

    sealed class StatusResult {
        data object Pending : StatusResult()
        data object Viewed : StatusResult()
        data object Expired : StatusResult()
        data class Error(val message: String) : StatusResult()
    }

    /** The URL the QR code should encode - opening it prompts a login, then a one-time reveal. */
    fun viewUrl(code: String): String = "$VIEW_URL_BASE?code=${URLEncoder.encode(code, "UTF-8")}"

    fun startShare(app: String, label: String, fields: Map<String, String>): ShareSession? {
        return try {
            val body = buildJsonObject {
                put("app", app)
                put("label", label)
                putJsonObject("fields") {
                    fields.forEach { (key, value) -> put(key, value) }
                }
            }.toString()
            val response = httpPost(START_URL, body) ?: return null
            val json = Json.parseToJsonElement(response).jsonObject
            if (json["ok"]?.jsonPrimitive?.booleanOrNull != true) return null
            ShareSession(
                code = json["code"]?.jsonPrimitive?.contentOrNull ?: return null,
                token = json["token"]?.jsonPrimitive?.contentOrNull ?: return null,
                expiresInSeconds = json["expires_in"]?.jsonPrimitive?.intOrNull ?: 600
            )
        } catch (_: Exception) {
            null
        }
    }

    fun pollStatus(token: String): StatusResult {
        return try {
            val encodedToken = URLEncoder.encode(token, "UTF-8")
            val response = httpGet("$STATUS_URL?token=$encodedToken") ?: return StatusResult.Error("No response")
            val json = Json.parseToJsonElement(response).jsonObject
            when (json["status"]?.jsonPrimitive?.contentOrNull) {
                "viewed" -> StatusResult.Viewed
                "pending" -> StatusResult.Pending
                "expired" -> StatusResult.Expired
                else -> StatusResult.Error(json["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown error")
            }
        } catch (e: Exception) {
            StatusResult.Error(e.message ?: "Network error")
        }
    }

    private fun httpGet(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.requestMethod = "GET"
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun httpPost(url: String, body: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
