package org.bot

import dev.kord.core.entity.Message
import dev.kord.core.entity.User
import dev.kord.core.entity.channel.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.lang.NullPointerException
import kotlin.io.path.Path
import kotlin.io.path.exists

class TLDRManager {
    private val TLDRPrompt = File("./src/Character/TLDRPrompt.LLMD").readText()
    private val maxMessageLogLength = dotenv["MAX_LOG_LENGTH"].toIntOrNull() ?: 50
    private var lastTLDRs = mutableMapOf<String, Long>()
    private val TLDRCooldown = dotenv["TLDR_COOLDOWN"].toIntOrNull() ?: -1
    private val targetMinimumLength = dotenv["TLDR_MINIMUM_CHARACTERS"].toIntOrNull() ?: -1
    private val previousTLDRLLMPrompts = mutableMapOf<String, String>()
    private val ctxTruncation = dotenv["CTX_TRUNCATION"].toIntOrNull() ?: -1
    private val truncationLength = dotenv["TRUNCATION_LENGTH"].toIntOrNull() ?: -1
    private val maxStreak = dotenv["MAX_NEWLINE_STREAK"].toIntOrNull() ?: -1
    private val doStreak = maxStreak >= 0

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
        val inputMessage = try { "${message.author!!.username}: ${message.content}" } catch (e: NullPointerException) { "UnnamedUser: ${message.content}" }
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
        println("${message.author!!.username} requested a TLDR")
        val lastTLDR = if (lastTLDRs["${message.channel.id}"] == null) {
            0
        } else {
            lastTLDRs["${message.channel.id}"]!!
        }
        if (!File("./src/Logs/Messages${message.channel.id}.json").exists()) {
            reply(message, "No messages logged for channel")
            return
        }
        val messagesLog = mutableListOf<String>()
        for (i in Json.decodeFromString<JsonArray>(File("./src/Logs/Messages${message.channel.id}.json").readText())) {
            messagesLog.add(i.jsonPrimitive.content)
        }
        val minimumMessages = dotenv["MINIMUM_MESSAGES"].toIntOrNull() ?: -1
        val messagesLoggedCount = messagesLog.size
        if (minimumMessages > 0 && messagesLoggedCount < minimumMessages) {
            reply(message, "Less than $minimumMessages messages logged on channel, ${minimumMessages - messagesLoggedCount} more messages required.")
            return
        }
        val currentTime = currentTimeMinutes()
        if (TLDRCooldown > 0) {
            if (currentTime < lastTLDR + TLDRCooldown) {
                reply(message, "On cooldown, come back approximately <t:${getTLDRCooldownTimestamp(TLDRCooldown, lastTLDR)}:R>")
                return
            }
        }
        val chatLog = messagesLog.joinToString("\n")
        val inputToLLM = "${
            if (ctxTruncation < 0) {
                chatLog
            } else {
                if (chatLog.length < ctxTruncation) {
                    chatLog
                } else {
                    chatLog.drop(chatLog.length - ctxTruncation)
                }
            }
        }\n${filter.clean(message.author!!.username)}: $TLDRPrompt\n$charName:"
        val firstResponse = try {
            LLM.sendLLMRequest(
                inputToLLM,
                message.author!!.username,
                message
            )
        } catch (e: IOException) {
            throw LLMAPIException()
        }
        val rawResponse = if (targetMinimumLength <= 0) {
            firstResponse
        } else {
            if (firstResponse.length >= targetMinimumLength) {
                firstResponse
            } else {
                val secondResponse = try {
                    LLM.sendLLMRequest(
                        inputToLLM,
                        message.author!!.username,
                        message
                    )
                } catch (e: IOException) {
                    throw LLMAPIException()
                }
                if (secondResponse.length > firstResponse.length) {
                    secondResponse
                } else {
                    firstResponse
                }
            }
        }
        previousTLDRLLMPrompts["${message.channel.id}"] = inputToLLM
        lastTLDRs["${message.channel.id}"] = currentTime
        clearMessageLogs(message.channel.id.toString())
        val botResponse = processResponse(rawResponse)
        println("$charName: $botResponse")
        reply(message, botResponse)
    }

    suspend fun regenerate(message: Message) {
        println("${message.author!!.username} requested a regeneration")
        if (previousTLDRLLMPrompts["${message.channel.id}"] == null) {
            reply(message, "No previous TLDR on channel")
            return
        }
        val inputToLLM = previousTLDRLLMPrompts["${message.channel.id}"]!!
        val firstResponse = try {
            LLM.sendLLMRequest(
                inputToLLM,
                message.author!!.username,
                message
            )
        } catch (e: IOException) {
            throw LLMAPIException()
        }
        val rawResponse = if (targetMinimumLength <= 0) {
            firstResponse
        } else {
            if (firstResponse.length >= targetMinimumLength) {
                firstResponse
            } else {
                val secondResponse = try {
                    LLM.sendLLMRequest(
                        inputToLLM,
                        message.author!!.username,
                        message
                    )
                } catch (e: IOException) {
                    throw LLMAPIException()
                }
                if (secondResponse.length > firstResponse.length) {
                    secondResponse
                } else {
                    firstResponse
                }
            }
        }
        val botResponse = processResponse(rawResponse)
        println("$charName: $botResponse")
        reply(message, botResponse)
    }

    private fun processResponse(input: String): String {
        var unfilteredResponse = ""
        var streakLength = 0
        if (truncationLength < 0) {
            unfilteredResponse = input
        } else {
            var newlines = 0
            for (i in input) {
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
        return if (!strictFiltering) {
            filter.filter(unfilteredResponse)
        } else {
            filter.filterStrict(unfilteredResponse)
        }
    }

    private fun currentTimeMinutes(): Long {
        val timeMilliseconds = System.currentTimeMillis()
        val timeMinutes = (timeMilliseconds - (timeMilliseconds % 60000)) / 60000
        return timeMinutes
    }

    private fun clearMessageLogs(channelID: String) {
        File("./src/Logs/Messages$channelID.json").printWriter().use {
            it.print("[]")
        }
    }

    private fun getTLDRCooldownTimestamp(TLDRCooldown: Int, lastTLDR: Long) = (lastTLDR + TLDRCooldown) * 60

    suspend fun slashTLDR(channel: Channel, author: User): String {
        println("${author.username} requested a TLDR")
        val lastTLDR = if (lastTLDRs["${channel.id}"] == null) {
            0
        } else {
            lastTLDRs["${channel.id}"]!!
        }
        if (!File("./src/Logs/Messages${channel.id}.json").exists()) {
            return "No messages logged for channel"
        }
        val messagesLog = mutableListOf<String>()
        for (i in Json.decodeFromString<JsonArray>(File("./src/Logs/Messages${channel.id}.json").readText())) {
            messagesLog.add(i.jsonPrimitive.content)
        }
        val minimumMessages = dotenv["MINIMUM_MESSAGES"].toIntOrNull() ?: -1
        val messagesLoggedCount = messagesLog.size
        if (minimumMessages > 0 && messagesLoggedCount < minimumMessages) {
            return "Less than $minimumMessages messages logged on channel, ${minimumMessages - messagesLoggedCount} more messages required."
        }
        val currentTime = currentTimeMinutes()
        if (TLDRCooldown > 0) {
            if (currentTime < lastTLDR + TLDRCooldown) {
                return "On cooldown, come back approximately <t:${getTLDRCooldownTimestamp(TLDRCooldown, lastTLDR)}:R>"
            }
        }
        val chatLog = messagesLog.joinToString("\n")
        val inputToLLM = "${
            if (ctxTruncation < 0) {
                chatLog
            } else {
                if (chatLog.length < ctxTruncation) {
                    chatLog
                } else {
                    chatLog.drop(chatLog.length - ctxTruncation)
                }
            }
        }\n${filter.clean(author.username)}: $TLDRPrompt\n$charName:"
        val firstResponse = try {
            LLM.sendLLMRequest(
                inputToLLM,
                author.username,
                null
            )
        } catch (e: IOException) {
            throw LLMAPIException()
        }
        val rawResponse = if (targetMinimumLength <= 0) {
            firstResponse
        } else {
            if (firstResponse.length >= targetMinimumLength) {
                firstResponse
            } else {
                val secondResponse = try {
                    LLM.sendLLMRequest(
                        inputToLLM,
                        author.username,
                        null
                    )
                } catch (e: IOException) {
                    throw LLMAPIException()
                }
                if (secondResponse.length > firstResponse.length) {
                    secondResponse
                } else {
                    firstResponse
                }
            }
        }
        previousTLDRLLMPrompts["${channel.id}"] = inputToLLM
        lastTLDRs["${channel.id}"] = currentTime
        clearMessageLogs(channel.id.toString())
        val botResponse = processResponse(rawResponse)
        println("$charName: $botResponse")
        return botResponse
    }

    suspend fun slashRetry(channel: Channel, author: User): String {
        println("${author.username} requested a regeneration")
        if (previousTLDRLLMPrompts["${channel.id}"] == null) {
            return "No previous TLDR on channel"
        }
        val inputToLLM = previousTLDRLLMPrompts["${channel.id}"]!!
        val firstResponse = try {
            LLM.sendLLMRequest(
                inputToLLM,
                author.username,
                null
            )
        } catch (e: IOException) {
            throw LLMAPIException()
        }
        val rawResponse = if (targetMinimumLength <= 0) {
            firstResponse
        } else {
            if (firstResponse.length >= targetMinimumLength) {
                firstResponse
            } else {
                val secondResponse = try {
                    LLM.sendLLMRequest(
                        inputToLLM,
                        author.username,
                        null
                    )
                } catch (e: IOException) {
                    throw LLMAPIException()
                }
                if (secondResponse.length > firstResponse.length) {
                    secondResponse
                } else {
                    firstResponse
                }
            }
        }
        val botResponse = processResponse(rawResponse)
        println("$charName: $botResponse")
        return botResponse
    }
}