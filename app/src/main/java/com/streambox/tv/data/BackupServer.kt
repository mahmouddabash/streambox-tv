package com.streambox.tv.data

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Tiny web page the phone opens (QR code on the TV): download the backup file, or upload one to restore.
 * Only reachable on the home network, only with the random link, only while the TV shows the QR code.
 */
class BackupServer(
    private val backupText: String?,
    private val onUpload: (String) -> Unit
) {
    private var server: ServerSocket? = null

    @Volatile
    private var running = false
    private val token = (1..10).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")

    fun start(): String? {
        val ip = PairingServer.localIp() ?: return null
        val s = try { ServerSocket(0) } catch (_: Exception) { return null }
        server = s
        running = true
        thread(isDaemon = true, name = "backup-accept") {
            while (running) {
                val c = try { s.accept() } catch (_: Exception) { break }
                thread(isDaemon = true, name = "backup-client") { handle(c) }
            }
        }
        return "http://$ip:${s.localPort}/$token"
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) { }
        server = null
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 20_000
            val input = BufferedInputStream(client.getInputStream())
            val parts = (readLine(input) ?: return).split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')
            var length = 0
            var contentType = ""
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i <= 0) continue
                val name = line.substring(0, i).trim()
                val value = line.substring(i + 1).trim()
                if (name.equals("Content-Length", true)) length = value.toIntOrNull() ?: 0
                if (name.equals("Content-Type", true)) contentType = value
            }
            when {
                path == "/$token" && method == "GET" -> html(client, page())
                path == "/$token/StreamBox.sbk" && backupText != null -> file(client, backupText)
                path == "/$token/restore" && method == "POST" -> {
                    val body = ByteArray(length.coerceIn(0, 4 * 1024 * 1024))
                    var read = 0
                    while (read < body.size) {
                        val n = input.read(body, read, body.size - read)
                        if (n < 0) break
                        read += n
                    }
                    val text = extractUpload(String(body, 0, read, Charsets.UTF_8), contentType)
                    if (text != null && Backup.looksValid(text)) {
                        onUpload(text)
                        html(client, wrap("Sent to your TV", "<p>Now enter the backup password on the TV.</p>"))
                    } else {
                        html(client, wrap("Not a StreamBox backup", "<p>Choose a <b>.sbk</b> file made by StreamBox.</p><p><a href=\"/$token\">Try again</a></p>"))
                    }
                }
                else -> html(client, wrap("Not found", "<p>Scan the QR code on the TV again.</p>"), 404)
            }
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) { }
        }
    }

    /** Pulls the file's text out of a multipart/form-data body (the backup is plain text). */
    private fun extractUpload(body: String, contentType: String): String? {
        val boundary = contentType.substringAfter("boundary=", "").trim('"')
        if (boundary.isEmpty()) return body.lineSequence().firstOrNull { Backup.looksValid(it) }
        val start = body.indexOf("\r\n\r\n")
        if (start < 0) return null
        val end = body.indexOf("\r\n--$boundary", start)
        return body.substring(start + 4, if (end > 0) end else body.length).trim()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length < 8192) sb.append(b.toChar())
        }
    }

    private fun html(client: Socket, html: String, code: Int = 200) =
        send(client, if (code == 200) "200 OK" else "404 Not Found", "text/html; charset=utf-8", html.toByteArray(), "")

    private fun file(client: Socket, text: String) =
        send(client, "200 OK", "application/octet-stream", text.toByteArray(),
            "Content-Disposition: attachment; filename=\"StreamBox-backup.sbk\"\r\n")

    private fun send(client: Socket, status: String, type: String, bytes: ByteArray, extra: String) {
        val head = "HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n$extra" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(bytes)
            flush()
        }
    }

    private fun page(): String {
        val download = if (backupText != null)
            "<h2>Save a backup</h2><p><a class=\"btn\" href=\"/$token/StreamBox.sbk\" download>Download backup file</a></p>" +
                "<p class=\"hint\">Keep the file and its password safe: it contains your logins.</p>"
        else ""
        val upload = "<h2>Restore a backup</h2><form method=\"post\" action=\"/$token/restore\" enctype=\"multipart/form-data\">" +
            "<input type=\"file\" name=\"file\" accept=\".sbk\" required><button class=\"btn\" type=\"submit\">Send to TV</button></form>"
        return wrap("StreamBox backup", download + upload)
    }

    private fun wrap(title: String, body: String) = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title</title><style>
body{font-family:system-ui,sans-serif;background:#0b1020;color:#f5f7ff;margin:0;padding:24px;max-width:520px}
h1{font-size:22px}h2{font-size:17px;margin-top:28px}.hint{color:#8e9ab8;font-size:14px}
.btn{display:inline-block;background:linear-gradient(90deg,#7c5cff,#00d4ff);color:#fff;border:0;border-radius:24px;padding:12px 22px;font-size:16px;text-decoration:none;margin-top:8px}
input[type=file]{display:block;margin:12px 0;color:#f5f7ff}a{color:#00d4ff}
</style></head><body><h1>$title</h1>$body</body></html>"""
}
