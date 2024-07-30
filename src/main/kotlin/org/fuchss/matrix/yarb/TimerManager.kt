package org.fuchss.matrix.yarb

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import net.folivo.trixnity.client.room.getTimelineEventReactionAggregation
import net.folivo.trixnity.client.room.message.reply
import net.folivo.trixnity.client.room.message.text
import net.folivo.trixnity.client.store.eventId
import net.folivo.trixnity.client.store.relatesTo
import net.folivo.trixnity.client.store.sender
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.events.m.RelatesTo
import net.folivo.trixnity.core.model.events.m.RelationType
import org.fuchss.matrix.bots.MatrixBot
import org.fuchss.matrix.bots.emoji
import org.fuchss.matrix.bots.firstWithTimeout
import org.fuchss.matrix.bots.matrixTo
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalTime
import java.util.Timer
import java.util.TimerTask
import kotlin.time.Duration.Companion.minutes

class TimerManager(private val matrixBot: MatrixBot, javaTimer: Timer, config: Config) {
    companion object {
        val EMOJI = ":+1:".emoji()
        private val logger = LoggerFactory.getLogger(TimerManager::class.java)
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
            1.minutes.inWholeMilliseconds
        )
    }

    fun addTimer(
        roomId: RoomId,
        requestMessage: EventId,
        timeToRemind: LocalTime,
        content: String
    ) {
        val timer = TimerData(roomId.full, requestMessage.full, timeToRemind, content, null)
        timers.add(timer)
        saveTimers()
    }

    fun addBotMessageToTimer(
        requestMessage: EventId,
        botMessageId: EventId
    ): Boolean {
        val timer = timers.find { it.requestMessage() == requestMessage } ?: return false
        timer.botMessageId = botMessageId.full
        saveTimers()
        return true
    }

    fun removeByRequestMessage(eventId: EventId): EventId? {
        val timerData = timers.find { it.requestMessage() == eventId } ?: return null
        timers.remove(timerData)
        saveTimers()
        return timerData.botMessageId()
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
            val roomId = timer.roomId()
            val messageId = timer.botMessageId() ?: return

            val reactions = matrixBot.room().getTimelineEventReactionAggregation(roomId, messageId).first().reactions
            val peopleToRemind = reactions[EMOJI]?.filter { it != matrixBot.self() }?.map { it.matrixTo() }
            if (peopleToRemind.isNullOrEmpty()) {
                return
            }

            val timelineEvent = matrixBot.getTimelineEvent(roomId, messageId) ?: return

            removeReactionOfBot(roomId, messageId)

            matrixBot.room().sendMessage(roomId) {
                reply(timelineEvent)
                text("'${timer.content}' ${peopleToRemind.joinToString(", ")}")
            }
        } catch (e: Exception) {
            logger.error("Error during remind: ${e.message}", e)
        }
    }

    private suspend fun removeReactionOfBot(
        roomId: RoomId,
        messageId: EventId
    ) {
        val reactions =
            matrixBot.room().getTimelineEventRelations(roomId, messageId, RelationType.Annotation)
                .map { it?.keys.orEmpty() }
                .map { relations ->
                    relations.mapNotNull { matrixBot.getTimelineEvent(roomId, it) }
                        .filter { it.sender == matrixBot.self() }
                        .mapNotNull {
                            val relatesTo = it.relatesTo
                            if (relatesTo is RelatesTo.Annotation) {
                                it.eventId to relatesTo.key
                            } else {
                                null
                            }
                        }
                }.firstWithTimeout { it.isNotEmpty() } ?: return

        val botReaction = reactions.find { it.second == EMOJI }
        if (botReaction != null) {
            matrixBot.roomApi().redactEvent(roomId, botReaction.first)
        } else {
            logger.warn("Could not find bot reaction to remove for message $messageId")
        }
    }

    private data class TimerData(
        @JsonProperty val roomId: String,
        @JsonProperty val requestMessage: String,
        @JsonProperty val timeToRemind: LocalTime,
        @JsonProperty val content: String,
        @JsonProperty var botMessageId: String?
    ) {
        fun roomId() = RoomId(roomId)

        fun requestMessage() = EventId(requestMessage)

        fun botMessageId() = botMessageId?.let { EventId(it) }
    }
}
