package org.fuchss.matrix.yarb

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.folivo.trixnity.client.room.getTimelineEventReactionAggregation
import net.folivo.trixnity.client.room.message.mentions
import net.folivo.trixnity.client.room.message.reply
import net.folivo.trixnity.client.store.sender
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import org.fuchss.matrix.bots.MatrixBot
import org.fuchss.matrix.bots.emoji
import org.fuchss.matrix.bots.markdown
import org.fuchss.matrix.bots.matrixTo
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalTime
import java.util.Timer
import java.util.TimerTask
import kotlin.time.Duration.Companion.seconds

class TimerManager(
    private val matrixBot: MatrixBot,
    javaTimer: Timer,
    config: Config
) {
    companion object {
        private val logger = LoggerFactory.getLogger(TimerManager::class.java)
        val DEFAULT_REACTION = ":+1:".emoji()
    }

    private val objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule()).enable(SerializationFeature.INDENT_OUTPUT)
    private val timerFileLocation = config.dataDirectory + "/timers.json"
    private val timers = mutableListOf<TimerData>()

    init {
        val timerFile = File(timerFileLocation)
        if (timerFile.exists()) {
            val timersFromFile: List<TimerData> = objectMapper.readValue(timerFile)
            timers.addAll(timersFromFile)
        }
    }

    init {
        val millisecondsToNextMinute = (60 - LocalTime.now().second) * 1000L
        javaTimer.schedule(
            object : TimerTask() {
                override fun run() {
                    runBlocking {
                        logger.debug("Reminders: {}", timers)
                        val timerCopy = timers.toList()
                        val now = LocalTime.now()
                        for (timer in timerCopy) {
                            if (timer.timeToRemind.isAfter(now)) {
                                continue
                            }
                            removeTimer(timer)
                            remind(timer)
                        }
                    }
                }
            },
            millisecondsToNextMinute,
            30.seconds.inWholeMilliseconds
        )
    }

    fun addTimer(timer: TimerData) {
        timers.add(timer)
        saveTimers()
    }

    fun removeByOriginalRequestMessage(eventId: EventId): TimerData? {
        val timer = timers.find { it.originalRequestMessage() == eventId } ?: return null
        timers.remove(timer)
        saveTimers()
        return timer
    }

    private fun removeTimer(timer: TimerData) {
        timers.remove(timer)
        saveTimers()
    }

    @Synchronized
    private fun saveTimers() {
        val tempFile = File(timerFileLocation + ".tmp")
        objectMapper.writeValue(tempFile, timers)
        val timerFile = File(timerFileLocation)
        Files.move(tempFile.toPath(), timerFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private suspend fun remind(timer: TimerData) {
        try {
            val(remainingReactions, emojiCount) = removeReactionOfBot(timer)
            if (remainingReactions.isEmpty()) {
                return
            }

            val maxReactions = emojiCount.values.max()

            matrixBot.room().sendMessage(timer.roomId()) {
                reply(timer.botMessageId(), null)
                mentions(remainingReactions.toSet())
                markdown(
                    "${emojiCount.filter {it.value >= maxReactions }.map { "* " + timer.emojiToMessage[it.key] }.joinToString(
                        "\n"
                    )}\n\n${remainingReactions.joinToString(", ") { it.matrixTo() }}"
                )
            }
        } catch (e: Exception) {
            logger.error("Error during remind: ${e.message}", e)
        }
    }

    /**
     * Remove the reaction of the bot from the message
     * @return the list of users reacted to the message
     */
    private suspend fun removeReactionOfBot(timer: TimerData): Pair<List<UserId>, Map<String, Int>> {
        timer.redactBotReaction(matrixBot)

        val allReactions =
            matrixBot
                .room()
                .getTimelineEventReactionAggregation(timer.roomId(), timer.botMessageId())
                .first()
                .reactions

        val users =
            timer.emojiToMessage.keys
                .flatMap { allReactions[it] ?: emptyList() }
                .map { it.sender }
                .filter { it != matrixBot.self() }
                .distinct()
        val countsOfEmojis =
            timer.emojiToMessage.keys
                .map { it to (allReactions[it] ?: emptyList()) }
                .map { (emoji, reactions) -> emoji to reactions.filter { reaction -> reaction.sender != matrixBot.self() } }
                .associate { (emoji, reactions) -> emoji to reactions.size }
        return users to countsOfEmojis
    }

    data class TimerData(
        @JsonProperty val roomId: String,
        @JsonProperty val originalRequestMessage: String,
        @JsonProperty val currentRequestMessage: String,
        @JsonProperty val timeToRemind: LocalTime,
        @JsonProperty val botMessageId: String,
        @JsonProperty val botReactionMessageIds: List<String>,
        @JsonProperty val emojiToMessage: Map<String, String>
    ) {
        constructor(
            roomId: RoomId,
            originalRequestMessage: EventId,
            currentRequestMessage: EventId,
            timeToRemind: LocalTime,
            botMessageId: EventId,
            botReactionMessageIds: List<EventId>,
            emojiToMessage: Map<String, String>
        ) : this(
            roomId.full,
            originalRequestMessage.full,
            currentRequestMessage.full,
            timeToRemind,
            botMessageId.full,
            botReactionMessageIds.map { it.full },
            emojiToMessage
        )

        fun roomId() = RoomId(roomId)

        fun originalRequestMessage() = EventId(originalRequestMessage)

        fun botMessageId() = EventId(botMessageId)

        fun botReactionMessageIds() = botReactionMessageIds.map { EventId(it) }

        suspend fun redactAll(matrixBot: MatrixBot) {
            redactBotReaction(matrixBot)
            matrixBot.roomApi().redactEvent(roomId(), botMessageId())
        }

        suspend fun redactBotReaction(matrixBot: MatrixBot) {
            botReactionMessageIds().forEach { matrixBot.roomApi().redactEvent(roomId(), it) }
        }
    }
}
