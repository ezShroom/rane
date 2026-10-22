package me.shro.rane.greylist

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A deliberately small Discord bot client: gateway connection (heartbeat, resume, reconnect) plus
 * the REST calls needed for guild slash commands. No intents are requested; slash command
 * interactions arrive regardless.
 */
class DiscordGateway(
    private val token: String,
    private val onReady: (applicationId: String, guildIds: List<String>) -> Unit,
    private val onInteraction: (JsonObject) -> Unit,
) {
    private val log = LoggerFactory.getLogger("rane/discord")
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "rane-discord").apply { isDaemon = true } }

    private var ws: WebSocket? = null
    private var heartbeat: ScheduledFuture<*>? = null
    private var seq: Long? = null
    private var sessionId: String? = null
    private var resumeUrl: String? = null
    private var acked = true
    private var backoffSec = 2L
    @Volatile private var stopped = false
    var applicationId: String? = null
        private set

    fun start() {
        exec.execute { connect(resume = false) }
    }

    fun stop() {
        stopped = true
        heartbeat?.cancel(false)
        runCatching { ws?.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown")?.get(3, TimeUnit.SECONDS) }
        exec.shutdownNow()
    }

    // ------------------------------------------------------------------ REST

    fun rest(method: String, path: String, body: JsonElement? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create("https://discord.com/api/v10$path"))
            .header("Authorization", "Bot $token")
            .header("User-Agent", "DiscordBot (https://github.com/ezShroom/rane, 1)")
            .timeout(Duration.ofSeconds(20))
        val pub = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body.toString())
        if (body != null) b.header("Content-Type", "application/json")
        val res = http.send(b.method(method, pub).build(), HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() == 429) {
            val wait = runCatching { JsonParser.parseString(res.body()).asJsonObject["retry_after"].asDouble }.getOrDefault(1.0)
            Thread.sleep((wait * 1000).toLong() + 50)
            return rest(method, path, body)
        }
        if (res.statusCode() >= 400) log.warn("Discord {} {} -> {}: {}", method, path, res.statusCode(), res.body().take(300))
        return res
    }

    fun restAsync(method: String, path: String, body: JsonElement? = null) {
        CompletableFuture.runAsync({ runCatching { rest(method, path, body) }.onFailure { log.warn("Discord request failed", it) } }, exec)
    }

    /** Replies to an interaction with a message only the invoking user can see. */
    fun replyEphemeral(interaction: JsonObject, content: String) {
        val body = JsonObject().apply {
            addProperty("type", 4)
            add("data", JsonObject().apply {
                addProperty("content", content)
                addProperty("flags", 64)
                add("allowed_mentions", JsonObject().apply { add("parse", JsonArray()) })
            })
        }
        restAsync("POST", "/interactions/${interaction["id"].asString}/${interaction["token"].asString}/callback", body)
    }

    // ------------------------------------------------------------------ gateway

    private fun connect(resume: Boolean) {
        if (stopped) return
        try {
            val base = if (resume && resumeUrl != null) resumeUrl!! else {
                val r = rest("GET", "/gateway/bot")
                if (r.statusCode() == 401) {
                    log.error("Discord rejected the bot token; the greylist bot is off until it's fixed")
                    return
                }
                JsonParser.parseString(r.body()).asJsonObject["url"].asString
            }
            val listener = Listener(resume)
            ws = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(20))
                .buildAsync(URI.create("$base/?v=10&encoding=json"), listener).get(30, TimeUnit.SECONDS)
            backoffSec = 2
        } catch (e: Exception) {
            log.warn("Could not connect to Discord ({}), retrying in {}s", e.message, backoffSec)
            scheduleReconnect(resume)
        }
    }

    private fun scheduleReconnect(resume: Boolean) {
        if (stopped) return
        heartbeat?.cancel(false)
        val delay = backoffSec
        backoffSec = minOf(backoffSec * 2, 120)
        exec.schedule({ connect(resume) }, delay, TimeUnit.SECONDS)
    }

    private fun send(op: Int, d: JsonElement?) {
        val payload = JsonObject().apply {
            addProperty("op", op)
            add("d", d)
        }
        ws?.sendText(payload.toString(), true)
    }

    private inner class Listener(val resume: Boolean) : WebSocket.Listener {
        private val buf = StringBuilder()

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buf.append(data)
            if (last) {
                val text = buf.toString()
                buf.setLength(0)
                exec.execute { runCatching { handle(JsonParser.parseString(text).asJsonObject) }.onFailure { log.warn("Bad gateway message", it) } }
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            log.info("Discord gateway closed ({} {})", statusCode, reason)
            // 4004 auth failed, 4010-4014 config errors: don't hammer Discord.
            if (statusCode == 4004 || statusCode in 4010..4014) {
                log.error("Discord closed the connection permanently ({}); check the bot token", statusCode)
                return null
            }
            val canResume = statusCode !in setOf(4007, 4009) && sessionId != null
            if (!canResume) { sessionId = null; seq = null }
            scheduleReconnect(canResume)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            log.warn("Discord gateway error: {}", error.message)
            scheduleReconnect(sessionId != null)
        }

        private fun handle(msg: JsonObject) {
            val op = msg["op"].asInt
            msg["s"]?.takeUnless { it.isJsonNull }?.let { seq = it.asLong }
            when (op) {
                10 -> { // HELLO
                    val interval = msg["d"].asJsonObject["heartbeat_interval"].asLong
                    acked = true
                    heartbeat?.cancel(false)
                    heartbeat = exec.scheduleAtFixedRate({
                        if (!acked) {
                            log.warn("Discord heartbeat not acknowledged; reconnecting")
                            ws?.abort()
                            scheduleReconnect(true)
                            return@scheduleAtFixedRate
                        }
                        acked = false
                        send(1, seq?.let { com.google.gson.JsonPrimitive(it) })
                    }, (interval * Math.random()).toLong(), interval, TimeUnit.MILLISECONDS)
                    if (resume && sessionId != null) {
                        send(6, JsonObject().apply {
                            addProperty("token", token)
                            addProperty("session_id", sessionId)
                            addProperty("seq", seq)
                        })
                    } else {
                        send(2, JsonObject().apply {
                            addProperty("token", token)
                            addProperty("intents", 0)
                            add("properties", JsonObject().apply {
                                addProperty("os", "linux")
                                addProperty("browser", "rane")
                                addProperty("device", "rane")
                            })
                        })
                    }
                }
                11 -> acked = true
                1 -> send(1, seq?.let { com.google.gson.JsonPrimitive(it) })
                7 -> { ws?.sendClose(4000, "reconnect"); scheduleReconnect(true) }
                9 -> { // invalid session
                    val resumable = msg["d"]?.takeUnless { it.isJsonNull }?.asBoolean ?: false
                    if (!resumable) { sessionId = null; seq = null }
                    ws?.sendClose(4000, "invalid session")
                    scheduleReconnect(resumable)
                }
                0 -> dispatch(msg["t"].asString, msg["d"])
            }
        }

        private fun dispatch(type: String, d: JsonElement) {
            when (type) {
                "READY" -> {
                    val o = d.asJsonObject
                    sessionId = o["session_id"].asString
                    resumeUrl = o["resume_gateway_url"].asString
                    applicationId = o["application"].asJsonObject["id"].asString
                    val guilds = o["guilds"].asJsonArray.map { it.asJsonObject["id"].asString }
                    log.info("Discord bot connected as {}", o["user"].asJsonObject["username"].asString)
                    onReady(applicationId!!, guilds)
                }
                "RESUMED" -> log.info("Discord session resumed")
                "INTERACTION_CREATE" -> onInteraction(d.asJsonObject)
            }
        }
    }
}
