package com.streamcam.app

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal HTTP/1.1 server hosting the OBS-dockable gimbal control page.
 * Hand-rolled (no HTTP dependency) — thread-per-connection, connections always closed.
 * LAN-only, token-authenticated: every route except GET / and POST /api/login requires
 * a cookie (streamcam_token), Authorization: Bearer, or ?token= matching [authToken].
 */
class GimbalDockServer(
    private val listener: Listener,
    private val authToken: String,
    private val port: Int = PORT,
) {

    interface Listener {
        fun onDockMove(yawDps: Float, pitchDps: Float)
        fun onDockStop()
        fun onDockCenter()
        /** @return null on success, or an error message surfaced to the page */
        fun onDockTrackAt(x: Float, y: Float): String?
        fun onDockTrackStop()
        fun getDockStatusJson(clients: Int): String
        fun getDockDetectionsJson(): String
        /** @return the latest preview frame, or null when unavailable (answered as 503) */
        fun getDockPreviewJpeg(): ByteArray?
        /** @return null if the script was started, otherwise an error message (answered as 409) */
        fun onScriptRun(code: String?, name: String?): String?
        fun onScriptStop()
        fun getScriptStatusJson(): String
        fun getScriptsJson(): String
        /** @return the script source, or null if no such script */
        fun getScriptCode(name: String): String?
        /** @return null on success, otherwise an error message */
        fun saveScript(name: String, code: String): String?
        fun deleteScript(name: String): Boolean
    }

    companion object {
        private const val TAG = "GimbalDockServer"
        const val PORT = 8556
        private const val CLIENT_WINDOW_MS = 15_000L
        private const val PREVIEW_IDLE_MS = 10_000L
        private const val MAX_BODY_BYTES = 256 * 1024

        fun jsonEscape(s: String): String = buildString(s.length + 8) {
            for (c in s) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(if (c < ' ') ' ' else c)
                }
            }
        }
    }

    @Volatile private var running = false
    @Volatile private var lastPreviewRequestMs = 0L
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clientActivity = ConcurrentHashMap<String, Long>()

    fun isPreviewWanted(): Boolean =
        lastPreviewRequestMs > 0 &&
            System.currentTimeMillis() - lastPreviewRequestMs < PREVIEW_IDLE_MS

    fun recentClients(): Int {
        val now = System.currentTimeMillis()
        val it = clientActivity.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > CLIENT_WINDOW_MS) it.remove()
        }
        return clientActivity.size
    }

    fun getDockUrl(): String = "http://${RtspStreamServer.getDeviceIpAddress()}:$port/"

    fun start() {
        if (running) return
        val server = ServerSocket(port)
        serverSocket = server
        running = true
        acceptThread = Thread {
            while (running) {
                try {
                    val socket = server.accept()
                    socket.soTimeout = 10_000
                    clientActivity[socket.inetAddress?.hostAddress ?: "?"] =
                        System.currentTimeMillis()
                    Thread { handleConnection(socket) }.apply {
                        isDaemon = true
                        name = "GimbalDockClient"
                        start()
                    }
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "Accept failed", e)
                }
            }
        }.apply {
            isDaemon = true
            name = "GimbalDockServer"
            start()
        }
        Log.i(TAG, "Dock server listening on port $port")
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        Log.i(TAG, "Dock server stopped")
    }

    private fun handleConnection(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            val query = parts[1].substringAfter('?', "")

            var contentLength = 0
            var cookieHeader: String? = null
            var authHeader: String? = null
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                } else if (line.startsWith("Cookie:", ignoreCase = true)) {
                    cookieHeader = line.substringAfter(':').trim()
                } else if (line.startsWith("Authorization:", ignoreCase = true)) {
                    authHeader = line.substringAfter(':').trim()
                }
            }

            val bodyBytes = ByteArray(contentLength.coerceIn(0, MAX_BODY_BYTES))
            var read = 0
            while (read < bodyBytes.size) {
                val n = input.read(bodyBytes, read, bodyBytes.size - read)
                if (n < 0) break
                read += n
            }
            val body = String(bodyBytes, 0, read, Charsets.UTF_8)

            route(method, path, query, cookieHeader, authHeader, body, output)
            output.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Connection error: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun isAuthorized(query: String, cookies: String?, authHeader: String?): Boolean {
        if (authHeader != null &&
            authHeader.removePrefix("Bearer ").trim() == authToken
        ) {
            return true
        }
        if (cookies != null) {
            for (part in cookies.split(';')) {
                val kv = part.trim()
                if (kv.startsWith("streamcam_token=") &&
                    kv.substringAfter('=') == authToken
                ) {
                    return true
                }
            }
        }
        if (query.isNotEmpty()) {
            for (part in query.split('&')) {
                if (part.startsWith("token=") && part.substringAfter('=') == authToken) {
                    return true
                }
            }
        }
        return false
    }

    private fun route(
        method: String,
        path: String,
        query: String,
        cookies: String?,
        authHeader: String?,
        body: String,
        out: OutputStream,
    ) {
        val isPublic = (method == "GET" && (path == "/" || path == "/index.html")) ||
            (method == "POST" && path == "/api/login")
        if (!isPublic && !isAuthorized(query, cookies, authHeader)) {
            respondJson(out, "{\"error\":\"unauthorized\"}", 401, "Unauthorized")
            return
        }
        try {
            when {
                method == "GET" && (path == "/" || path == "/index.html") ->
                    respond(out, 200, "OK", "text/html; charset=utf-8",
                        INDEX_HTML.toByteArray(Charsets.UTF_8))

                method == "POST" && path == "/api/login" -> {
                    val token = parseJsonString(body, "token")
                    if (token != null && token == authToken) {
                        respondJson(
                            out, "{\"ok\":true}",
                            extraHeaders = "Set-Cookie: streamcam_token=$authToken; Path=/; SameSite=Lax\r\n",
                        )
                    } else {
                        respondJson(out, "{\"error\":\"invalid token\"}", 401, "Unauthorized")
                    }
                }

                method == "GET" && path == "/api/status" ->
                    respondJson(out, listener.getDockStatusJson(recentClients()))

                method == "GET" && path == "/api/detections" ->
                    respondJson(out, listener.getDockDetectionsJson())

                method == "GET" && path == "/preview.jpg" -> {
                    lastPreviewRequestMs = System.currentTimeMillis()
                    val jpeg = listener.getDockPreviewJpeg()
                    if (jpeg != null) {
                        respond(out, 200, "OK", "image/jpeg", jpeg)
                    } else {
                        respondJson(out, "{\"error\":\"preview unavailable\"}",
                            503, "Service Unavailable")
                    }
                }

                method == "POST" && path == "/api/move" -> {
                    val yaw = parseFloat(body, "yaw")
                    val pitch = parseFloat(body, "pitch")
                    if (yaw == null || pitch == null) {
                        respondJson(out, "{\"error\":\"need yaw and pitch\"}", 400, "Bad Request")
                    } else {
                        listener.onDockMove(
                            yaw.coerceIn(-120f, 120f),
                            pitch.coerceIn(-120f, 120f),
                        )
                        respondJson(out, "{\"ok\":true}")
                    }
                }

                method == "POST" && path == "/api/stop" -> {
                    listener.onDockStop()
                    respondJson(out, "{\"ok\":true}")
                }

                method == "POST" && path == "/api/center" -> {
                    listener.onDockCenter()
                    respondJson(out, "{\"ok\":true}")
                }

                method == "POST" && path == "/api/track_at" -> {
                    val x = parseFloat(body, "x")
                    val y = parseFloat(body, "y")
                    if (x == null || y == null) {
                        respondJson(out, "{\"error\":\"need x and y\"}", 400, "Bad Request")
                    } else {
                        val err = listener.onDockTrackAt(
                            x.coerceIn(0f, 1f),
                            y.coerceIn(0f, 1f),
                        )
                        if (err != null) {
                            respondJson(out, "{\"error\":\"${jsonEscape(err)}\"}", 409, "Conflict")
                        } else {
                            respondJson(out, "{\"ok\":true}")
                        }
                    }
                }

                method == "POST" && path == "/api/track_stop" -> {
                    listener.onDockTrackStop()
                    respondJson(out, "{\"ok\":true}")
                }

                method == "POST" && path == "/api/script/run" -> {
                    val code = parseJsonString(body, "code")
                    val name = parseJsonString(body, "name")
                    if (code == null && name == null) {
                        respondJson(out, "{\"error\":\"need code or name\"}", 400, "Bad Request")
                    } else {
                        val err = listener.onScriptRun(code, name)
                        if (err != null) {
                            respondJson(out, "{\"error\":\"${jsonEscape(err)}\"}", 409, "Conflict")
                        } else {
                            respondJson(out, "{\"ok\":true}")
                        }
                    }
                }

                method == "POST" && path == "/api/script/stop" -> {
                    listener.onScriptStop()
                    respondJson(out, "{\"ok\":true}")
                }

                method == "GET" && path == "/api/script/status" ->
                    respondJson(out, listener.getScriptStatusJson())

                method == "GET" && path == "/api/scripts" ->
                    respondJson(out, listener.getScriptsJson())

                path.startsWith("/api/scripts/") -> {
                    val name = path.removePrefix("/api/scripts/")
                    if (!ScriptEngine.isValidName(name)) {
                        respondJson(out, "{\"error\":\"invalid script name\"}", 400, "Bad Request")
                    } else when (method) {
                        "GET" -> {
                            val code = listener.getScriptCode(name)
                            if (code == null) {
                                respondJson(out, "{\"error\":\"not found\"}", 404, "Not Found")
                            } else {
                                respondJson(out, "{\"name\":\"${jsonEscape(name)}\"," +
                                    "\"code\":\"${jsonEscape(code)}\"}")
                            }
                        }
                        "POST" -> {
                            val code = parseJsonString(body, "code")
                            if (code == null) {
                                respondJson(out, "{\"error\":\"need code\"}", 400, "Bad Request")
                            } else {
                                val err = listener.saveScript(name, code)
                                if (err != null) {
                                    respondJson(out, "{\"error\":\"${jsonEscape(err)}\"}",
                                        400, "Bad Request")
                                } else {
                                    respondJson(out, "{\"ok\":true}")
                                }
                            }
                        }
                        "DELETE" -> {
                            if (listener.deleteScript(name)) {
                                respondJson(out, "{\"ok\":true}")
                            } else {
                                respondJson(out, "{\"error\":\"not found\"}", 404, "Not Found")
                            }
                        }
                        else -> respondJson(out, "{\"error\":\"not found\"}", 404, "Not Found")
                    }
                }

                else -> respondJson(out, "{\"error\":\"not found\"}", 404, "Not Found")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Route $method $path failed", e)
            respondJson(out, "{\"error\":\"internal error\"}", 500, "Internal Server Error")
        }
    }

    private fun parseFloat(body: String, key: String): Float? {
        val m = Regex("\"" + key + "\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)").find(body)
        return m?.groupValues?.get(1)?.toFloatOrNull()
    }

    /** Extracts a JSON string field, handling standard escape sequences. */
    private fun parseJsonString(body: String, key: String): String? {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"").find(body) ?: return null
        var i = m.range.last + 1
        val sb = StringBuilder()
        while (i < body.length) {
            when (val c = body[i]) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (++i >= body.length) return null
                    when (val e = body[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('')
                        'u' -> {
                            if (i + 4 >= body.length) return null
                            sb.append(body.substring(i + 1, i + 5).toIntOrNull(16)?.toChar() ?: '?')
                            i += 4
                        }
                        else -> sb.append(e)
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        return null
    }

    private fun respondJson(
        out: OutputStream,
        json: String,
        code: Int = 200,
        reason: String = "OK",
        extraHeaders: String = "",
    ) {
        respond(out, code, reason, "application/json", json.toByteArray(Charsets.UTF_8), extraHeaders)
    }

    private fun respond(
        out: OutputStream,
        code: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        extraHeaders: String = "",
    ) {
        val head = StringBuilder()
            .append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            .append("Content-Type: ").append(contentType).append("\r\n")
            .append("Content-Length: ").append(body.size).append("\r\n")
            .append("Cache-Control: no-store\r\n")
            .append(extraHeaders)
            .append("Connection: close\r\n")
            .append("\r\n")
        out.write(head.toString().toByteArray(Charsets.US_ASCII))
        out.write(body)
    }
}

// Compact dark dock page; detection boxes are normalized 0..1 in view space,
// so they map onto the full-frame <img> by percentage. No JS template literals
// or '$' anywhere — this is a Kotlin raw string.
private const val INDEX_HTML = """<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Gimbal Dock</title>
<style>
html,body{margin:0;background:#121216;color:#e8e8ea;font-family:system-ui,-apple-system,sans-serif;font-size:13px}
#wrap{padding:8px;display:flex;flex-direction:column;gap:8px;max-width:520px;margin:0 auto}
.row{display:flex;align-items:center;gap:8px}
#dot{width:10px;height:10px;border-radius:50%;background:#777;flex:none}
#devname{font-weight:600}
#trk{color:#ef5350;font-weight:700;display:none}
#hint{color:#ffb74d;display:none}
#prevwrap{position:relative;width:100%;min-height:90px;background:#000;border-radius:8px;overflow:hidden;line-height:0;cursor:crosshair}
#prev{width:100%;display:block}
#boxes{position:absolute;inset:0;pointer-events:none}
.box{position:absolute;border:2px solid #4fc3f7;border-radius:3px}
.box .lbl{position:absolute;top:-15px;left:-2px;font-size:10px;line-height:1.3;background:#4fc3f7;color:#000;padding:0 3px;border-radius:2px;white-space:nowrap}
.box.tracked{border-color:#ef5350;border-width:3px}
.box.tracked .lbl{background:#ef5350;color:#fff}
#prevmsg{position:absolute;inset:0;display:none;align-items:center;justify-content:center;color:#888;line-height:1.5;text-align:center;padding:12px}
#pad{display:grid;grid-template-columns:repeat(3,52px);grid-template-rows:repeat(3,52px);gap:6px;justify-content:center;user-select:none;-webkit-user-select:none}
button{background:#26262e;color:#e8e8ea;border:1px solid #3a3a44;border-radius:8px;font-size:16px;cursor:pointer;touch-action:none}
button:active{background:#4fc3f7;color:#000}
button:disabled{opacity:.35;cursor:default}
button:disabled:active{background:#26262e;color:#e8e8ea}
.ctl{display:flex;gap:8px;align-items:center}
.ctl button{flex:1;padding:8px 0;font-size:13px}
input[type=range]{flex:1}
#speedv{min-width:52px;text-align:right;color:#9e9ea8}
#msg{min-height:16px;color:#ffb74d}
#foot{color:#666;font-size:11px;line-height:1.5}
#login{position:fixed;inset:0;display:none;align-items:center;justify-content:center;background:rgba(0,0,0,.85);z-index:10}
#loginbox{background:#1c1c22;border:1px solid #3a3a44;border-radius:10px;padding:20px;display:flex;flex-direction:column;gap:10px;width:260px}
#loginbox input{background:#26262e;border:1px solid #3a3a44;color:#e8e8ea;border-radius:6px;padding:8px;font-size:13px}
#loginhint{color:#888;font-size:11px}
details{border:1px solid #2a2a32;border-radius:8px;padding:6px;display:flex;flex-direction:column;gap:6px}
summary{cursor:pointer;color:#9e9ea8;margin:-6px;padding:6px}
select,input[type=text]{background:#26262e;color:#e8e8ea;border:1px solid #3a3a44;border-radius:6px;padding:6px;font-size:12px}
select{flex:1;min-width:0}
textarea{width:100%;box-sizing:border-box;background:#0a0a0c;color:#e8e8ea;border:1px solid #3a3a44;border-radius:6px;padding:6px;font:12px/1.4 monospace;resize:vertical}
#output{background:#0a0a0c;border-radius:6px;padding:6px;max-height:160px;min-height:36px;overflow:auto;font:11px/1.4 monospace;white-space:pre-wrap;color:#9ee09e;margin:0}
#runstate{color:#4fc3f7;font-size:11px}
</style>
</head>
<body>
<div id="login">
  <div id="loginbox">
    <div><b>Dock token required</b></div>
    <input id="token" type="password" placeholder="paste token" autocomplete="off">
    <button id="loginbtn">Log in</button>
    <div id="loginhint">the token is shown in the StreamCam app under the dock URL</div>
  </div>
</div>
<div id="wrap">
  <div class="row"><span id="dot"></span><span id="devname">gimbal</span><span id="trk">TRACKING</span></div>
  <div id="hint">gimbal not connected — connect it in the StreamCam app</div>
  <div id="prevwrap">
    <img id="prev" draggable="false" alt="">
    <div id="boxes"></div>
    <div id="prevmsg">preview unavailable — bring StreamCam to the front</div>
  </div>
  <div id="pad">
    <span></span><button data-d="u">&#9650;</button><span></span>
    <button data-d="l">&#9664;</button><button data-d="c" title="Center">&#9679;</button><button data-d="r">&#9654;</button>
    <span></span><button data-d="d">&#9660;</button><span></span>
  </div>
  <div class="ctl"><span>speed</span><input id="speed" type="range" min="10" max="120" value="45"><span id="speedv">45&deg;/s</span></div>
  <div class="ctl"><button id="center">Center</button><button id="stoptrk">Stop Track</button></div>
  <div id="msg"></div>
  <details id="scripts">
    <summary>Scripts (Lua)</summary>
    <div class="ctl">
      <select id="scriptsel"><option value="">(editor draft)</option></select>
      <button id="refresh" title="Reload list">&#8635;</button>
    </div>
    <div class="ctl">
      <input id="scriptname" type="text" placeholder="script name" style="flex:1">
      <button id="save">Save</button>
      <button id="del">Delete</button>
    </div>
    <textarea id="code" rows="9" spellcheck="false"></textarea>
    <div class="ctl">
      <button id="run">Run</button>
      <button id="stopbtn">Stop</button>
      <span id="runstate"></span>
    </div>
    <pre id="output"></pre>
  </details>
  <div id="foot">click the preview to track a detected object &middot; arrows/WASD to move<br>preview needs StreamCam in the foreground &middot; full-motion video is the RTSP Media Source</div>
</div>
<script>
var connected=false, speed=45, scriptPoll=null;
var dot=document.getElementById('dot'), devname=document.getElementById('devname'),
    trk=document.getElementById('trk'), hint=document.getElementById('hint'),
    prev=document.getElementById('prev'), prevmsg=document.getElementById('prevmsg'),
    boxes=document.getElementById('boxes'), msg=document.getElementById('msg'),
    prevwrap=document.getElementById('prevwrap');
var msgTimer=null;
function showMsg(t){
  msg.textContent=t;
  if(msgTimer) clearTimeout(msgTimer);
  msgTimer=setTimeout(function(){ msg.textContent=''; },3000);
}
function showLogin(){ document.getElementById('login').style.display='flex'; }
function hideLogin(){ document.getElementById('login').style.display='none'; }
function api(url,opts){
  return fetch(url,opts||{}).then(function(r){
    if(r.status===401){ showLogin(); throw new Error('unauthorized'); }
    return r;
  });
}
function post(url,obj){
  return api(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(obj||{})})
    .then(function(r){
      if(!r.ok){
        r.json().then(function(j){ showMsg(j.error||('error '+r.status)); })
          .catch(function(){ showMsg('error '+r.status); });
        return null;
      }
      return r;
    })
    .catch(function(e){ if(e.message!=='unauthorized') showMsg('request failed'); return null; });
}
document.getElementById('loginbtn').addEventListener('click',function(){
  var t=document.getElementById('token').value;
  fetch('/api/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token:t})})
    .then(function(r){
      if(r.ok){
        hideLogin(); pollStatus(); pollPreview(); refreshScripts(); pollScript();
      } else {
        document.getElementById('loginhint').textContent='wrong token — check the StreamCam app';
      }
    })
    .catch(function(){ document.getElementById('loginhint').textContent='login request failed'; });
});
document.getElementById('token').addEventListener('keydown',function(e){
  if(e.key==='Enter') document.getElementById('loginbtn').click();
});
function setEnabled(en){
  var bs=document.querySelectorAll('#pad button, #center, #stoptrk');
  for(var i=0;i<bs.length;i++) bs[i].disabled=!en;
}
function pollStatus(){
  api('/api/status').then(function(r){ return r.json(); }).then(function(s){
    connected = s.gimbal==='CONNECTED';
    dot.style.background = connected ? '#66bb6a' : (s.gimbal==='DISCONNECTED' ? '#777' : '#ffeb3b');
    devname.textContent = s.deviceName || ('gimbal: '+String(s.gimbal).toLowerCase());
    trk.style.display = s.tracking ? 'inline' : 'none';
    hint.style.display = connected ? 'none' : 'block';
    setEnabled(connected);
  }).catch(function(){ dot.style.background='#777'; });
}
function pollPreview(){ prev.src='/preview.jpg?t='+Date.now(); }
prev.onload=function(){ prevmsg.style.display='none'; };
prev.onerror=function(){ prevmsg.style.display='flex'; };
function pollDetections(){
  api('/api/detections').then(function(r){ return r.json(); }).then(function(j){
    var html='';
    for(var i=0;i<j.boxes.length;i++){
      var b=j.boxes[i];
      html+='<div class="box'+(i===j.tracked?' tracked':'')+'" style="left:'+(b.x*100)+'%;top:'+(b.y*100)+'%;width:'+(b.w*100)+'%;height:'+(b.h*100)+'%"><span class="lbl">'+b.label+'</span></div>';
    }
    boxes.innerHTML=html;
  }).catch(function(){});
}
prevwrap.addEventListener('click',function(e){
  var r=prevwrap.getBoundingClientRect();
  if(r.width<2||r.height<2) return;
  post('/api/track_at',{x:(e.clientX-r.left)/r.width, y:(e.clientY-r.top)/r.height});
});
function dirVec(d){
  if(d==='u') return {yaw:0,pitch:speed};
  if(d==='d') return {yaw:0,pitch:-speed};
  if(d==='l') return {yaw:-speed,pitch:0};
  return {yaw:speed,pitch:0};
}
var padBtns=document.querySelectorAll('#pad button[data-d]');
for(var i=0;i<padBtns.length;i++){
  (function(btn){
    var d=btn.getAttribute('data-d');
    if(d==='c'){
      btn.addEventListener('click',function(){ post('/api/center'); });
      return;
    }
    btn.addEventListener('pointerdown',function(e){ e.preventDefault(); post('/api/move',dirVec(d)); });
    btn.addEventListener('pointerup',function(){ post('/api/stop'); });
    btn.addEventListener('pointerleave',function(){ post('/api/stop'); });
    btn.addEventListener('pointercancel',function(){ post('/api/stop'); });
  })(padBtns[i]);
}
document.getElementById('center').addEventListener('click',function(){ post('/api/center'); });
document.getElementById('stoptrk').addEventListener('click',function(){ post('/api/track_stop'); });
var speedEl=document.getElementById('speed');
speedEl.addEventListener('input',function(){
  speed=parseInt(speedEl.value,10);
  document.getElementById('speedv').textContent=speed+'\u00b0/s';
});
var keys={};
function keyDir(k){
  k=String(k).toLowerCase();
  if(k==='arrowup'||k==='w') return 'u';
  if(k==='arrowdown'||k==='s') return 'd';
  if(k==='arrowleft'||k==='a') return 'l';
  if(k==='arrowright'||k==='d') return 'r';
  return null;
}
function applyKeys(){
  var yaw=0, pitch=0, any=false;
  for(var k in keys){ var v=dirVec(keys[k]); yaw+=v.yaw; pitch+=v.pitch; any=true; }
  if(!any||(yaw===0&&pitch===0)) post('/api/stop'); else post('/api/move',{yaw:yaw,pitch:pitch});
}
document.addEventListener('keydown',function(e){
  if(e.repeat) return;
  var d=keyDir(e.key); if(!d) return;
  e.preventDefault();
  keys[String(e.key).toLowerCase()]=d;
  applyKeys();
});
document.addEventListener('keyup',function(e){
  var d=keyDir(e.key); if(!d) return;
  delete keys[String(e.key).toLowerCase()];
  applyKeys();
});

// --- Scripts panel ---
var codeEl=document.getElementById('code'), selEl=document.getElementById('scriptsel'),
    nameEl=document.getElementById('scriptname'), outEl=document.getElementById('output'),
    runstateEl=document.getElementById('runstate');
var draft=null;
try{ draft=localStorage.getItem('script_draft'); }catch(e){}
if(draft!==null&&draft!==undefined) codeEl.value=draft;
codeEl.addEventListener('input',function(){
  try{ localStorage.setItem('script_draft',codeEl.value); }catch(e){}
});
function refreshScripts(){
  api('/api/scripts').then(function(r){ return r.json(); }).then(function(list){
    var html='<option value="">(editor draft)</option>';
    for(var i=0;i<list.length;i++) html+='<option value="'+list[i]+'">'+list[i]+'</option>';
    selEl.innerHTML=html;
  }).catch(function(){});
}
document.getElementById('refresh').addEventListener('click',refreshScripts);
selEl.addEventListener('change',function(){
  var n=selEl.value;
  nameEl.value=n;
  if(!n) return;
  api('/api/scripts/'+encodeURIComponent(n)).then(function(r){ return r.json(); }).then(function(j){
    codeEl.value=j.code;
    try{ localStorage.setItem('script_draft',j.code); }catch(e){}
  }).catch(function(){});
});
document.getElementById('save').addEventListener('click',function(){
  var n=nameEl.value.trim();
  if(!n){ showMsg('name the script first'); return; }
  post('/api/scripts/'+encodeURIComponent(n),{code:codeEl.value}).then(function(r){
    if(r){ showMsg('saved '+n); refreshScripts(); }
  });
});
document.getElementById('del').addEventListener('click',function(){
  var n=nameEl.value.replace(/^\s+|\s+$/g,'');
  if(!n){ showMsg('pick a script to delete'); return; }
  api('/api/scripts/'+encodeURIComponent(n),{method:'DELETE'}).then(function(r){
    if(r.ok){ showMsg('deleted '+n); refreshScripts(); } else { showMsg('delete failed'); }
  }).catch(function(){});
});
document.getElementById('run').addEventListener('click',function(){
  post('/api/script/run',{code:codeEl.value}).then(function(r){
    if(r){ pollScript(); }
  });
});
document.getElementById('stopbtn').addEventListener('click',function(){
  post('/api/script/stop');
});
function pollScript(){
  api('/api/script/status').then(function(r){ return r.json(); }).then(function(s){
    runstateEl.textContent = s.running ? ('running: '+s.name) : '';
    outEl.textContent = s.output;
    outEl.scrollTop = outEl.scrollHeight;
    if(s.running){
      if(!scriptPoll) scriptPoll=setInterval(pollScript,1000);
    } else if(scriptPoll){
      clearInterval(scriptPoll);
      scriptPoll=null;
    }
  }).catch(function(){});
}

pollStatus(); setInterval(pollStatus,1000);
pollPreview(); setInterval(pollPreview,250);
pollDetections(); setInterval(pollDetections,300);
refreshScripts(); pollScript();
</script>
</body>
</html>
"""
