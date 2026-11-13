package com.streambox.tv.data

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Tiny one-page web server on the local network. The TV shows its address as a QR code;
 * a phone on the same Wi-Fi opens it, types the provider details, and they arrive in [onSubmit].
 * A random token in the path keeps other devices on the network from posting by accident.
 */
class PairingServer(private val onSubmit: (Map<String, String>) -> Unit) {

    private var server: ServerSocket? = null

    @Volatile
    private var running = false

    val token: String = (1..8).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")

    /** Starts listening and returns the URL to show, or null when the TV has no local network address. */
    fun start(): String? {
        val ip = localIp() ?: return null
        val s = try {
            ServerSocket(0)
        } catch (_: Exception) {
            return null
        }
        server = s
        running = true
        thread(isDaemon = true, name = "pairing-accept") {
            while (running) {
                val client = try {
                    s.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true, name = "pairing-client") { handle(client) }
            }
        }
        return "http://$ip:${s.localPort}/$token"
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 10_000
            val input = BufferedInputStream(client.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')

            var contentLength = 0
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0 && line.substring(0, i).trim().equals("Content-Length", ignoreCase = true)) {
                    contentLength = line.substring(i + 1).trim().toIntOrNull() ?: 0
                }
            }

            if (path != "/$token") {
                respond(client, 404, page("Not found", "<p>Scan the QR code on the TV again.</p>"))
                return
            }
            if (method == "POST") {
                val len = contentLength.coerceIn(0, 16 * 1024)
                val body = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = input.read(body, read, len - read)
                    if (n < 0) break
                    read += n
                }
                val form = parseForm(String(body, 0, read, Charsets.UTF_8))
                onSubmit(form)
                respond(client, 200, page("Sent to your TV", "<p>Check the TV screen: it is signing in now.</p><p><a href=\"/$token\">Send again</a></p>"))
            } else {
                respond(client, 200, formPage())
            }
        } catch (_: Exception) {
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
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

    private fun parseForm(body: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (pair in body.split("&")) {
            val i = pair.indexOf('=')
            if (i <= 0) continue
            try {
                out[URLDecoder.decode(pair.substring(0, i), "UTF-8")] = URLDecoder.decode(pair.substring(i + 1), "UTF-8").trim()
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun respond(client: Socket, code: Int, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val status = if (code == 200) "200 OK" else "404 Not Found"
        val head = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        val out = client.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun page(title: String, body: String): String = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>StreamBox TV</title>
<style>
body{font-family:system-ui,sans-serif;background:#0e1420;color:#f2f5fa;margin:0;padding:20px;}
.card{max-width:460px;margin:0 auto;}
h1{font-size:22px;margin:8px 0 16px;}
label{display:block;font-size:13px;color:#9aa7bd;margin:12px 0 4px;}
input,select{width:100%;box-sizing:border-box;padding:13px;font-size:16px;border-radius:8px;border:1px solid #22304a;background:#172033;color:#f2f5fa;}
button{width:100%;margin-top:20px;padding:14px;font-size:17px;font-weight:600;border:0;border-radius:24px;background:#2f9bff;color:#fff;}
a{color:#2f9bff;} p{color:#9aa7bd;line-height:1.5;}
</style></head><body><div class="card"><h1>$title</h1>$body</div></body></html>"""

    private fun formPage(): String = page(
        "Add a profile to your TV",
        """<form method="post" action="/$token" autocomplete="off">
<label>Type</label>
<select name="type" id="type" onchange="sw()">
<option value="xtream">Xtream Codes login</option>
<option value="m3u">M3U playlist URL</option>
</select>
<label>Profile name (optional)</label><input name="name">
<div id="x">
<label>Server URL</label><input name="server" inputmode="url" autocapitalize="none" placeholder="http://example.com:8080">
<label>Username</label><input name="username" autocapitalize="none" autocorrect="off">
<label>Password</label><input name="password" autocapitalize="none" autocorrect="off">
</div>
<div id="m" style="display:none">
<label>Playlist URL</label><input name="m3u" inputmode="url" autocapitalize="none" placeholder="http://...m3u">
<label>EPG URL (optional)</label><input name="epg" inputmode="url" autocapitalize="none">
</div>
<button type="submit">Send to TV</button>
</form>
<p>Your details go straight to the TV over your home network. Nothing is sent anywhere else.</p>
<script>
function sw(){var t=document.getElementById('type').value;
document.getElementById('x').style.display=t=='xtream'?'block':'none';
document.getElementById('m').style.display=t=='m3u'?'block':'none';}
</script>"""
    )

    companion object {
        /** First private IPv4 address of an active interface (Wi-Fi or Ethernet). */
        fun localIp(): String? {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
                for (ni in interfaces) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (addr in ni.inetAddresses) {
                        if (addr is Inet4Address && addr.isSiteLocalAddress) return addr.hostAddress
                    }
                }
            } catch (_: Exception) {
            }
            return null
        }
    }
}
