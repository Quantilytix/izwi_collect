package com.quantilytix.izwi.network

import com.quantilytix.izwi.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class UploadResult(
    val pullRequestUrl: String,
    val batchPath: String,
    val clipCount: Int,
    val warnings: List<String>,
)

sealed class UploadOutcome {
    data class Success(val result: UploadResult) : UploadOutcome()
    data class Rejected(val message: String) : UploadOutcome()
    data class NetworkError(val message: String) : UploadOutcome()
}

/**
 * Talks only to the Smart-Q Voice Relay, never to Hugging Face directly.
 * Authenticates with the narrowly scoped relay key baked into BuildConfig
 * at build time (see app/build.gradle.kts) — this app never holds an HF
 * write token.
 */
class RelayClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    fun uploadBatch(zipFile: File): UploadOutcome {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                zipFile.name,
                zipFile.asRequestBody("application/zip".toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url("${BuildConfig.RELAY_BASE_URL}/v1/sessions/upload")
            .addHeader("X-Relay-Key", BuildConfig.RELAY_API_KEY)
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> {
                        val json = JSONObject(text)
                        val warnings = mutableListOf<String>()
                        json.optJSONArray("warnings")?.let { arr ->
                            for (i in 0 until arr.length()) warnings.add(arr.getString(i))
                        }
                        UploadOutcome.Success(
                            UploadResult(
                                pullRequestUrl = json.getString("pull_request_url"),
                                batchPath = json.getString("batch_path"),
                                clipCount = json.getInt("clip_count"),
                                warnings = warnings,
                            )
                        )
                    }
                    response.code == 422 -> UploadOutcome.Rejected(
                        runCatching { JSONObject(text).optString("detail", text) }.getOrDefault(text)
                    )
                    response.code == 401 -> UploadOutcome.Rejected("relay rejected the upload key")
                    else -> UploadOutcome.NetworkError("relay returned HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            UploadOutcome.NetworkError(e.message ?: "network error")
        }
    }
}
