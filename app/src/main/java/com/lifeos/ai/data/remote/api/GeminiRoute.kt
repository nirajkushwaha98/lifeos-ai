package com.lifeos.ai.data.remote.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

data class HttpReply(val status: Int, val body: String, val retryAfter: String?)

/** One blocking HTTPS POST. Injectable so routes can be tested without a network. */
fun interface HttpTransport {
    fun post(url: String, headers: Map<String, String>, body: String, readTimeoutMs: Int): HttpReply
}

object UrlConnectionTransport : HttpTransport {
    override fun post(url: String, headers: Map<String, String>, body: String, readTimeoutMs: Int): HttpReply {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return HttpReply(status, text, connection.getHeaderField("retry-after"))
        } finally {
            connection.disconnect()
        }
    }
}

/** A Gemini model called directly over REST (debug builds only — see DirectAiService). */
class GeminiRoute(
    private val model: String,
    private val apiKey: String,
    private val transport: HttpTransport = UrlConnectionTransport,
) : AiRoute {

    override val id: String = model

    override suspend fun send(prompt: AiPrompt): RouteResult {
        val body = GeminiRequests.body(prompt, model).toString()
        val reply = try {
            runInterruptible(Dispatchers.IO) {
                transport.post(
                    url = "$BASE_URL/models/$model:generateContent",
                    // The key travels only in a header and is never logged.
                    headers = mapOf("x-goog-api-key" to apiKey, "Content-Type" to "application/json; charset=utf-8"),
                    body = body,
                    readTimeoutMs = if (prompt.quick) QUICK_READ_TIMEOUT_MS else READ_TIMEOUT_MS,
                )
            }
        } catch (e: SocketTimeoutException) {
            return RouteResult.Failure(AiError.TIMEOUT, FailureKind.TRANSIENT)
        } catch (e: IOException) {
            return RouteResult.Failure(AiError.NETWORK, FailureKind.TRANSIENT)
        }
        return GeminiRequests.classify(reply.status, reply.body, reply.retryAfter)
    }

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        // A model that hasn't answered a voice turn in 15 s is overloaded; the next one usually answers in 2-4 s.
        private const val QUICK_READ_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private val MODEL_ID = Regex("[A-Za-z0-9._-]{1,64}")

        /** Parses a comma-separated model list, dropping blanks, duplicates and malformed ids. */
        fun parseModels(csv: String): List<String> =
            csv.split(',').map { it.trim() }.filter { MODEL_ID.matches(it) }.distinct()
    }
}
