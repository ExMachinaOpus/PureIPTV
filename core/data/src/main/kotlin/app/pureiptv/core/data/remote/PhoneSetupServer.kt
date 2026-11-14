package app.pureiptv.core.data.remote

import app.pureiptv.core.data.source.SourceInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/** What the TV shows while waiting: the address to open on the phone. */
data class PhoneSetupSession(val url: String)

sealed interface PhoneSetupEvent {
    data class Ready(val session: PhoneSetupSession) : PhoneSetupEvent
    /** The phone sent a source; the TV checks and adds it. */
    data class Received(val input: SourceInput) : PhoneSetupEvent
    /** No usable network (e.g. not on Wi-Fi/Ethernet). */
    data object NoNetwork : PhoneSetupEvent
}

/**
 * "Set up from your phone": a tiny HTTP server on the local network that serves one form and
 * receives a playlist link or Xtream login, so nobody types a password with a remote.
 *
 * Runs only while the setup screen is open (collect [run]). The address contains a random token,
 * so other devices on the network can't post to it by guessing the port. It listens on the local
 * network address only, and a request gets a few seconds, a few headers and one of a few slots:
 * nothing on the network can tie up the app's threads through it.
 *
 * The form travels over plain HTTP inside the local network (the TV has no certificate a phone
 * would trust). That is said in the privacy policy.
 */
class PhoneSetupServer @Inject constructor() {

    fun run(): Flow<PhoneSetupEvent> = callbackFlow {
        val address = lanAddress()
        if (address == null) {
            send(PhoneSetupEvent.NoNetwork)
            close()
            return@callbackFlow
        }
        val token = token()
        // Only on the address the QR code points at, not on every interface the device has.
        val server = ServerSocket(0, 8, address)
        val open = AtomicInteger()
        val url = "http://${address.hostAddress}:${server.localPort}/$token"
        send(PhoneSetupEvent.Ready(PhoneSetupSession(url)))

        val acceptLoop = launch(Dispatchers.IO) {
            while (!server.isClosed) {
                val socket = runCatching { runInterruptible { server.accept() } }.getOrNull() ?: break
                if (open.incrementAndGet() > MAX_CONNECTIONS) {
                    open.decrementAndGet()
                    runCatching { socket.close() }
                    continue
                }
                launch(Dispatchers.IO) {
                    // The read timeout is per read; this ends a request that trickles in slowly.
                    val deadline = launch {
                        delay(REQUEST_DEADLINE_MS)
                        runCatching { socket.close() }
                    }
                    try {
                        socket.use { s ->
                            s.soTimeout = 5_000
                            val input = runCatching { handle(s, token) }.getOrNull()
                            if (input != null) trySend(PhoneSetupEvent.Received(input))
                        }
                    } finally {
                        deadline.cancel()
                        open.decrementAndGet()
                    }
                }
            }
        }
        awaitClose {
            acceptLoop.cancel()
            runCatching { server.close() }
        }
    }.flowOn(Dispatchers.IO)

    /** Serves the form (GET) or reads a submission (POST). Returns the submitted source, if any. */
    private fun handle(socket: Socket, token: String): SourceInput? {
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        val requestLine = readLine(input) ?: return null
        val (method, path) = requestLine.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        var contentLength = 0
        var headers = 0
        while (true) {
            val header = readLine(input) ?: return null
            if (header.isEmpty()) break
            if (++headers > MAX_HEADERS) return null
            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        if (path.substringBefore('?') != "/$token") {
            respond(out, 404, "<p>This setup link has expired. Scan the code on your TV again.</p>")
            return null
        }
        if (method == "GET") {
            respond(out, 200, FORM)
            return null
        }
        if (method != "POST" || contentLength !in 1..MAX_BODY) {
            respond(out, 400, FORM)
            return null
        }
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n < 0) break
            read += n
        }
        val form = parseForm(String(body, 0, read, Charsets.UTF_8))
        val link = form["link"]?.trim().orEmpty()
        val guide = form["guide"]?.trim().orEmpty()
        val server = form["server"]?.trim().orEmpty()
        val user = form["username"]?.trim().orEmpty()
        val pass = form["password"].orEmpty()
        val source = when {
            link.isNotEmpty() -> SourceInput.Link(link, guide.ifEmpty { null })
            server.isNotEmpty() && user.isNotEmpty() && pass.isNotEmpty() -> SourceInput.Login(server, user, pass)
            else -> null
        }
        respond(out, 200, if (source != null) DONE else FORM.replace("<!--error-->", "<p class=e>Fill in a link, or server, username and password.</p>"))
        return source
    }

    private fun respond(out: OutputStream, code: Int, html: String) {
        val body = page(html).toByteArray(Charsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 404 -> "Not Found"; else -> "Bad Request" }
        out.write("HTTP/1.1 $code $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8_192) return null
            sb.append(c.toChar())
        }
    }

    private fun parseForm(body: String): Map<String, String> = body.split('&').mapNotNull { pair ->
        val k = pair.substringBefore('=')
        if (k.isEmpty()) null else dec(k) to dec(pair.substringAfter('=', ""))
    }.toMap()

    private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun token(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    /** First private IPv4 address of an active interface (Wi-Fi or Ethernet). */
    private fun lanAddress(): InetAddress? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    }.getOrNull()

    private companion object {
        const val MAX_BODY = 16 * 1024
        const val MAX_HEADERS = 64
        const val MAX_CONNECTIONS = 8
        const val REQUEST_DEADLINE_MS = 10_000L

        fun page(content: String) = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>PureIPTV setup</title>
<style>body{font-family:system-ui,sans-serif;background:#0e0f13;color:#ecedef;margin:0;padding:24px;max-width:480px}
h1{font-size:22px}h2{font-size:16px;margin-top:28px;color:#a9adb7}input{width:100%;box-sizing:border-box;padding:12px;margin:6px 0;
border-radius:8px;border:1px solid #444;background:#181a20;color:#ecedef;font-size:16px}
button{width:100%;padding:14px;border:0;border-radius:24px;background:#8ab4ff;color:#0b1b3a;font-size:16px;font-weight:600;margin-top:12px}
.e{color:#ff8a80}</style></head><body>$content</body></html>"""

        const val FORM = """<h1>Add a source to your TV</h1><!--error-->
<form method="post"><h2>Playlist link</h2><input name="link" type="url" placeholder="http://example.com/playlist.m3u" autocomplete="off">
<input name="guide" type="url" placeholder="TV guide link (optional)" autocomplete="off">
<h2>Or server and login</h2><input name="server" placeholder="http://example.com:8080" autocomplete="off">
<input name="username" placeholder="Username" autocomplete="off" autocapitalize="none">
<input name="password" type="password" placeholder="Password">
<button type="submit">Send to TV</button></form>"""

        const val DONE = "<h1>Sent!</h1><p>Look at your TV: it's checking the source now. You can close this page.</p>"
    }
}
