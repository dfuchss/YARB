package org.fuchss.matrix.yarb

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.fuchss.matrix.bots.IConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

/**
 * This is the configuration template of YARB.
 * @param[prefix] the command prefix the bot listens to. By default, "mensa"
 * @param[baseUrl] the base url of the matrix server the bot shall use
 * @param[username] the username of the bot's account
 * @param[password] the password of the bot's account
 * @param[dataDirectory] the path to the databases and media folder
 * @param[admins] the matrix ids of the admins. E.g. "@user:invalid.domain"
 * @param[offsetInMinutes] the offset for reminders in minutes. E.g. "5" means that the reminder will be sent 5 minutes before the actual time
 */
data class Config(
    @JsonProperty override val prefix: String = "mensa",
    @JsonProperty override val baseUrl: String,
    @JsonProperty override val username: String,
    @JsonProperty override val password: String,
    @JsonProperty override val dataDirectory: String,
    @JsonProperty override val admins: List<String>,
    @JsonProperty override val users: List<String> = listOf(),
    @JsonProperty("offset_in_minutes") val offsetInMinutes: Long = 0
) : IConfig {
    companion object {
        private val log: Logger = LoggerFactory.getLogger(Config::class.java)

        /**
         * Load the config from the file path. You can set "CONFIG_PATH" in the environment to override the default location ("./config.json").
         */
        fun load(): Config {
            val configPath = System.getenv("CONFIG_PATH") ?: "./config.json"
            val configFile = File(configPath)
            if (!configFile.exists()) {
                error("Config ${configFile.absolutePath} does not exist!")
            }

            val config: Config = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule()).readValue(configFile)
            log.info("Loaded config ${configFile.absolutePath}")
            config.validate()
            return config
        }
    }

    override fun validate() {
        super.validate()
        if (offsetInMinutes < 0) {
            error("Offset must be greater or equal to 0.")
        }
    }
}
