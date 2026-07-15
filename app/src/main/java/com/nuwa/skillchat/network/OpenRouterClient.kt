package com.nuwa.skillchat.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class OpenRouterClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val apiEndpoint = "https://openrouter.ai/api/v1/chat/completions"

    data class Message(
        val role: String,
        val content: String,
        val toolCallId: String? = null,
        val name: String? = null
    )

    // ─── Web Search via DuckDuckGo ─────────────────────────────────
    fun performWebSearch(query: String): String {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchRequest = Request.Builder()
                .url("https://html.duckduckgo.com/html/?q=$encodedQuery")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .get()
                .build()

            client.newCall(searchRequest).execute().use { response ->
                if (!response.isSuccessful) return "搜索无结果"
                val html = response.body?.string() ?: return "搜索无结果"

                val results = mutableListOf<Pair<String, String>>()
                val resultPattern = Pattern.compile(
                    "<a[^>]+class=\"result__a\"[^>]*>(.*?)</a>.*?<a[^>]+class=\"result__snippet\"[^>]*>(.*?)</a>",
                    Pattern.DOTALL
                )
                val matcher = resultPattern.matcher(html)
                while (matcher.find() && results.size < 5) {
                    val title = matcher.group(1)?.replace("<[^>]+>".toRegex(), "")?.trim() ?: ""
                    val snippet = matcher.group(2)?.replace("<[^>]+>".toRegex(), "")?.trim() ?: ""
                    if (title.isNotBlank() && snippet.isNotBlank()) {
                        results.add(title to snippet)
                    }
                }

                if (results.isEmpty()) return "搜索「$query」无结果"

                buildString {
                    appendLine("以下是关于「$query」的网络搜索结果：")
                    results.forEachIndexed { i, (t, s) ->
                        appendLine("${i + 1}. $t — $s")
                    }
                }
            }
        } catch (e: Exception) {
            "搜索出错: ${e.localizedMessage}"
        }
    }

    // ─── Streaming Chat with Tool Calling ──────────────────────────
    fun sendChatRequestStream(
        messages: List<Message>,
        apiKey: String,
        model: String,
        tools: JSONArray? = null
    ): Flow<String> = flow {
        val targetModel = model.ifBlank { "deepseek/deepseek-v4-pro" }
        val authHeader = if (apiKey.trim().startsWith("Bearer ")) apiKey.trim() else "Bearer ${apiKey.trim()}"

        // --- First request (with tools) ---
        val toolCallResult = streamAndCollect(messages, targetModel, authHeader, tools, this)

        if (toolCallResult == null) {
            // No tool call — content was already streamed directly
            return@flow
        }

        // --- Tool was called: execute web search ---
        val toolCallId = toolCallResult.first
        val arguments = toolCallResult.second

        var query = ""
        try {
            val argsJson = JSONObject(arguments)
            query = argsJson.optString("query", "")
        } catch (_: Exception) {}

        emit("\n\n🔍 正在搜索: $query ...\n\n")

        val searchResults = if (query.isNotBlank()) performWebSearch(query) else "搜索无结果"

        // --- Second request with search results (no tools) ---
        val newMessages = messages.toMutableList()
        newMessages.add(Message("assistant", "", toolCallId = toolCallId))
        newMessages.add(Message("tool", searchResults, name = "web_search"))

        streamAndCollect(newMessages, targetModel, authHeader, null, this)
    }.flowOn(Dispatchers.IO)

    /**
     * Executes a streaming request, emitting content chunks to the collector.
     * Returns null if only content was streamed (no tool call),
     * or Pair(toolCallId, arguments) if the model called a tool.
     */
    private suspend fun streamAndCollect(
        messages: List<Message>,
        model: String,
        authHeader: String,
        tools: JSONArray?,
        collector: FlowCollector<String>
    ): Pair<String, String>? {
        val requestBodyJson = JSONObject().apply {
            put("model", model)
            put("stream", true)

            val messagesArray = JSONArray()
            messages.forEach { msg ->
                val msgJson = JSONObject()
                msgJson.put("role", msg.role)

                if (msg.role == "assistant" && msg.toolCallId != null) {
                    msgJson.put("content", JSONObject.NULL)
                    msgJson.put("tool_calls", JSONArray().apply {
                        put(JSONObject().apply {
                            put("id", msg.toolCallId)
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", "web_search")
                                put("arguments", "{}")
                            })
                        })
                    })
                } else {
                    msgJson.put("content", msg.content)
                }

                if (msg.role == "tool" && msg.name != null) {
                    msgJson.put("name", msg.name)
                }

                messagesArray.put(msgJson)
            }
            put("messages", messagesArray)

            if (tools != null) {
                put("tools", tools)
            }
        }

        val request = Request.Builder()
            .url(apiEndpoint)
            .addHeader("Authorization", authHeader)
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://github.com/alchaincyf/nuwa-skill")
            .addHeader("X-Title", "Nuwa Mobile Skill Chat")
            .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        var toolCallId = ""
        val toolArgsBuffer = StringBuilder()
        var hasToolCall = false

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    collector.emit("API Error: ${response.message} (${response.code})")
                    return null
                }

                val reader = response.body?.charStream()?.buffered() ?: return null
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: break
                    if (!currentLine.startsWith("data: ")) continue
                    val dataContent = currentLine.substring(6).trim()
                    if (dataContent == "[DONE]") break

                    try {
                        val json = JSONObject(dataContent)
                        val choices = json.getJSONArray("choices")
                        if (choices.length() == 0) continue
                        val choice = choices.getJSONObject(0)
                        val delta = choice.optJSONObject("delta") ?: continue

                        // Stream content
                        if (delta.has("content") && !delta.isNull("content")) {
                            collector.emit(delta.getString("content"))
                        }

                        // Accumulate tool calls
                        if (delta.has("tool_calls")) {
                            val toolCalls = delta.getJSONArray("tool_calls")
                            if (toolCalls.length() > 0) {
                                hasToolCall = true
                                val tc = toolCalls.getJSONObject(0)
                                if (tc.has("id")) toolCallId = tc.getString("id")
                                if (tc.has("function")) {
                                    val fn = tc.getJSONObject("function")
                                    if (fn.has("arguments")) {
                                        toolArgsBuffer.append(fn.getString("arguments"))
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            if (!hasToolCall) {
                collector.emit("Network Error: ${e.localizedMessage}")
            }
        }

        return if (hasToolCall) Pair(toolCallId, toolArgsBuffer.toString()) else null
    }

    /**
     * Non-streaming request for session title summarization using deepseek-v4-flash.
     */
    suspend fun sendSummarizeRequest(messages: List<Message>, apiKey: String, prompt: String? = null): String? {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val requestBodyJson = JSONObject().apply {
                    put("model", "deepseek/deepseek-v4-flash")
                    put("stream", false)

                    val messagesArray = JSONArray()
                    val systemPrompt = prompt ?: "请用8-15个中文字总结以下对话的主题，只输出主题文字，不加任何标点或解释。"
                    messagesArray.put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    messages.forEach { msg ->
                        messagesArray.put(JSONObject().apply {
                            put("role", msg.role)
                            put("content", msg.content)
                        })
                    }
                    put("messages", messagesArray)
                }

                val authHeader = if (apiKey.trim().startsWith("Bearer ")) apiKey.trim() else "Bearer ${apiKey.trim()}"

                val request = Request.Builder()
                    .url(apiEndpoint)
                    .addHeader("Authorization", authHeader)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("HTTP-Referer", "https://github.com/alchaincyf/nuwa-skill")
                    .addHeader("X-Title", "Nuwa Mobile Skill Chat")
                    .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val bodyString = response.body?.string() ?: return@withContext null
                    val json = JSONObject(bodyString)
                    val choices = json.getJSONArray("choices")
                    if (choices.length() > 0) {
                        val message = choices.getJSONObject(0).getJSONObject("message")
                        return@withContext message.getString("content").trim()
                    }
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}
