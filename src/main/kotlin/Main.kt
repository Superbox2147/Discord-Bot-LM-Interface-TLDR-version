package org.bot

import dev.kord.common.entity.*
import dev.kord.core.*
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.behavior.reply
import dev.kord.core.entity.*
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.event.message.*
import dev.kord.gateway.*
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import java.io.*
import java.util.concurrent.*
import kotlin.system.exitProcess

val dotenv = dotenv()
val botToken: String? = dotenv["TOKEN"]
val owners = Json.decodeFromString<JsonArray>(dotenv["OWNERS"])
val llmUrl: String? = dotenv["LLMURL"]
var kord: Kord? = null
val client =
    OkHttpClient
        .Builder()
        .connectTimeout(
            1,
            TimeUnit.DAYS,
        ).writeTimeout(1, TimeUnit.DAYS)
        .readTimeout(1, TimeUnit.DAYS)
        .callTimeout(1, TimeUnit.DAYS)
        .build()
val llmConfig = Json.decodeFromString<JsonObject>(File("./src/config.json").readText())
val prompt = File("./src/SystemPrompt.LLMD").readText() + "\n" + File("./src/Character/CharacterInfo.LLMD").readText()
val charName = File("./src/Character/CharacterName.LLMD").readText()
val greeting = File("./src/Character/Greeting.LLMD").readText()
var blockList = Json.decodeFromString<JsonArray>("[]")
val commandManager = CommandManager()
val LLM = LLMManager()
var ignoreNext = false
val filter = FilterManager()
val strictFiltering =
    try {
        dotenv["STRICT_FILTERING"].toBooleanStrictOrNull()!!
    } catch (
        e: NullPointerException,
    ) {
        throw Exception("NonBooleanStrictFilteringException")
    }
val allowDMs = dotenv["ALLOW_DMS"].toBooleanStrictOrNull() ?: false
val botStatus = getStatus()
val TLDRCore = TLDRManager()
val statsManager = StatsManager()
var loginAgain = true
val apiErrorMessage = dotenv["LLM_API_ERROR_MESSAGE"] ?: "Error accessing LLM API"
const val botVersion = "Discord bot LMI by Superbox\nV1.1.0 (TLDR)\n"

@OptIn(ExperimentalCoroutinesApi::class)
suspend fun main() {
    println("Starting $botVersion")
    if (dotenv["TRUNCATION_LENGTH"].toIntOrNull() == null) throw Exception("InvalidTruncationLengthException")
    if (llmUrl == null || llmUrl == "") throw Exception("NoLLLMURLException")
    if (owners.isEmpty()) throw Exception("NoOwnersException")
    println("Owners: $owners")
    println("LLMUrl: $llmUrl")
    if (!File("./src/Logs").exists()) File("./src/Logs").mkdir()
    if (!File("./src/Logs/stats.json").exists()) {
        runBlocking {
            File("./src/Logs/stats.json").createNewFile()
            File("./src/Logs/stats.json").printWriter().use {
                it.print("{}")
            }
        }
    }
    if (File("./src/Blocklist.json").exists()) {
        blockList = Json.decodeFromString<JsonArray>(File("./src/Blocklist.json").readText())
    } else {
        runBlocking {
            File("./src/Blocklist.json").createNewFile()
            File("./src/Blocklist.json").printWriter().use {
                it.print("[]")
            }
            println("Created new blocklist file")
        }
    }
    if (!File("./src/Usernames.json").exists()) {
        runBlocking {
            File("./src/Usernames.json").createNewFile()
            File("./src/Usernames.json").printWriter().use {
                it.print("[]")
            }
            println("Created new usernames file")
        }
    }
    println("Blocked users: $blockList")
    filter.buildFilterMap()
    if (botToken == null || botToken == "") {
        throw Exception("NoBotTokenException")
    }
    val nonParallelDispatcher = Dispatchers.Default.limitedParallelism(1)
    kord = Kord(botToken)
    kord!!.on<ReactionAddEvent>(CoroutineScope(nonParallelDispatcher)) {
        if (messageAuthorId.toString() == kord.selfId.toString()) {
            if (emoji == ReactionEmoji.Unicode("❌")) {
                for (i in message.getReactors(emoji).toList()) {
                    var moderator = false
                    for (k in i.asMember(guildId!!).roleBehaviors) {
                        if (k.asRole().permissions.contains(Permission.ModerateMembers)) moderator = true
                    }
                    if (owners.contains<Any?>(Json.encodeToJsonElement(i.id.toString())) || moderator) {
                        message.delete()
                    }
                }
            }
            if (emoji == ReactionEmoji.Unicode("⛔")) {
                for (i in message.getReactors(emoji).toList()) {
                    var moderator = false
                    for (k in i.asMember(guildId!!).roleBehaviors) {
                        if (k.asRole().permissions.contains(Permission.ModerateMembers)) moderator = true
                    }
                    if (owners.contains<Any?>(Json.encodeToJsonElement(i.id.toString())) || moderator) {
                        ignoreNext = true
                    }
                }
            }
        }
    }
    kord!!.createGlobalChatInputCommand(name = "tldr", description = "generate a summary of the chat")
    kord!!.createGlobalChatInputCommand(name = "tldr_retry", description = "regenerate the latest summary")
    kord!!.createGlobalChatInputCommand(name = "test", description = "test command")
    kord!!.createGlobalChatInputCommand(name = "stats", description = "get statistics about commands")
    kord!!.on<ChatInputCommandInteractionCreateEvent>(CoroutineScope(nonParallelDispatcher)) {
        val response = interaction.deferPublicResponse()
        val command = interaction.command
        if (!allowDMs && interaction.channel.asChannel().type == ChannelType.DM) {
            response.respond {
                content = "DMs are disabled for this bot"
            }
            return@on
        }
        statsManager.addSlashCommandsUsage()
        when (command.rootName) {
            "test" -> {
                response.respond {
                    content = "Test output"
                }
            }
            "tldr" -> {
                val channel = interaction.channel.asChannel()
                val author = interaction.user
                if (blockList.contains<Any?>(Json.encodeToJsonElement(author.id.toString()))) {
                    println("Blocked user ${author.username} tried to talk to the bot")
                    response.respond {
                        content = "You are blocked from using that"
                    }
                    return@on
                }
                try {
                    val tldrResult = TLDRCore.slashTLDR(channel, author)
                    response.respond {
                        content = tldrResult
                    }
                } catch (e: LLMAPIException) {
                    println("LLM API access failed")
                    response.respond {
                        content = apiErrorMessage
                    }
                }
            }
            "tldr_retry" -> {
                val channel = interaction.channel.asChannel()
                val author = interaction.user
                if (blockList.contains<Any?>(Json.encodeToJsonElement(author.id.toString()))) {
                    println("Blocked user ${author.username} tried to talk to the bot")
                    response.respond {
                        content = "You are blocked from using that"
                    }
                    return@on
                }
                try {
                    val tldrResult = TLDRCore.slashRetry(channel, author)
                    response.respond {
                        content = tldrResult
                    }
                } catch (e: LLMAPIException) {
                    println("LLM API access failed")
                    response.respond {
                        content = apiErrorMessage
                    }
                }
            }
            "stats" -> {
                response.respond {
                    content = statsManager.getStats()
                }
            }
        }
    }
    kord!!.on<MessageCreateEvent>(CoroutineScope(nonParallelDispatcher)) {
        if (message.channel.asChannel().type == ChannelType.DM && !allowDMs) {
            return@on
        }
        if (message.author?.id == kord.selfId) {
            return@on
        }
        val messageContent = message.content.split(" ")
        if (messageContent.isEmpty()) {
            return@on
        }
        try {
            when (messageContent[0].lowercase()) {
                "!ping" -> commandManager.ping(message)
                // "!debug" -> commandManager.debug(message)
                // "!bonk" -> commandManager.bonk(message, messageContent)
                // "!reset" -> commandManager.reset(message)
                "!stop" -> commandManager.stop(message)
//                "!continue" -> commandManager.continueCmd(message)
//                "!echo" -> {
//                    if (messageContent.size >= 2) {
//                        commandManager.echo(message, messageContent)
//                    } else {
//                        reply(message, "You must specify a message to echo")
//                    }
//                }

                "!tldr" -> {
                    statsManager.addPrefixCommandUsage()
                    if (messageContent.size == 2) {
                        when (messageContent[1].lowercase()) {
                            "stats" -> reply(message, statsManager.getStats())
                            "reset" -> TLDRCore.clearAllLogs(message)
                            "stop" -> commandManager.stop(message)
                            "retry" ->
                                try {
                                    TLDRCore.regenerate(message)
                                } catch (e: LLMAPIException) {
                                    println("LLM API access failed")
                                    reply(message, apiErrorMessage)
                                }
                            "relog" -> commandManager.relog(message)
                            else ->
                                try {
                                    TLDRCore.TLDR(message)
                                } catch (e: LLMAPIException) {
                                    println("LLM API access failed")
                                    reply(message, apiErrorMessage)
                                }
                        }
                    } else {
                        if (blockList.contains<Any?>(Json.encodeToJsonElement(message.author?.id.toString()))) {
                            println("Blocked user ${message.author!!.username} tried to talk to the bot")
                            message.channel.createMessage("You are blocked from using that")
                            return@on
                        }
                        try {
                            TLDRCore.TLDR(message)
                        } catch (e: LLMAPIException) {
                            println("LLM API access failed")
                            reply(message, apiErrorMessage)
                        }
                    }
                }

                "!pldt" -> {
                    statsManager.addPrefixCommandUsage()
                    if (messageContent.size == 2) {
                        when (messageContent[1].lowercase()) {
                            "reset" -> TLDRCore.clearAllLogs(message)
                            "stop" -> commandManager.stop(message)
                            "retry" ->
                                try {
                                    TLDRCore.regenerate(message)
                                } catch (e: LLMAPIException) {
                                    println("LLM API access failed")
                                    reply(message, apiErrorMessage)
                                }
                            "relog" -> commandManager.relog(message)
                            else ->
                                try {
                                    TLDRCore.TLDR(message)
                                } catch (e: LLMAPIException) {
                                    println("LLM API access failed")
                                    reply(message, apiErrorMessage)
                                }
                        }
                    } else {
                        if (blockList.contains<Any?>(Json.encodeToJsonElement(message.author?.id.toString()))) {
                            println("Blocked user ${message.author!!.username} tried to talk to the bot")
                            message.channel.createMessage("You are blocked from using that")
                            return@on
                        }
                        try {
                            TLDRCore.TLDR(message)
                        } catch (e: LLMAPIException) {
                            println("LLM API access failed")
                            reply(message, apiErrorMessage)
                        }
                    }
                }

                "!blocklist" -> {
                    if (messageContent.size == 3) {
                        try {
                            if (checkPermissions(message)) {
                                when (messageContent[1].lowercase()) {
                                    "add" -> commandManager.blocklistAdd(message, messageContent[2])
                                    "remove" -> commandManager.blocklistRemove(message, messageContent[2])
                                }
                            } else {
                                message.channel.createMessage("Sorry, but you do not have the correct permission to do so.")
                                println(
                                    "${message.author?.username} tried to tamper with the blocklist, but they lack the permission to do so! Skill issue.",
                                )
                            }
                        } catch (e: NullPointerException) {
                            println("Caught NullPointerException in blocklist")
                            message.channel.createMessage("Checking permissions failed: NullPointerException")
                        }
                    } else {
                        message.channel.createMessage(
                            "Command has an incorrect amount of parameters, expecting '!blocklist add/remove USER'",
                        )
                    }
                }

                "!filter" -> {
                    if (messageContent.size >= 3) {
                        if (messageContent[1].lowercase() == "toggle") {
                            filter.toggleFilter(messageContent.drop(2).joinToString(" "), message)
                        }
                    }
                }

                else -> {
                    if (message.referencedMessage?.author?.id == kord.selfId) {
                        TLDRCore.saveMessage(message)
                    } else if (message.referencedMessage?.type == MessageType.ChatInputCommand) {
                        TLDRCore.saveMessage(message)
                    } else if (message.mentionedUserIds.contains(kord.selfId)) {
                        if (ignoreNext) {
                            ignoreNext = false
                            return@on
                        }
                        if (blockList.contains<Any?>(Json.encodeToJsonElement(message.author?.id.toString()))) {
                            println("Blocked user ${message.author!!.username} tried to talk to the bot")
                            message.channel.createMessage("You are blocked from using that")
                            return@on
                        }
                        try {
                            TLDRCore.TLDR(message)
                        } catch (e: LLMAPIException) {
                            println("LLM API access failed")
                            reply(message, apiErrorMessage)
                        }
                    } else {
                        TLDRCore.saveMessage(message)
                    }
                }
            }
        } catch (e: Exception) {
            for (i in e.stackTrace) println(i.toString())
            println(e.toString())
        }
    }
    println("ready")
    while (loginAgain) {
        println("Logging in as ${kord!!.getSelf().username}")
        kord!!.login {
            @OptIn(PrivilegedIntent::class)
            intents += Intent.MessageContent
            presence {
                status = botStatus.first
                watching(botStatus.second)
            }
        }
    }
    kord!!.shutdown()
    println("logged out")
    exitProcess(0)
}

suspend fun checkPermissions(message: Message): Boolean {
    var moderator = false
    for (i in message.author?.asMember(message.getGuild().id)!!.roleBehaviors) {
        if (i.asRole().permissions.contains(Permission.ModerateMembers)) moderator = true
    }
    return owners.contains<Any?>(Json.encodeToJsonElement(message.author?.id.toString())) || moderator
}

suspend fun reply(
    message: Message,
    input: String,
) {
    message.reply {
        content = input
    }
}

fun getStatus(): Pair<PresenceStatus, String> {
    val rawStatus = dotenv["STATUS"] ?: "away"
    val status =
        if (rawStatus.lowercase() == "online") {
            PresenceStatus.Online
        } else if (rawStatus.lowercase() == "away") {
            PresenceStatus.Idle
        } else if (rawStatus.lowercase() == "dnd") {
            PresenceStatus.DoNotDisturb
        } else {
            PresenceStatus.Invisible
        }
    val presence = dotenv["PRESENCE"] ?: "TLDR chat with !TLDR"
    return Pair(status, presence)
}
