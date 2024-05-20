package org.bot

import dev.kord.core.entity.Message
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.exists

class TLDRManager {
    private val TLDRPrompt = File("./src/Character/TLDRPrompt.LLMD")
    private val maxMessageLogLength = dotenv["MAX_LOG_LENGTH"].toIntOrNull() ?: 50
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
        newMessageLogs.add(Json.decodeFromString(""""${message.author!!.username}: ${message.content}""""))
        println(newMessageLogs)
        val finalMessageLogs = buildJsonArray {
            for (i in newMessageLogs) {
                add(i)
            }
        }
        File("./src/Logs/Messages${message.channel.id}.json").printWriter().use {
            it.print(finalMessageLogs)
        }
    }
    fun clearAllLogs() {
        File("./src/Logs").deleteRecursively()
        File("./src/Logs").mkdir()
    }
    suspend fun TLDR(message: Message): String {
        println("${message.author!!.username} requested a TLDR")
        val ctxTruncation = dotenv["CTX_TRUNCATION"].toIntOrNull() ?: -1
        val truncationLength = dotenv["TRUNCATION_LENGTH"].toIntOrNull() ?: -1
        val maxStreak = dotenv["MAX_NEWLINE_STREAK"].toIntOrNull() ?: -1
        val doStreak = maxStreak >= 0
        val messagesLog = mutableListOf<String>()
        if (!File("./src/Logs/Messages${message.channel.id}.json").exists())
            return "No messages logged for channel"
        for (i in Json.decodeFromString<JsonArray>(File("./src/Logs/Messages${message.channel.id}.json").readText())) {
            messagesLog.add(i.jsonPrimitive.content)
        }
        val chatLog = messagesLog.joinToString("\n")
        val rawResponse = LLM.sendLLMRequest(if (ctxTruncation < 0) {chatLog} else {if (chatLog.length < ctxTruncation) {chatLog} else {chatLog.drop(chatLog.length-ctxTruncation)} } +
                "\n${message.author!!.username}: $TLDRPrompt" +
                "\n$charName:", message.author!!.username, message)
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
        return botResponse

    }
}