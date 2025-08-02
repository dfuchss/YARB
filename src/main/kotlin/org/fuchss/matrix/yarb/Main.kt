package org.fuchss.matrix.yarb

import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.client.fromStore
import net.folivo.trixnity.client.login
import net.folivo.trixnity.clientserverapi.model.authentication.IdentifierType
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent
import org.fuchss.matrix.bots.MatrixBot
import org.fuchss.matrix.bots.command.ChangeUsernameCommand
import org.fuchss.matrix.bots.command.Command
import org.fuchss.matrix.bots.command.HelpCommand
import org.fuchss.matrix.bots.command.LogoutCommand
import org.fuchss.matrix.bots.command.QuitCommand
import org.fuchss.matrix.bots.helper.createMediaStoreModule
import org.fuchss.matrix.bots.helper.createRepositoriesModule
import org.fuchss.matrix.bots.helper.decryptMessage
import org.fuchss.matrix.bots.helper.handleCommand
import org.fuchss.matrix.bots.helper.handleEncryptedCommand
import org.fuchss.matrix.yarb.commands.ReminderCommand
import java.io.File
import java.util.Timer
import kotlin.random.Random

private lateinit var commands: List<Command>

fun main() {
    runBlocking {
        val config = Config.load()

        val matrixClient = getMatrixClient(config)
        val matrixBot = MatrixBot(matrixClient, config)

        val timer = Timer(true)
        val timerManager = TimerManager(matrixBot, timer, config)

        val reminderCommand = ReminderCommand(config, timerManager)
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

        // Command Handling
        matrixBot.subscribeContent { event -> handleCommand(commands, event, matrixBot, config, ReminderCommand.COMMAND_NAME) }
        matrixBot.subscribeContent { encEvent -> handleEncryptedCommand(commands, encEvent, matrixBot, config, ReminderCommand.COMMAND_NAME) }

        // Listen for edits of user messages
        matrixBot.subscribeContent<RoomMessageEventContent.TextBased.Text> { eventId, sender, roomId, content ->
            reminderCommand.handleUserEditMessage(matrixBot, eventId, sender, roomId, content)
        }
        matrixBot.subscribeContent { encryptedEvent ->
            decryptMessage(encryptedEvent, matrixBot) { eventId, userId, roomId, text ->
                reminderCommand.handleUserEditMessage(matrixBot, eventId, userId, roomId, text)
            }
        }
        matrixBot.subscribeContent { event -> reminderCommand.handleUserDeleteMessage(matrixBot, event) }

        val loggedOut = matrixBot.startBlocking()

        // After Shutdown
        timer.cancel()

        if (loggedOut) {
            // Cleanup database
            val databaseFiles = listOf(File(config.dataDirectory + "/database.mv.db"), File(config.dataDirectory + "/database.trace.db"))
            databaseFiles.filter { it.exists() }.forEach { it.delete() }
        }
    }
}

private suspend fun getMatrixClient(config: Config): MatrixClient {
    val existingMatrixClient =
        MatrixClient.fromStore(createRepositoriesModule(config), createMediaStoreModule(config)).getOrThrow()
    if (existingMatrixClient != null) {
        return existingMatrixClient
    }

    val matrixClient =
        MatrixClient
            .login(
                baseUrl = Url(config.baseUrl),
                identifier = IdentifierType.User(config.username),
                password = config.password,
                repositoriesModule = createRepositoriesModule(config),
                mediaStoreModule = createMediaStoreModule(config),
                initialDeviceDisplayName = "${MatrixBot::class.java.`package`.name}-${Random.Default.nextInt()}"
            ).getOrThrow()

    return matrixClient
}
