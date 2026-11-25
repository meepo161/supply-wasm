package org.example.project.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.example.project.config.AssistantSettings
import org.example.project.dto.AssistantStatusDto
import org.example.project.exception.ServiceUnavailableException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class LlmToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

data class LlmToolCall(
    val name: String,
    val arguments: JsonObject
)

data class LlmChatMessage(
    val role: String,
    val content: String,
    val toolCalls: List<LlmToolCall> = emptyList(),
    val images: List<String> = emptyList()
)

data class LlmChatResult(
    val content: String,
    val toolCalls: List<LlmToolCall>
)

interface LlmProvider {
    fun status(): AssistantStatusDto
    fun chat(messages: List<LlmChatMessage>, tools: List<LlmToolDefinition>): LlmChatResult
}

class OllamaLlmProvider(
    private val settings: AssistantSettings
) : LlmProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    override fun status(): AssistantStatusDto {
        if (!settings.enabled) {
            return AssistantStatusDto(
                available = false,
                model = settings.model,
                reason = "ИИ-помощник отключён в конфигурации сервера"
            )
        }
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${settings.baseUrl.trimEnd('/')}/api/tags"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                return AssistantStatusDto(
                    available = false,
                    model = settings.model,
                    reason = "Локальная модель ответила кодом ${response.statusCode()}"
                )
            }
            val root = json.parseToJsonElement(response.body()).jsonObject
            val models = root["models"]?.jsonArray?.mapNotNull {
                it.jsonObject["name"]?.jsonPrimitive?.content
            }.orEmpty()
            val hasModel = models.any { it == settings.model || it.startsWith("${settings.model}:") }
            if (hasModel) {
                AssistantStatusDto(available = true, model = settings.model)
            } else {
                AssistantStatusDto(
                    available = false,
                    model = settings.model,
                    reason = "Модель ${settings.model} не найдена в Ollama"
                )
            }
        } catch (e: Exception) {
            AssistantStatusDto(
                available = false,
                model = settings.model,
                reason = "Сервер модели недоступен (${settings.baseUrl})"
            )
        }
    }

    override fun chat(messages: List<LlmChatMessage>, tools: List<LlmToolDefinition>): LlmChatResult {
        if (!settings.enabled) {
            throw ServiceUnavailableException("ИИ-помощник отключён в конфигурации сервера")
        }

        val payload = buildJsonObject {
            put("model", settings.model)
            put("stream", false)
            put("think", false)
            put("options", buildJsonObject {
                put("temperature", 0.1)
                put("num_ctx", 16384)
                put("num_predict", 4096)
            })
            put("messages", buildJsonArray {
                messages.forEach { msg ->
                    add(buildJsonObject {
                        put("role", msg.role)
                        put("content", msg.content)
                        if (msg.images.isNotEmpty()) {
                            put("images", buildJsonArray {
                                msg.images.forEach { img -> add(JsonPrimitive(img)) }
                            })
                        }
                        if (msg.toolCalls.isNotEmpty()) {
                            put("tool_calls", buildJsonArray {
                                msg.toolCalls.forEach { tc ->
                                    add(buildJsonObject {
                                        put("function", buildJsonObject {
                                            put("name", tc.name)
                                            put("arguments", tc.arguments)
                                        })
                                    })
                                }
                            })
                        }
                    })
                }
            })
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.parameters)
                            })
                        })
                    }
                })
            }
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("${settings.baseUrl.trimEnd('/')}/api/chat"))
            .timeout(Duration.ofSeconds(settings.timeoutSeconds))
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build()

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw ServiceUnavailableException("Не удалось подключиться к локальной модели (${settings.baseUrl})")
        }

        if (response.statusCode() !in 200..299) {
            throw ServiceUnavailableException("Ошибка локальной модели: HTTP ${response.statusCode()}")
        }

        val root = json.parseToJsonElement(response.body()).jsonObject
        val messageObj = root["message"]?.jsonObject
            ?: throw ServiceUnavailableException("Пустой ответ от локальной модели")
        val content = messageObj["content"]?.jsonPrimitive?.content ?: ""
        val toolCalls = parseToolCalls(messageObj["tool_calls"])
        return LlmChatResult(content = content, toolCalls = toolCalls)
    }

    private fun parseToolCalls(element: JsonElement?): List<LlmToolCall> {
        if (element !is JsonArray) return emptyList()
        return element.mapNotNull { item ->
            val fn = item.jsonObject["function"]?.jsonObject ?: return@mapNotNull null
            val name = fn["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val argsEl = fn["arguments"]
            val argsObj = when (argsEl) {
                is JsonObject -> argsEl
                is JsonPrimitive -> runCatching {
                    json.parseToJsonElement(argsEl.content).jsonObject
                }.getOrElse { buildJsonObject {} }
                else -> buildJsonObject {}
            }
            LlmToolCall(name = name, arguments = argsObj)
        }
    }
}
