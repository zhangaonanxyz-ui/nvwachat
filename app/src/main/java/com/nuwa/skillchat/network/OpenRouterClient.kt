package com.nuwa.skillchat.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenRouterClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val apiEndpoint = "https://openrouter.ai/api/v1/chat/completions"

    data class Message(
        val role: String,
        val content: String
    )

    /**
     * Streams chat completions from OpenRouter using custom API Key and Model.
     */
    fun sendChatRequestStream(messages: List<Message>, apiKey: String, model: String): Flow<String> = flow {
        val targetModel = model.ifBlank { "deepseek/deepseek-v4-pro" }
        
        val requestBodyJson = JSONObject().apply {
            put("model", targetModel)
            put("stream", true)
            
            val messagesArray = JSONArray()
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

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit("Error: ${response.message} (${response.code})")
                    return@flow
                }

                val reader = response.body?.charStream()?.buffered() ?: throw IOException("Empty response body")
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: break
                    if (currentLine.startsWith("data: ")) {
                        val dataContent = currentLine.substring(6).trim()
                        if (dataContent == "[DONE]") {
                            break
                        }
                        
                        try {
                            val json = JSONObject(dataContent)
                            val choices = json.getJSONArray("choices")
                            if (choices.length() > 0) {
                                val delta = choices.getJSONObject(0).getJSONObject("delta")
                                if (delta.has("content")) {
                                    val chunk = delta.getString("content")
                                    emit(chunk)
                                }
                            }
                        } catch (e: Exception) {
                            // Skip JSON parse errors
                        }
                    }
                }
            }
        } catch (e: Exception) {
            emit("Network Error: ${e.localizedMessage}")
        }
    }.flowOn(Dispatchers.IO)
}
