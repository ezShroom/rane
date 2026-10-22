package me.shro.rane.greylist

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.slf4j.Logger
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.concurrent.Executors

/**
 * The web side of the greylist. Players open `/verify/<token>` from chat, are sent to Discord's
 * OAuth consent screen (scopes `identify guilds`), and come back to `/callback`. We read their
 * server list once, revoke the access token straight away, and report the result to [onResult].
 * No bot is involved and nothing from Discord is kept except the user's id and name.
 */
class DiscordOAuth(
    private val config: GreylistConfig,
    private val log: Logger,
    /** Looks up a pending verification by its token (any thread). */
    private val pending: (String) -> PendingVerification?,
    /** Called off the server thread once Discord has answered. */
    private val onResult: (PendingVerification, DiscordUser, Boolean) -> Unit,
) {
    data class PendingVerification(val token: String, val playerName: String)
    data class DiscordUser(val id: String, val name: String)

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private var server: HttpServer? = null
    private val base = config.publicUrl.trimEnd('/')
    private val redirectUri = "$base/callback"

    fun start() {
        val s = HttpServer.create(InetSocketAddress(config.bindAddress, config.port), 0)
        s.executor = Executors.newFixedThreadPool(4) { r -> Thread(r, "rane-greylist-web").apply { isDaemon = true } }
        s.createContext("/") { ex ->
            try {
                handle(ex)
            } catch (e: Exception) {
                log.warn("Greylist web request failed", e)
                runCatching { page(ex, 500, "Something went wrong", "Please try again from the link in Minecraft.") }
            } finally {
                ex.close()
            }
        }
        s.start()
        server = s
        log.info("Greylist verification listening on {}:{} (public URL {})", config.bindAddress, config.port, base)
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    fun verifyUrl(token: String) = "$base/verify/$token"

    private fun handle(ex: HttpExchange) {
        if (ex.requestMethod != "GET" && ex.requestMethod != "HEAD") return page(ex, 405, "Not allowed", "")
        val path = ex.requestURI.path
        when {
            path.startsWith("/verify/") -> {
                val p = pending(path.removePrefix("/verify/"))
                    ?: return page(ex, 410, "This link has expired", "Use the newest link in Minecraft chat, or run <code>/verify</code> to get a fresh one.")
                ex.responseHeaders.add("Location", authorizeUrl(p.token))
                ex.responseHeaders.add("Cache-Control", "no-store")
                ex.sendResponseHeaders(302, -1)
            }
            path == "/callback" -> callback(ex)
            path == "/" -> page(ex, 200, "${config.serverName} verification", "Open the link shown in Minecraft chat to verify.")
            else -> page(ex, 404, "Not found", "")
        }
    }

    private fun authorizeUrl(state: String) = "https://discord.com/oauth2/authorize?" + form(
        "client_id" to config.clientId,
        "response_type" to "code",
        "redirect_uri" to redirectUri,
        "scope" to "identify guilds",
        "state" to state,
        "prompt" to "none",
    )

    private fun callback(ex: HttpExchange) {
        val q = query(ex.requestURI.rawQuery)
        val p = q["state"]?.let(pending)
            ?: return page(ex, 410, "This link has expired", "Use the newest link in Minecraft chat, or run <code>/verify</code> to get a fresh one.")
        val code = q["code"]
            ?: return page(ex, 400, "Verification cancelled", "Discord didn't hand anything back. Use the link in Minecraft chat to try again.")

        val token = exchange(code) ?: return page(ex, 502, "Discord didn't accept that", "Use the link in Minecraft chat to try again.")
        val user: DiscordUser
        val guilds: Set<String>
        try {
            val me = api("/users/@me", token).asJsonObject
            user = DiscordUser(me["id"].asString, me["global_name"]?.takeUnless { it.isJsonNull }?.asString ?: me["username"].asString)
            guilds = (api("/users/@me/guilds", token) as JsonArray).map { it.asJsonObject["id"].asString }.toSet()
        } finally {
            revoke(token)
        }

        val allowed = config.requiredGuilds.any { it in guilds }
        onResult(p, user, allowed)
        if (allowed) {
            page(ex, 200, "You're in!", "<b>${esc(p.playerName)}</b> is verified. Head back to Minecraft and have fun.")
        } else {
            val invite = config.inviteUrl.takeIf { it.isNotBlank() }
                ?.let { " Join <a href=\"${esc(it)}\">our Discord</a>, then use the link in Minecraft chat again." }
                ?: " Join it, then use the link in Minecraft chat again."
            page(ex, 403, "You're not in our Discord", "Discord account <b>${esc(user.name)}</b> isn't in the required server.$invite")
        }
    }

    private fun exchange(code: String): String? {
        val res = http.send(
            HttpRequest.newBuilder(URI("$API/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", basicAuth())
                .POST(HttpRequest.BodyPublishers.ofString(form(
                    "grant_type" to "authorization_code", "code" to code, "redirect_uri" to redirectUri)))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (res.statusCode() != 200) {
            log.warn("Discord token exchange failed ({}): {}", res.statusCode(), res.body())
            return null
        }
        return JsonParser.parseString(res.body()).asJsonObject["access_token"].asString
    }

    private fun api(path: String, token: String) = http.send(
        HttpRequest.newBuilder(URI("$API$path")).header("Authorization", "Bearer $token").GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    ).let { res ->
        if (res.statusCode() != 200) error("Discord $path returned ${res.statusCode()}: ${res.body()}")
        JsonParser.parseString(res.body())
    }

    private fun revoke(token: String) {
        runCatching {
            http.send(
                HttpRequest.newBuilder(URI("$API/oauth2/token/revoke"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Authorization", basicAuth())
                    .POST(HttpRequest.BodyPublishers.ofString(form("token" to token, "token_type_hint" to "access_token")))
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        }.onFailure { log.warn("Could not revoke a Discord token", it) }
    }

    private fun basicAuth() = "Basic " + Base64.getEncoder().encodeToString("${config.clientId}:${config.clientSecret}".toByteArray())

    private fun page(ex: HttpExchange, status: Int, title: String, body: String) {
        val html = """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(title)} · ${esc(config.serverName)}</title>
            <style>
              body{margin:0;min-height:100vh;display:grid;place-items:center;background:#1e1b18;color:#eee;
                font:17px/1.5 system-ui,sans-serif}
              main{max-width:30rem;padding:2rem;margin:1rem;background:#2b2622;border-radius:12px;
                box-shadow:0 0 0 1px #3d362f}
              h1{margin:0 0 .5rem;font-size:1.5rem;color:${if (status < 400) "#8fd16a" else "#f0b35b"}}
              a{color:#7fc4ff} code{background:#3d362f;padding:.1em .35em;border-radius:4px}
            </style></head><body><main><h1>${esc(title)}</h1><p>$body</p></main></body></html>
        """.trimIndent().toByteArray()
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.responseHeaders.add("Referrer-Policy", "no-referrer")
        if (ex.requestMethod == "HEAD") return ex.sendResponseHeaders(status, -1)
        ex.sendResponseHeaders(status, html.size.toLong())
        ex.responseBody.use { it.write(html) }
    }

    private companion object {
        /** Overridable for testing against a fake Discord. */
        val API: String = System.getProperty("rane.discordApi", "https://discord.com/api/v10")

        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        fun form(vararg pairs: Pair<String, String>) =
            pairs.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }

        fun query(raw: String?) = raw.orEmpty().split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
        }
    }
}
