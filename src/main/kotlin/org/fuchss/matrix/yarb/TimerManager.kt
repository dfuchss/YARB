package org.fuchss.matrix.yarb

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import net.folivo.trixnity.client.room.RoomService
import net.folivo.trixnity.client.room.message.mentions
import net.folivo.trixnity.client.room.message.reply
import net.folivo.trixnity.client.room.message.text
import net.folivo.trixnity.client.store.eventId
import net.folivo.trixnity.client.store.relatesTo
import net.folivo.trixnity.client.store.sender
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.events.m.RelatesTo
import net.folivo.trixnity.core.model.events.m.RelationType
import org.fuchss.matrix.bots.MatrixBot
import org.fuchss.matrix.bots.emoji
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
            val timelineEvent = matrixBot.getTimelineEvent(roomId, messageId) ?: return

            val remainingReactions = removeReactionOfBot(roomId, messageId)

            if (remainingReactions.isEmpty()) {
                return
            }

            matrixBot.room().sendMessage(roomId) {
                reply(timelineEvent)
                mentions(remainingReactions.toSet())
                text("'${timer.content}' ${remainingReactions.joinToString(", ") { it.matrixTo() }}")
            }
        } catch (e: Exception) {
            logger.error("Error during remind: ${e.message}", e)
        }
    }

    /**
     * Remove the reaction of the bot from the message
     * @return the list of users reacted to the message
     */
    private suspend fun removeReactionOfBot(
        roomId: RoomId,
        messageId: EventId
    ): List<UserId> {
        val allReactions = matrixBot.room().getTimelineEventReactionAggregationWithIds(roomId, messageId).first()

        val reactions = allReactions[EMOJI] ?: return emptyList()

        val botReaction = reactions.find { it.second == matrixBot.self() }
        if (botReaction != null) {
            matrixBot.roomApi().redactEvent(roomId, botReaction.first)
        } else {
            logger.warn("Could not find bot reaction to remove for message $messageId")
        }
        return reactions.filter { it.second != matrixBot.self() }.map { it.second }
    }

    // Adapted from net/folivo/trixnity/client/room/TimelineEventAggregation.kt
    private fun RoomService.getTimelineEventReactionAggregationWithIds(
        roomId: RoomId,
        eventId: EventId
    ): Flow<Map<String, Set<Pair<EventId, UserId>>>> =
        getTimelineEventRelations(roomId, eventId, RelationType.Annotation)
            .map { it?.keys.orEmpty() }
            .map { relations ->
                coroutineScope {
                    relations.map { relatedEvent ->
                        async {
                            withTimeoutOrNull(1.minutes) { getTimelineEvent(roomId, relatedEvent).first() }
                        }
                    }.awaitAll()
                }.filterNotNull()
                    .mapNotNull {
                        val relatesTo = it.relatesTo as? RelatesTo.Annotation ?: return@mapNotNull null
                        val key = relatesTo.key ?: return@mapNotNull null
                        key to (it.eventId to it.sender)
                    }
                    .distinct()
                    .groupBy { it.first }
                    .mapValues { entry -> entry.value.map { it.second }.toSet() }
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
