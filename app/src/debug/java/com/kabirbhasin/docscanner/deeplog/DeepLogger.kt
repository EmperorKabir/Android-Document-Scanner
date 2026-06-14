@file:Suppress("unused")

package com.kabirbhasin.docscanner.deeplog

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.Window
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement

/**
 * Forensic on-device logger. Asynchronous, NDJSON, fresh file per launch.
 *
 * Lifecycle: call [install] once at process start (the App Startup Initializer
 * does this for you). Everything else self-registers.
 */
object DeepLogger {

    private const val DIR_NAME = "deeplog"
    private const val QUEUE_CAPACITY = 8192
    private const val FLUSH_EVERY = 64
    private const val MEMORY_SNAPSHOT_MS = 5_000L
    private val ISO: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    private val started = AtomicBoolean(false)
    private val seq = AtomicLong(0)
    private val dropped = AtomicLong(0)
    private val queueDepth = AtomicInteger(0)
    @Volatile private var queue: ArrayBlockingQueue<JSONObject>? = null
    @Volatile private var writerThread: Thread? = null
    @Volatile private var sessionFile: File? = null
    @Volatile private var appContext: Context? = null

    @Volatile private var contextSupplier: (() -> Map<String, Any?>)? = null

    @JvmStatic
    fun install(app: Application) {
        if (!started.compareAndSet(false, true)) return
        appContext = app.applicationContext
        openSessionFile(app)
        startWriter()
        writeSessionHeader(app)
        registerActivityCallbacks(app)
        registerMemorySampler()
        installCrashHandler()
        registerConnectivity(app)
        Choreographer.getInstance().postFrameCallback(FrameJankCallback)
        log("logger", mapOf("event" to "installed", "file" to sessionFile?.absolutePath))
    }

    @JvmStatic
    fun setContextSupplier(supplier: () -> Map<String, Any?>) {
        contextSupplier = supplier
    }

    @JvmStatic
    @JvmOverloads
    fun log(category: String, payload: Map<String, Any?>, traceId: String? = null) {
        val q = queue ?: return
        val obj = baseEntry(category, traceId)
        val data = JSONObject()
        for ((k, v) in payload) data.put(k, normalize(v))
        obj.put("payload", data)
        enqueue(q, obj)
    }

    @JvmStatic
    @JvmOverloads
    fun log(category: String, key: String, value: Any?, traceId: String? = null) =
        log(category, mapOf(key to value), traceId)

    @JvmStatic
    @JvmOverloads
    fun stateChange(name: String, from: Any?, to: Any?, trigger: String? = null, traceId: String? = null) =
        log(
            "state.programmatic",
            mapOf("name" to name, "from" to from, "to" to to, "trigger" to trigger),
            traceId,
        )

    @JvmStatic
    @JvmOverloads
    fun transition(machine: String, from: String, to: String, trigger: String, guardPassed: Boolean, traceId: String? = null) =
        log(
            "state.transition",
            mapOf("machine" to machine, "from" to from, "to" to to, "trigger" to trigger, "guard" to guardPassed),
            traceId,
        )

    @JvmStatic
    @JvmOverloads
    fun emission(source: String, type: String, value: Any?, traceId: String? = null) =
        log("state.emission", mapOf("source" to source, "type" to type, "value" to value), traceId)

    @JvmStatic
    fun input(action: String, x: Float, y: Float, targetViewId: String?, traceId: String? = null) =
        log("input.touch", mapOf("action" to action, "x" to x, "y" to y, "target" to targetViewId), traceId)

    @JvmStatic
    @JvmOverloads
    fun exception(t: Throwable, fatal: Boolean = false, traceId: String? = null) {
        log(
            "exception",
            mapOf(
                "fatal" to fatal,
                "type" to t.javaClass.name,
                "message" to t.message,
                "causes" to causeChain(t),
                "stack" to stackString(t),
            ),
            traceId,
        )
    }

    @JvmStatic
    fun diagnostics(): Map<String, Any?> = mapOf(
        "dropped" to dropped.get(),
        "queueDepth" to queueDepth.get(),
        "seq" to seq.get(),
        "file" to sessionFile?.absolutePath,
    )

    @JvmStatic
    fun flushBlocking(timeoutMs: Long = 2_000L) {
        val q = queue ?: return
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (q.isNotEmpty() && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(5)
        }
    }

    class TraceContext(val traceId: String) : ThreadContextElement<String?>, CoroutineContext.Element {
        companion object Key : CoroutineContext.Key<TraceContext>
        override val key: CoroutineContext.Key<*> get() = Key
        override fun updateThreadContext(context: CoroutineContext): String? {
            val prev = threadTrace.get()
            threadTrace.set(traceId)
            return prev
        }
        override fun restoreThreadContext(context: CoroutineContext, oldState: String?) {
            if (oldState == null) threadTrace.remove() else threadTrace.set(oldState)
        }
    }

    private val threadTrace = ThreadLocal<String?>()

    @JvmStatic
    fun trace(traceId: String): CoroutineContext = TraceContext(traceId)

    @JvmStatic
    fun currentTrace(): String? = threadTrace.get()

    private fun baseEntry(category: String, traceId: String?): JSONObject {
        val now = System.currentTimeMillis()
        val obj = JSONObject()
        obj.put("seq", seq.incrementAndGet())
        obj.put("nano", System.nanoTime())
        obj.put("wall", now)
        obj.put("iso", ISO.get()!!.format(now))
        obj.put("category", category)
        obj.put("trace", traceId ?: currentTrace())
        val thread = Thread.currentThread()
        obj.put("thread", thread.name)
        obj.put("tid", thread.id)
        coroutineInfo()?.let { obj.put("coroutine", it) }
        contextSupplier?.invoke()?.let { ctx ->
            val c = JSONObject()
            for ((k, v) in ctx) c.put(k, normalize(v))
            obj.put("ctx", c)
        }
        return obj
    }

    private fun coroutineInfo(): JSONObject? {
        val name = Thread.currentThread().name
        if (!name.contains("Dispatch") && !name.contains("coroutine")) return null
        return JSONObject().put("dispatcherHint", name)
    }

    @OptIn(ExperimentalStdlibApi::class)
    @JvmStatic
    fun logCoroutine(category: String, payload: Map<String, Any?>, ctx: CoroutineContext) {
        val q = queue ?: return
        val obj = baseEntry(category, (ctx[TraceContext.Key])?.traceId)
        val co = JSONObject()
        co.put("name", ctx[CoroutineName]?.name)
        val job = ctx[Job]
        co.put(
            "job",
            when {
                job == null -> "none"
                job.isCancelled -> "cancelled"
                job.isCompleted -> "completed"
                job.isActive -> "active"
                else -> "new"
            },
        )
        co.put("dispatcher", ctx[kotlinx.coroutines.CoroutineDispatcher.Key]?.toString())
        obj.put("coroutine", co)
        val data = JSONObject()
        for ((k, v) in payload) data.put(k, normalize(v))
        obj.put("payload", data)
        enqueue(q, obj)
    }

    private fun enqueue(q: ArrayBlockingQueue<JSONObject>, obj: JSONObject) {
        if (q.offer(obj)) {
            queueDepth.set(q.size)
        } else {
            dropped.incrementAndGet()
        }
    }

    private fun startWriter() {
        val q = ArrayBlockingQueue<JSONObject>(QUEUE_CAPACITY)
        queue = q
        val file = sessionFile ?: return
        val t = Thread({
            val writer = BufferedWriter(FileWriter(file, true), 1 shl 16)
            var sinceFlush = 0
            try {
                while (true) {
                    val item = q.poll(1, TimeUnit.SECONDS)
                    if (item == null) {
                        writer.flush()
                        continue
                    }
                    writer.write(item.toString())
                    writer.write("\n")
                    queueDepth.set(q.size)
                    if (++sinceFlush >= FLUSH_EVERY) {
                        writer.flush()
                        sinceFlush = 0
                    }
                }
            } catch (_: InterruptedException) {
                while (true) {
                    val item = q.poll() ?: break
                    writer.write(item.toString())
                    writer.write("\n")
                }
                writer.flush()
            } finally {
                try {
                    writer.flush()
                    writer.close()
                } catch (_: Throwable) {
                }
            }
        }, "DeepLogger-writer")
        t.isDaemon = true
        t.priority = Thread.MIN_PRIORITY + 1
        t.start()
        writerThread = t
    }

    private fun openSessionFile(ctx: Context) {
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val dir = File(base, DIR_NAME).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(System.currentTimeMillis())
        sessionFile = File(dir, "session-$stamp.ndjson")
    }

    private fun writeSessionHeader(ctx: Context) {
        val dm: DisplayMetrics = ctx.resources.displayMetrics
        val header = mapOf(
            "kind" to "session-header",
            "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "device" to Build.DEVICE,
            "api" to Build.VERSION.SDK_INT,
            "release" to Build.VERSION.RELEASE,
            "abis" to Build.SUPPORTED_ABIS.toList(),
            "density" to dm.density,
            "densityDpi" to dm.densityDpi,
            "widthPx" to dm.widthPixels,
            "heightPx" to dm.heightPixels,
            "locale" to Locale.getDefault().toLanguageTag(),
            "pkg" to ctx.packageName,
            "startUptimeMs" to SystemClock.uptimeMillis(),
        )
        log("session", header)
    }

    private fun registerActivityCallbacks(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                log("lifecycle.activity", mapOf("event" to "created", "activity" to a.javaClass.name))
                hookWindowTouch(a)
                if (a is FragmentActivity) registerFragmentCallbacks(a)
            }
            override fun onActivityStarted(a: Activity) =
                log("lifecycle.activity", mapOf("event" to "started", "activity" to a.javaClass.name))
            override fun onActivityResumed(a: Activity) =
                log("lifecycle.activity", mapOf("event" to "resumed", "activity" to a.javaClass.name))
            override fun onActivityPaused(a: Activity) =
                log("lifecycle.activity", mapOf("event" to "paused", "activity" to a.javaClass.name))
            override fun onActivityStopped(a: Activity) =
                log("lifecycle.activity", mapOf("event" to "stopped", "activity" to a.javaClass.name))
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) =
                log("lifecycle.activity", mapOf("event" to "destroyed", "activity" to a.javaClass.name))
        })
    }

    private fun registerFragmentCallbacks(a: FragmentActivity) {
        a.supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentResumed(fm: FragmentManager, f: Fragment) =
                    log("lifecycle.fragment", mapOf("event" to "resumed", "fragment" to f.javaClass.name))
                override fun onFragmentPaused(fm: FragmentManager, f: Fragment) =
                    log("lifecycle.fragment", mapOf("event" to "paused", "fragment" to f.javaClass.name))
                override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, s: Bundle?) =
                    log("lifecycle.fragment", mapOf("event" to "viewCreated", "fragment" to f.javaClass.name))
                override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) =
                    log("lifecycle.fragment", mapOf("event" to "destroyed", "fragment" to f.javaClass.name))
            },
            true,
        )
    }

    private fun hookWindowTouch(a: Activity) {
        val window = a.window ?: return
        val existing = window.callback
        window.callback = object : Window.Callback by existing {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                val actionName = MotionEvent.actionToString(event.actionMasked)
                val targetId = try {
                    val v = a.window.decorView.findViewById<View>(android.R.id.content)
                    v?.let { resName(a, it.id) }
                } catch (_: Throwable) {
                    null
                }
                input(actionName, event.x, event.y, targetId)
                return existing.dispatchTouchEvent(event)
            }
        }
    }

    private fun resName(ctx: Context, id: Int): String? =
        if (id == View.NO_ID) null else try {
            ctx.resources.getResourceEntryName(id)
        } catch (_: Throwable) {
            null
        }

    private object FrameJankCallback : Choreographer.FrameCallback {
        private var last = 0L
        private const val FRAME_BUDGET_NS = 16_666_666L
        override fun doFrame(frameTimeNanos: Long) {
            if (last != 0L) {
                val delta = frameTimeNanos - last
                if (delta > FRAME_BUDGET_NS * 2) {
                    log(
                        "render.frame",
                        mapOf("deltaNs" to delta, "droppedApprox" to (delta / FRAME_BUDGET_NS - 1)),
                    )
                }
            }
            last = frameTimeNanos
            try {
                Choreographer.getInstance().postFrameCallback(this)
            } catch (_: Throwable) {
            }
        }
    }

    private fun registerMemorySampler() {
        val ht = HandlerThread("DeepLogger-mem").apply { start() }
        val handler = Handler(ht.looper)
        val rt = Runtime.getRuntime()
        val task = object : Runnable {
            override fun run() {
                val mi = Debug.MemoryInfo()
                Debug.getMemoryInfo(mi)
                log(
                    "memory",
                    mapOf(
                        "totalPss" to mi.totalPss,
                        "dalvikPss" to mi.dalvikPss,
                        "nativePss" to mi.nativePss,
                        "heapUsed" to (rt.totalMemory() - rt.freeMemory()),
                        "heapMax" to rt.maxMemory(),
                    ),
                )
                handler.postDelayed(this, MEMORY_SNAPSHOT_MS)
            }
        }
        handler.post(task)
    }

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                exception(throwable, fatal = true)
                flushBlocking()
            } catch (_: Throwable) {
            } finally {
                prev?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun registerConnectivity(app: Application) {
        log("connectivity", mapOf("event" to "init"))
    }

    @JvmStatic
    fun observePrefs(prefs: SharedPreferences, name: String) {
        prefs.registerOnSharedPreferenceChangeListener { sp, key ->
            log("prefs.write", mapOf("store" to name, "key" to key, "value" to sp.all[key]))
        }
    }

    @JvmStatic
    fun broadcastReceived(receiver: BroadcastReceiver, intent: Intent) =
        log(
            "broadcast",
            mapOf(
                "receiver" to receiver.javaClass.name,
                "action" to intent.action,
                "extras" to intent.extras?.keySet()?.associateWith { intent.extras?.get(it)?.toString() },
            ),
        )

    @JvmStatic
    fun serviceBound(component: ComponentName?) =
        log("service", mapOf("event" to "bound", "component" to component?.flattenToString()))

    private fun normalize(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is Number, is Boolean, is String -> v
        is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, vv) -> o.put(k.toString(), normalize(vv)) } }
        is Iterable<*> -> JSONArray().also { arr -> v.forEach { arr.put(normalize(it)) } }
        is Array<*> -> JSONArray().also { arr -> v.forEach { arr.put(normalize(it)) } }
        else -> v.toString()
    }

    private fun causeChain(t: Throwable): JSONArray {
        val arr = JSONArray()
        var cur: Throwable? = t.cause
        var guard = 0
        while (cur != null && guard < 20) {
            arr.put(JSONObject().put("type", cur.javaClass.name).put("message", cur.message))
            cur = cur.cause
            guard++
        }
        return arr
    }

    private fun stackString(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}
