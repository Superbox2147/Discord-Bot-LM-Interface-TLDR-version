package org.bot

import kotlinx.serialization.json.*
import java.io.File

class StatsManager {
    private val statsFile = File("./src/Logs/stats.json")

    fun getStats(): String {
        val stats = Json.decodeFromString<JsonObject>(statsFile.readText())
        val slashCommandUsages = stats["slash_commands"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val prefixCommandUsages = stats["prefix_commands"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        return "Slash commands have been used $slashCommandUsages times and prefix commands $prefixCommandUsages times."
    }

    fun addSlashCommandsUsage() {
        val currentStats = Json.decodeFromString<JsonObject>(statsFile.readText())
        if (currentStats["slash_commands"] != null) {
            val slashCommandUsages = (currentStats["slash_commands"]!!.jsonPrimitive.content.toIntOrNull() ?: 0) + 1
            val jsonData = buildJsonObject {
                put("slash_commands", Json.encodeToJsonElement(slashCommandUsages.toString()))
                put(
                    "prefix_commands",
                    Json.encodeToJsonElement(currentStats["prefix_commands"]?.jsonPrimitive?.content ?: "0")
                )
            }
            statsFile.printWriter().use {
                it.print(jsonData)
            }
            return
        }
        val slashCommandUsages = 1
        val jsonData = buildJsonObject {
            put("slash_commands", Json.encodeToJsonElement(slashCommandUsages.toString()))
            put(
                "prefix_commands",
                Json.encodeToJsonElement(currentStats["prefix_commands"]?.jsonPrimitive?.content ?: "0")
            )
        }
        statsFile.printWriter().use {
            it.print(jsonData.toString())
        }
    }

    fun addPrefixCommandUsage() {
        val currentStats = Json.decodeFromString<JsonObject>(statsFile.readText())
        if (currentStats["prefix_commands"] != null) {
            val prefixCommandUsages = (currentStats["slash_commands"]!!.jsonPrimitive.content.toIntOrNull() ?: 0) + 1
            val jsonData = buildJsonObject {
                put("prefix_commands", Json.encodeToJsonElement(prefixCommandUsages.toString()))
                put("slash_commands", Json.encodeToJsonElement(currentStats["slash_commands"]?.jsonPrimitive?.content ?: "0"))
            }
            statsFile.printWriter().use {
                it.print(jsonData.toString())
            }
            return
        }
        val prefixCommandUsages = 1
        val jsonData = buildJsonObject {
            put("prefix_commands", Json.encodeToJsonElement(prefixCommandUsages.toString()))
            put("slash_commands", Json.encodeToJsonElement(currentStats["slash_commands"]?.jsonPrimitive?.content ?: "0"))
        }
        statsFile.printWriter().use {
            it.print(jsonData)
        }
    }
}