package com.drivealert.app.network

import com.drivealert.app.data.osm.OsmConfig
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** Public OpenStreetMap notes, used for community reports the user chose to publish. */
interface OsmNotesApi {
    /**
     * Creates a public note without an account name. Returns its id, or null when the
     * server accepted the note (2xx) but the id could not be read from the response.
     * Throws [HttpStatusException] for non-2xx and [IOException] for network failures.
     */
    suspend fun createNote(latitude: Double, longitude: Double, text: String): Long?

    /**
     * Finds an already-created note containing [token] (used to avoid re-posting after a lost response).
     * Returns null only when the search succeeded and found no such note. Throws [HttpStatusException]
     * for non-2xx and [IOException] for network failures or a response that cannot be read.
     */
    suspend fun findNoteByToken(token: String): Long?
}

/**
 * Free, key-less open-data services:
 * - the weekly OpenStreetMap camera snapshot (a static file; no location sent),
 * - OpenStreetMap notes for opted-in community reports.
 */
@Singleton
class OpenDataClient @Inject constructor(
    private val client: OkHttpClient,
) : OsmNotesApi {
    private val json = Json { ignoreUnknownKeys = true }

    /** Downloads the published India snapshot. The request carries no location or device data. */
    suspend fun fetchSnapshot(url: String = OsmConfig.snapshotUrl): String = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "Snapshot URL must be HTTPS" }
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            response.body?.string() ?: throw IOException("Empty snapshot")
        }
    }

    override suspend fun createNote(latitude: Double, longitude: Double, text: String): Long? = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("lat", String.format(Locale.US, "%.6f", latitude))
            .add("lon", String.format(Locale.US, "%.6f", longitude))
            .add("text", text)
            .build()
        val request = Request.Builder().url(OsmConfig.osmApiUrl + "api/0.6/notes.json").post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            // The note exists once the server answered 2xx; a body we cannot read must not cause a re-post.
            runCatching {
                json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject.noteId()
            }.getOrNull()
        }
    }

    override suspend fun findNoteByToken(token: String): Long? = withContext(Dispatchers.IO) {
        val url = (OsmConfig.osmApiUrl + "api/0.6/notes/search.json").toHttpUrl().newBuilder()
            .addQueryParameter("q", token)
            .addQueryParameter("closed", "-1")
            .addQueryParameter("limit", "5")
            .build()
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            val body = response.body?.string().orEmpty()
            // A body we cannot read says nothing about whether the note exists: fail, so the caller retries.
            runCatching {
                json.parseToJsonElement(body).jsonObject["features"]?.jsonArray.orEmpty()
                    .map { it.jsonObject }
                    .firstOrNull { feature ->
                        feature["properties"]?.jsonObject?.get("comments")?.jsonArray.orEmpty().any { comment ->
                            comment.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.contains(token) == true
                        }
                    }
                    ?.noteId()
            }.getOrElse { throw IOException("Unreadable note search response", it) }
        }
    }

    private fun JsonObject.noteId(): Long? = this["properties"]?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull
}
