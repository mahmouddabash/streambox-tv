package com.streambox.tv.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Phone as a remote: a small web page served by StreamBox on the home Wi-Fi (no internet, no
 * account). The address carries a random code, so only someone who scanned the TV's QR code can use it.
 */
object RemoteServer {
    @Volatile private var server: ServerSocket? = null
    @Volatile var url: String? = null
        private set
    private var token = ""
    private val main = Handler(Looper.getMainLooper())

    /** The screen StreamBox shows right now (kept by App), so remote keys go to it. */
    @Volatile var foreground: Activity? = null

    val running get() = server != null

    fun start(context: Context): String? {
        url?.let { return it }
        val ip = PairingServer.localIp() ?: return null
        val app = context.applicationContext
        token = (1000..9999).random().toString()
        val s = try {
            ServerSocket(8686)
        } catch (_: Exception) {
            try { ServerSocket(0) } catch (_: Exception) { return null }
        }
        server = s
        url = "http://$ip:${s.localPort}/r$token"
        thread(isDaemon = true, name = "sb-remote") {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { handle(app, c) }
            }
        }
        return url
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        url = null
    }

    private fun handle(app: Context, client: Socket) {
        try {
            client.soTimeout = 10_000
            val input = BufferedInputStream(client.getInputStream())
            val line = readLine(input) ?: return
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
            }
            val target = line.split(" ").getOrNull(1) ?: return
            val path = target.substringBefore('?')
            val q = target.substringAfter('?', "").split('&').filter { '=' in it }
                .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            val base = "/r$token"
            when {
                path == base -> respond(client, "text/html; charset=utf-8", page())
                path == "$base/key" -> { key(q["k"] ?: ""); respond(client, "text/plain", "ok") }
                path == "$base/vol" -> {
                    val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        when (q["d"]) { "up" -> AudioManager.ADJUST_RAISE; "down" -> AudioManager.ADJUST_LOWER; else -> AudioManager.ADJUST_TOGGLE_MUTE },
                        AudioManager.FLAG_SHOW_UI)
                    respond(client, "text/plain", "ok")
                }
                path == "$base/search" -> {
                    val text = q["q"].orEmpty()
                    main.post {
                        val a = foreground ?: return@post
                        a.startActivity(Intent(a, com.streambox.tv.ui.SearchActivity::class.java)
                            .putExtra(com.streambox.tv.ui.SearchActivity.EXTRA_QUERY, text))
                    }
                    respond(client, "text/plain", "ok")
                }
                path == "$base/channels" -> {
                    val arr = org.json.JSONArray()
                    if (Repo.loaded) Repo.favouriteChannels(60).ifEmpty { Repo.recentChannelList(30) }
                        .forEach { arr.put(org.json.JSONObject().put("id", it.id).put("name", it.name)) }
                    respond(client, "application/json", arr.toString())
                }
                path == "$base/play" -> {
                    val id = q["id"].orEmpty()
                    main.post {
                        val a = foreground ?: return@post
                        val c = Repo.channel(id) ?: return@post
                        val list = Repo.favouriteChannels(200).ifEmpty { listOf(c) }
                        val i = list.indexOfFirst { it.id == id }.coerceAtLeast(0)
                        com.streambox.tv.ui.Ui.play(a, list.map { PlayItem("", it.name, Repo.urlFor(it), true, it.id) }, i)
                    }
                    respond(client, "text/plain", "ok")
                }
                else -> respond(client, "text/plain", "Scan the QR code on the TV again.", 404)
            }
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private val keys = mapOf(
        "up" to KeyEvent.KEYCODE_DPAD_UP, "down" to KeyEvent.KEYCODE_DPAD_DOWN,
        "left" to KeyEvent.KEYCODE_DPAD_LEFT, "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "ok" to KeyEvent.KEYCODE_DPAD_CENTER, "back" to KeyEvent.KEYCODE_BACK,
        "play" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "menu" to KeyEvent.KEYCODE_MENU,
        "chup" to KeyEvent.KEYCODE_CHANNEL_UP, "chdown" to KeyEvent.KEYCODE_CHANNEL_DOWN,
        "rew" to KeyEvent.KEYCODE_MEDIA_REWIND, "ff" to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        "next" to KeyEvent.KEYCODE_MEDIA_NEXT, "prev" to KeyEvent.KEYCODE_MEDIA_PREVIOUS
    ) + (0..9).associate { "$it" to KeyEvent.KEYCODE_0 + it }

    private fun key(name: String) {
        val code = keys[name] ?: return
        main.post {
            val a = foreground ?: return@post
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun respond(client: Socket, type: String, body: String, code: Int = 200) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Not Found"}\r\nContent-Type: $type\r\n" +
            "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
    }

    private fun page(): String = """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>StreamBox remote</title><style>
body{margin:0;font-family:system-ui,sans-serif;background:#10142b;color:#f5f7ff;user-select:none;-webkit-user-select:none}
.wrap{max-width:420px;margin:0 auto;padding:16px}
h1{font-size:18px;margin:4px 0 14px;opacity:.8}
.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}
button{border:0;border-radius:18px;background:#262d55;color:#fff;font-size:20px;padding:18px 0;touch-action:manipulation}
button:active{background:#5a55d6}
.ok{background:#5a55d6;font-weight:700}.row{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-top:10px}
input{width:100%;box-sizing:border-box;border-radius:14px;border:0;padding:14px;font-size:17px;margin-top:14px;background:#1b2147;color:#fff}
.ch{display:block;width:100%;text-align:left;font-size:16px;padding:14px;margin-top:8px}
</style></head><body><div class="wrap"><h1>▶ StreamBox remote</h1>
<div class="grid">
<button onclick="k('back')">↩</button><button onclick="k('up')">▲</button><button onclick="k('menu')">☰</button>
<button onclick="k('left')">◀</button><button class="ok" onclick="k('ok')">OK</button><button onclick="k('right')">▶</button>
<button onclick="k('rew')">⏪</button><button onclick="k('down')">▼</button><button onclick="k('ff')">⏩</button>
</div>
<div class="row"><button onclick="k('play')">⏯</button><button onclick="k('chdown')">CH−</button><button onclick="k('chup')">CH+</button><button onclick="v('mute')">🔇</button></div>
<div class="row"><button onclick="k('prev')">⏮</button><button onclick="k('next')">⏭</button><button onclick="v('down')">🔉</button><button onclick="v('up')">🔊</button></div>
<input id="q" placeholder="Search channels, movies, series…" enterkeyhint="search">
<div id="chs"></div></div>
<script>
function k(n){fetch(location.pathname+'/key?k='+n)}
function v(d){fetch(location.pathname+'/vol?d='+d)}
document.getElementById('q').addEventListener('keydown',function(e){if(e.key==='Enter'){fetch(location.pathname+'/search?q='+encodeURIComponent(this.value));this.blur()}});
fetch(location.pathname+'/channels').then(r=>r.json()).then(l=>{var d=document.getElementById('chs');
l.forEach(c=>{var b=document.createElement('button');b.className='ch';b.textContent='▶  '+c.name;b.onclick=function(){fetch(location.pathname+'/play?id='+encodeURIComponent(c.id))};d.appendChild(b)})});
</script></body></html>"""
}
