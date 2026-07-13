package com.nuwa.skillchat.network

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class GiteeService(
    private val owner: String = "zan2553",
    private val repo: String = "nvwa",
    private val branch: String = "master"
) {
    private val client = OkHttpClient()
    private val baseUrl = "https://gitee.com/api/v5"

    data class GiteeSkill(
        val name: String,
        val path: String,
        val type: String
    )

    suspend fun fetchSkillsList(path: String = ""): List<GiteeSkill> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/repos/$owner/$repo/contents/$path?ref=$branch"
        val request = Request.Builder().url(url).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Failed to fetch: $response")
                
                val bodyString = response.body?.string() ?: return@use emptyList()
                
                // Gitee content API returns folder list as array
                val jsonArray = JSONArray(bodyString)
                val skills = mutableListOf<GiteeSkill>()
                
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val type = item.optString("type", "")
                    
                    // We check if it is a folder (representing a skill) OR if the user put a direct markdown file
                    if (type == "dir") {
                        skills.add(
                            GiteeSkill(
                                name = item.getString("name"),
                                path = item.getString("path"),
                                type = type
                            )
                        )
                    } else if (type == "file" && item.getString("name").endsWith(".md")) {
                        skills.add(
                            GiteeSkill(
                                name = item.getString("name"),
                                path = item.getString("path"),
                                type = type
                            )
                        )
                    }
                }
                return@use skills
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun fetchSkillPrompt(skillPath: String): String? = withContext(Dispatchers.IO) {
        val url = "$baseUrl/repos/$owner/$repo/contents/$skillPath?ref=$branch"
        val request = Request.Builder().url(url).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                
                val bodyString = response.body?.string() ?: return@use null
                val jsonObject = JSONObject(bodyString)
                val contentBase64 = jsonObject.getString("content")
                
                val cleanBase64 = contentBase64.replace("\n", "").replace("\r", "")
                val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                
                return@use String(decodedBytes, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
