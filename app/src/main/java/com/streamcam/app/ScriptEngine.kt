package com.streamcam.app

import android.content.Context
import android.util.Log
import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.CoroutineLib
import org.luaj.vm2.lib.DebugLib
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
import org.luaj.vm2.lib.jse.JseBaseLib
import org.luaj.vm2.lib.jse.JseMathLib
import java.io.File

/** UI-free host surface for Lua scripts; MainActivity implements it (see dockListener pattern). */
interface ScriptHost {
    fun gimbalMove(yawDps: Float, pitchDps: Float)
    fun gimbalStop()
    fun gimbalCenter()
    fun gimbalIsConnected(): Boolean
    fun gimbalAttitude(): GimbalController.Attitude?
    fun gimbalTrack(x: Float, y: Float): String?
    fun gimbalTrackStop()
    fun gimbalIsTracking(): Boolean
    fun sensorsImu(): FloatArray
    fun detectStart()
    fun detectStop()
    fun detectIsRunning(): Boolean
    fun detectObjects(): List<Detection>
    fun streamStart(): Boolean
    fun streamStop()
    fun streamIsStreaming(): Boolean
    fun recordStart(): Boolean
    fun recordStop()
    fun recordMark(): Float
    fun recordIsRecording(): Boolean
    fun onScriptStateChanged(running: Boolean, name: String)
}

/**
 * Sandboxed LuaJ engine: one script at a time on a daemon thread. Hard stop via a DebugLib
 * instruction hook (fires per VM instruction under LuaC) plus cancellation checks in sleep();
 * gimbal velocity is always zeroed when a script ends, errors, or is stopped.
 * Scripts are stored at filesDir/scripts/<name>.lua.
 */
class ScriptEngine(context: Context, private val host: ScriptHost) {

    companion object {
        private const val TAG = "ScriptEngine"
        private const val MAX_OUTPUT_LINES = 300
        private const val MAX_CODE_BYTES = 64 * 1024
        private val NAME_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

        fun isValidName(name: String): Boolean = NAME_REGEX.matches(name)
    }

    private val scriptsDir = File(context.filesDir, "scripts")

    @Volatile private var cancelled = false
    @Volatile var isRunning = false
        private set
    @Volatile var currentName = ""
        private set
    private var scriptThread: Thread? = null
    private val outputLock = Any()
    private val outputLines = ArrayDeque<String>()

    /** @return null if the script was started, otherwise an error message */
    @Synchronized
    fun run(code: String, name: String): String? {
        if (code.length > MAX_CODE_BYTES) return "Script too large"
        if (isRunning) return "A script is already running"
        isRunning = true
        cancelled = false
        currentName = name
        synchronized(outputLock) { outputLines.clear() }
        appendOutput("--- running: $name ---")
        host.onScriptStateChanged(true, name)
        scriptThread = Thread {
            try {
                execute(code, name)
                appendOutput("--- finished ---")
            } catch (e: LuaError) {
                appendOutput("--- error: ${e.message} ---")
            } catch (t: Throwable) {
                Log.w(TAG, "Script '$name' failed", t)
                appendOutput("--- error: ${t.message ?: t.javaClass.simpleName} ---")
            } finally {
                try {
                    host.gimbalStop()
                } catch (_: Exception) {
                }
                synchronized(this@ScriptEngine) {
                    isRunning = false
                    currentName = ""
                    scriptThread = null
                }
                host.onScriptStateChanged(false, "")
            }
        }.apply {
            isDaemon = true
            this.name = "LuaScript"
            start()
        }
        return null
    }

    /** @return null if the saved script was started, otherwise an error message */
    fun runSaved(name: String): String? {
        if (!isValidName(name)) return "Invalid script name"
        val f = File(scriptsDir, "$name.lua")
        if (!f.isFile) return "No script named '$name'"
        return run(f.readText(), name)
    }

    fun stop() {
        if (isRunning) {
            cancelled = true
            appendOutput("--- stop requested ---")
        }
    }

    fun statusJson(): String = buildString {
        append("{\"running\":").append(isRunning)
        append(",\"name\":\"").append(GimbalDockServer.jsonEscape(currentName)).append("\"")
        append(",\"output\":\"").append(GimbalDockServer.jsonEscape(outputTail())).append("\"")
        append("}")
    }

    fun listScripts(): List<String> =
        scriptsDir.listFiles()
            ?.filter { it.isFile && it.extension == "lua" && isValidName(it.nameWithoutExtension) }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

    fun readScript(name: String): String? {
        if (!isValidName(name)) return null
        val f = File(scriptsDir, "$name.lua")
        return if (f.isFile) f.readText() else null
    }

    /** @return null on success, otherwise an error message */
    fun saveScript(name: String, code: String): String? {
        if (!isValidName(name)) return "Invalid script name (letters, digits, _ and - only)"
        if (code.length > MAX_CODE_BYTES) return "Script too large"
        return try {
            if (!scriptsDir.exists()) scriptsDir.mkdirs()
            File(scriptsDir, "$name.lua").writeText(code)
            null
        } catch (e: Exception) {
            "Save failed: ${e.message}"
        }
    }

    fun deleteScript(name: String): Boolean {
        if (!isValidName(name)) return false
        return File(scriptsDir, "$name.lua").delete()
    }

    private fun outputTail(): String =
        synchronized(outputLock) { outputLines.joinToString("\n") }

    private fun appendOutput(line: String) {
        synchronized(outputLock) {
            outputLines.addLast(line)
            while (outputLines.size > MAX_OUTPUT_LINES) outputLines.removeFirst()
        }
    }

    private fun checkCancelled() {
        if (cancelled) throw LuaError("script stopped")
    }

    private fun execute(code: String, name: String) {
        val globals = buildGlobals()
        // load() returns an error-throwing function on syntax errors; call() raises LuaError
        globals.load(code, name.ifEmpty { "script" }).call()
    }

    private fun buildGlobals(): Globals {
        val globals = Globals()
        globals.load(JseBaseLib())
        globals.load(PackageLib())
        globals.load(TableLib())
        globals.load(StringLib())
        globals.load(JseMathLib())
        globals.load(CoroutineLib())
        globals.load(KillHook())
        LoadState.install(globals)
        LuaC.install(globals)
        // scrub anything that could touch the filesystem or Java
        for (fn in listOf("debug", "dofile", "loadfile", "require", "package", "loadstring")) {
            globals.set(fn, LuaValue.NIL)
        }
        bindApi(globals)
        return globals
    }

    /** Per-instruction VM hook (globals.debuglib) — kills even `while true do end`. */
    private inner class KillHook : DebugLib() {
        private var counter = 0

        override fun onInstruction(pc: Int, v: Varargs, top: Int) {
            if (++counter and 63 == 0) checkCancelled()
            super.onInstruction(pc, v, top)
        }
    }

    private fun zeroArg(f: () -> Unit): LuaValue = object : ZeroArgFunction() {
        override fun call(): LuaValue {
            f()
            return LuaValue.NIL
        }
    }

    private fun bindApi(globals: Globals) {
        val gimbal = LuaTable()
        gimbal.set("move", object : TwoArgFunction() {
            override fun call(yaw: LuaValue, pitch: LuaValue): LuaValue {
                host.gimbalMove(
                    yaw.checkdouble().toFloat().coerceIn(-120f, 120f),
                    pitch.checkdouble().toFloat().coerceIn(-120f, 120f),
                )
                return LuaValue.NIL
            }
        })
        gimbal.set("stop", zeroArg { host.gimbalStop() })
        gimbal.set("center", zeroArg { host.gimbalCenter() })
        gimbal.set("is_connected", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(host.gimbalIsConnected())
        })
        gimbal.set("attitude", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                val a = host.gimbalAttitude() ?: return LuaValue.NIL
                return LuaTable().apply {
                    set("pitch", LuaValue.valueOf(a.pitch.toDouble()))
                    set("roll", LuaValue.valueOf(a.roll.toDouble()))
                    set("yaw", LuaValue.valueOf(a.yaw.toDouble()))
                    set("ms", LuaValue.valueOf(a.deviceMs.toDouble()))
                }
            }
        })
        gimbal.set("track", object : TwoArgFunction() {
            override fun call(x: LuaValue, y: LuaValue): LuaValue {
                checkCancelled()
                val err = host.gimbalTrack(
                    x.checkdouble().toFloat().coerceIn(0f, 1f),
                    y.checkdouble().toFloat().coerceIn(0f, 1f),
                )
                if (err != null) throw LuaError(err)
                return LuaValue.NIL
            }
        })
        gimbal.set("track_stop", zeroArg { host.gimbalTrackStop() })
        gimbal.set("is_tracking", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(host.gimbalIsTracking())
        })
        globals.set("gimbal", gimbal)

        val sensors = LuaTable()
        sensors.set("imu", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                val v = host.sensorsImu()
                fun vec(x: Float, y: Float, z: Float) = LuaTable().apply {
                    set("x", LuaValue.valueOf(x.toDouble()))
                    set("y", LuaValue.valueOf(y.toDouble()))
                    set("z", LuaValue.valueOf(z.toDouble()))
                }
                val quat = LuaTable().apply {
                    set("w", LuaValue.valueOf(v[0].toDouble()))
                    set("x", LuaValue.valueOf(v[1].toDouble()))
                    set("y", LuaValue.valueOf(v[2].toDouble()))
                    set("z", LuaValue.valueOf(v[3].toDouble()))
                }
                return LuaTable().apply {
                    set("quat", quat)
                    set("accel", vec(v[4], v[5], v[6]))
                    set("gyro", vec(v[7], v[8], v[9]))
                }
            }
        })
        globals.set("sensors", sensors)

        val detect = LuaTable()
        detect.set("start", zeroArg { host.detectStart() })
        detect.set("stop", zeroArg { host.detectStop() })
        detect.set("is_running", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(host.detectIsRunning())
        })
        detect.set("objects", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                val arr = LuaTable()
                host.detectObjects().forEachIndexed { i, d ->
                    val b = d.boundingBox
                    arr.set(i + 1, LuaTable().apply {
                        set("x", LuaValue.valueOf(b.left.toDouble()))
                        set("y", LuaValue.valueOf(b.top.toDouble()))
                        set("w", LuaValue.valueOf(b.width().toDouble()))
                        set("h", LuaValue.valueOf(b.height().toDouble()))
                        set("label", LuaValue.valueOf(d.label))
                        set("conf", LuaValue.valueOf(d.confidence.toDouble()))
                    })
                }
                return arr
            }
        })
        globals.set("detect", detect)

        val stream = LuaTable()
        stream.set("start", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(host.streamStart())
        })
        stream.set("stop", zeroArg { host.streamStop() })
        stream.set("is_streaming", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(host.streamIsStreaming())
        })
        globals.set("stream", stream)

        val record = LuaTable()
        record.set("start", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(host.recordStart())
        })
        record.set("stop", zeroArg { host.recordStop() })
        record.set("mark", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(host.recordMark().toDouble())
        })
        record.set("is_recording", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(host.recordIsRecording())
        })
        globals.set("record", record)

        globals.set("sleep", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                var remaining = arg.checklong()
                while (remaining > 0) {
                    checkCancelled()
                    val chunk = minOf(remaining, 100L)
                    try {
                        Thread.sleep(chunk)
                    } catch (_: InterruptedException) {
                        checkCancelled()
                    }
                    remaining -= chunk
                }
                return LuaValue.NIL
            }
        })

        globals.set("print", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val sb = StringBuilder()
                for (i in 1..args.narg()) {
                    if (i > 1) sb.append('\t')
                    sb.append(args.arg(i).tojstring())
                }
                appendOutput(sb.toString())
                return LuaValue.NONE
            }
        })
    }
}
