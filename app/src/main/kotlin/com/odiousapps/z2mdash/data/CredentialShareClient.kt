package com.odiousapps.z2mdash.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Client for MX3Launcher's generic credential relay (credential_start/
 * status/view.php) in "pull" mode - this app has no credentials of its
 * own yet, so it asks to RECEIVE a broker's hostname/username/password,
 * the same short-code/QR/poll pattern MX3Launcher's own TV pairing
 * flow uses (see that project's SoundbarPairing.kt) to receive its own
 * pairing URL/secret. The logged-in account holder picks which of
 * their own saved credential presets to send, at
 * mx3launcher.odiousapps.com/credential_view.php - a preset meant for
 * this app must name its fields exactly "Hostname", "Username", and
 * "Password" (Username/Password only needed if the broker requires
 * auth), since this app looks those keys up by name once resolved.
 *
 * All functions here perform blocking network I/O - callers must run
 * them off the main thread (a coroutine on Dispatchers.IO).
 */
object CredentialShareClient {

    // mx3launcher.odiousapps.com is the same self-service site MX3Launcher's
    // TV-pairing flow uses - a separate domain from any individual user's
    // own home server, so no broker credentials ever touch that server
    // except in transit through this short-lived exchange.
    private const val START_URL = "https://mx3launcher.odiousapps.com/credential_start.php"
    private const val STATUS_URL = "https://mx3launcher.odiousapps.com/credential_status.php"
    private const val VIEW_URL_BASE = "https://mx3launcher.odiousapps.com/credential_view.php"

    private const val APP_NAME = "Z2M Dash"

    data class ImportSession(val code: String, val token: String, val expiresInSeconds: Int)

    sealed class StatusResult {
        data object Pending : StatusResult()
        data class Resolved(val fields: Map<String, String>) : StatusResult()
        data object Expired : StatusResult()
        data class Error(val message: String) : StatusResult()
    }

    /** The URL the QR code should encode - opening it prompts a login, then a preset picker. */
    fun viewUrl(code: String): String = "$VIEW_URL_BASE?code=${URLEncoder.encode(code, "UTF-8")}"

    fun startImport(label: String): ImportSession? {
        return try {
            // No "fields" - this is a pull-mode request, asking to
            // RECEIVE credentials rather than offering any of its own.
            val body = buildJsonObject {
                put("app", APP_NAME)
                put("label", label)
            }.toString()
            val response = httpPost(START_URL, body) ?: return null
            val json = Json.parseToJsonElement(response).jsonObject
            ImportSession(
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
                "viewed" -> {
                    val fields = json["fields"]?.jsonObject?.mapValues { (_, v) -> v.jsonPrimitive.contentOrNull ?: "" }
                        ?: emptyMap()
                    StatusResult.Resolved(fields)
                }
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
