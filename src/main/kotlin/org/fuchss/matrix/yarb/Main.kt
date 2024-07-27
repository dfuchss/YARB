package org.fuchss.matrix.yarb

import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.client.fromStore
import net.folivo.trixnity.client.login
import net.folivo.trixnity.clientserverapi.model.authentication.IdentifierType
import net.folivo.trixnity.core.model.events.idOrNull
import net.folivo.trixnity.core.model.events.roomIdOrNull
import net.folivo.trixnity.core.model.events.senderOrNull
import org.fuchss.matrix.bots.MatrixBot
import org.fuchss.matrix.bots.command.ChangeUsernameCommand
import org.fuchss.matrix.bots.command.Command
import org.fuchss.matrix.bots.command.HelpCommand
import org.fuchss.matrix.bots.command.LogoutCommand
import org.fuchss.matrix.bots.command.QuitCommand
import org.fuchss.matrix.bots.helper.createMediaStore
import org.fuchss.matrix.bots.helper.createRepositoriesModule
import org.fuchss.matrix.bots.helper.handleEncryptedTextMessage
import org.fuchss.matrix.bots.helper.handleEncryptedTextMessageToCommand
import org.fuchss.matrix.bots.helper.handleTextMessageToCommand
import org.fuchss.matrix.yarb.commands.ReminderCommand
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Timer
import kotlin.random.Random

private val logger: Logger = LoggerFactory.getLogger(MatrixBot::class.java)

private lateinit var commands: List<Command>

fun main() {
    runBlocking {
        val config = Config.load()
        val timer = Timer(true)
        val reminderCommand = ReminderCommand(config, timer)
        commands =
            listOf(
                HelpCommand(config, "YARB") {
                    commands
                },
                QuitCommand(config),
                LogoutCommand(config),
                ChangeUsernameCommand(),
                reminderCommand
            )

        val matrixClient = getMatrixClient(config)

        val matrixBot = MatrixBot(matrixClient, config)

        matrixBot.subscribeContent { event -> handleTextMessageToCommand(commands, event, matrixBot, config, ReminderCommand.COMMAND_NAME) }
        matrixBot.subscribeContent {
                encryptedEvent ->
            handleEncryptedTextMessageToCommand(commands, encryptedEvent, matrixBot, config, ReminderCommand.COMMAND_NAME)
        }

        matrixBot.subscribeContent(listenNonUsers = true, listenBotEvents = true) { event ->
            val eventId = event.idOrNull ?: return@subscribeContent
            val sender = event.senderOrNull ?: return@subscribeContent
            val roomId = event.roomIdOrNull ?: return@subscribeContent
            reminderCommand.handleBotMessageForReminder(matrixBot, eventId, sender, roomId, event.content)
        }
        matrixBot.subscribeContent(listenNonUsers = true, listenBotEvents = true) { encryptedEvent ->
            handleEncryptedTextMessage(encryptedEvent, matrixBot) { eventId, userId, roomId, text ->
                reminderCommand.handleBotMessageForReminder(matrixBot, eventId, userId, roomId, text)
            }
        }

        matrixBot.subscribeContent { event ->
            val eventId = event.idOrNull ?: return@subscribeContent
            val sender = event.senderOrNull ?: return@subscribeContent
            val roomId = event.roomIdOrNull ?: return@subscribeContent
            reminderCommand.handleUserEditMessage(matrixBot, eventId, sender, roomId, event.content)
        }
        matrixBot.subscribeContent(listenNonUsers = true, listenBotEvents = true) { encryptedEvent ->
            handleEncryptedTextMessage(encryptedEvent, matrixBot) { eventId, userId, roomId, text ->
                reminderCommand.handleUserEditMessage(matrixBot, eventId, userId, roomId, text)
            }
        }

        val loggedOut = matrixBot.startBlocking()
        timer.cancel()

        // After Shutdown
        if (loggedOut) {
            // Cleanup database
            val databaseFiles = listOf(File(config.dataDirectory + "/database.mv.db"), File(config.dataDirectory + "/database.trace.db"))
            databaseFiles.filter { it.exists() }.forEach { it.delete() }
        }
    }
}

private suspend fun getMatrixClient(config: Config): MatrixClient {
    val existingMatrixClient =
        MatrixClient.fromStore(createRepositoriesModule(config), createMediaStore(config)).getOrThrow()
    if (existingMatrixClient != null) {
        return existingMatrixClient
    }

    val matrixClient =
        MatrixClient.login(
            baseUrl = Url(config.baseUrl),
            identifier = IdentifierType.User(config.username),
            password = config.password,
            repositoriesModule = createRepositoriesModule(config),
            mediaStore = createMediaStore(config),
            initialDeviceDisplayName = "${MatrixBot::class.java.`package`.name}-${Random.Default.nextInt()}"
        ).getOrThrow()

    return matrixClient
}
