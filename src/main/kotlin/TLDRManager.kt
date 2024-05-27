package org.bot

import dev.kord.core.entity.Message
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.exists

class TLDRManager {
    private val TLDRPrompt = File("./src/Character/TLDRPrompt.LLMD").readText()
    private val maxMessageLogLength = dotenv["MAX_LOG_LENGTH"].toIntOrNull() ?: 50
    private var lastTLDRs = mutableMapOf<String, Long>()
    private val TLDRCooldown = dotenv["TLDR_COOLDOWN"].toIntOrNull() ?: -1
    fun saveMessage(message: Message) {
        if (maxMessageLogLength < 5)
            println("warning: low message logging length, consider increasing it to make sure the context is saved correctly")
        if (!Path("./src/Logs/Messages${message.channel.id}.json").exists()) {
            runBlocking {
                File("./src/Logs/Messages${message.channel.id}.json").createNewFile()
            }
            File("./src/Logs/Messages${message.channel.id}.json").printWriter().use {
                it.print("[]")
            }
        }
        val messageLogs = Json.decodeFromString<JsonArray>(File("./src/Logs/Messages${message.channel.id}.json").readText())
        val newMessageLogs = mutableListOf<JsonElement>()
        if (messageLogs.size > maxMessageLogLength - 1) {
            for (i in 1..<maxMessageLogLength) {
                newMessageLogs.add(messageLogs[i + messageLogs.size - maxMessageLogLength])
            }
        } else {
            for (i in messageLogs) {
                newMessageLogs.add(i)
            }
        }
        //newMessageLogs.add(Json.decodeFromString(""""${message.author!!.username}: ${message.content}""""))
        val inputMessage = "${message.author!!.username}: ${message.content}"
        newMessageLogs.add(Json.encodeToJsonElement(inputMessage))
        val finalMessageLogs = buildJsonArray {
            for (i in newMessageLogs) {
                add(i)
            }
        }
        File("./src/Logs/Messages${message.channel.id}.json").printWriter().use {
            it.print(finalMessageLogs)
        }
    }
    suspend fun clearAllLogs(message: Message) {
        if (checkPermissions(message)) {
            File("./src/Logs").deleteRecursively()
            File("./src/Logs").mkdir()
            reply(message, "Message log reset successfully, <@${message.author!!.id}>")
        } else {
            reply(message, "Sorry, but you do not have the correct permissions to do so")
        }
    }
    suspend fun TLDR(message: Message) {
        val lastTLDR = if (lastTLDRs["${message.channel.id}"] == null) {
            0
        } else {
            lastTLDRs["${message.channel.id}"]!!
        }
        println("${message.author!!.username} requested a TLDR")
        if (!File("./src/Logs/Messages${message.channel.id}.json").exists()) {
            reply(message, "No messages logged for channel")
            return
        }
        val messagesLog = mutableListOf<String>()
        for (i in Json.decodeFromString<JsonArray>(File("./src/Logs/Messages${message.channel.id}.json").readText())) {
            messagesLog.add(i.jsonPrimitive.content)
        }
        val minimumMessages = dotenv["MINIMUM_MESSAGES"].toIntOrNull() ?: -1
        if (minimumMessages > 0 && messagesLog.size < minimumMessages) {
            reply(message, "Less than $minimumMessages messages logged on channel")
            return
        }
        val currentTime = currentTimeMinutes()
        if (TLDRCooldown > 0) {
            if (currentTime < lastTLDR + TLDRCooldown) {
                reply(message, "On cooldown, come back approximately <t:${getTLDRCooldownTimestamp(TLDRCooldown, lastTLDR)}:R>")
                return
            } else {
                lastTLDRs["${message.channel.id}"] = currentTime
            }
        }
        val ctxTruncation = dotenv["CTX_TRUNCATION"].toIntOrNull() ?: -1
        val truncationLength = dotenv["TRUNCATION_LENGTH"].toIntOrNull() ?: -1
        val maxStreak = dotenv["MAX_NEWLINE_STREAK"].toIntOrNull() ?: -1
        val doStreak = maxStreak >= 0
        val chatLog = messagesLog.joinToString("\n")
        clearMessageLogs(message)
        val rawResponse = LLM.sendLLMRequest("${
            if (ctxTruncation < 0) {
                chatLog
            } else {
                if (chatLog.length < ctxTruncation) {
                    chatLog
                } else {
                    chatLog.drop(chatLog.length - ctxTruncation)
                }
            }
        }\n${filter.clean(message.author!!.username)}: $TLDRPrompt\n$charName:", message.author!!.username, message)
        var unfilteredResponse = ""
        var streakLength = 0
        if (truncationLength < 0) {
            unfilteredResponse = rawResponse
        } else {
            var newlines = 0
            for (i in rawResponse) {
                if (i.toString() == " ") {
                    streakLength++
                } else if (i.toString() == "\n") {
                    newlines++
                    streakLength++
                    if (newlines > truncationLength) {
                        println("Truncated reply to $truncationLength newlines")
                        break
                    }
                    unfilteredResponse += " "
                } else {
                    streakLength = 0
                }
                if (streakLength > maxStreak && doStreak) {
                    println("Maximum streak length of $maxStreak reached, reply cut off")
                    break
                }
                unfilteredResponse += i.toString()
            }
        }
        if (unfilteredResponse == "") unfilteredResponse = "..."
        val botResponse = if (!strictFiltering) {
            filter.filter(unfilteredResponse)
        } else {
            filter.filterStrict(unfilteredResponse)
        }
        println("$charName: $botResponse")
        reply(message, botResponse)
    }

    private fun currentTimeMinutes(): Long {
        val timeMilliseconds = System.currentTimeMillis()
        val timeMinutes = (timeMilliseconds - (timeMilliseconds % 60000)) / 60000
        return timeMinutes
    }

    private fun clearMessageLogs(message: Message) {
        File("./src/Logs/Messages${message.channel.id}.json").printWriter().use {
            it.print("[]")
        }
    }

    private fun getTLDRCooldownTimestamp(TLDRCooldown: Int, lastTLDR: Long) = (lastTLDR + TLDRCooldown) * 60
}