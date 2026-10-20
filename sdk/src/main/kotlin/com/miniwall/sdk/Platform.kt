package com.miniwall.sdk

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.util.LruCache
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.miniwall.core.logic.Svg
import com.miniwall.core.logic.SvgIcon
import com.miniwall.core.session.Events
import com.miniwall.core.session.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import com.miniwall.core.logic.IsoTime
import com.miniwall.core.session.Assets
import com.miniwall.core.session.DeliveryAssets
import com.miniwall.core.session.DownloadLimits

/** SharedPreferences storage for the visitor id, frequency caps and exposures. */
internal class PrefsStore(context: Context) : KeyValueStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("com.miniwall.sdk", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun set(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

class MiniwallException(message: String) : IOException(message)

/**
 * The delivery API (`/v1/sdk/...`): ETag revalidation, the last copy on disk so a published
 * screen still shows offline, and batched analytics events kept on disk until sent.
 */
internal class Client(private val cacheDir: File) {
    @Volatile var apiKey: String? = null
    @Volatile var apiUrl: String = Miniwall.DEFAULT_API_URL
    @Volatile var timeoutMs: Int = 8000
    @Volatile var analytics: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memory = HashMap<String, Pair<String?, JsonElement>>()
    private val eventsFile = File(cacheDir, "events.json")
    private val eventsLock = Mutex()
    private var pending: MutableList<JsonObject>? = null
    @Volatile private var flushScheduled = false
    private var dirty = false
    private var persistScheduled = false

    init {
        cacheDir.mkdirs()
    }

    /**
     * Cache key of a path: scoped by a short hash of the API key and URL, so switching keys or environments
     * (a test key, another project, staging) never shows another project's JSON from disk or memory.
     */
    private fun cacheKey(path: String, key: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest("$key|${apiUrl.trimEnd('/')}".toByteArray(Charsets.UTF_8))
        return digest.take(4).joinToString("") { "%02x".format(it) } + "_" + path
    }

    private fun cacheFile(key: String) = File(cacheDir, key.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").takeLast(120) + ".json")

    private fun readCache(key: String): Pair<String?, JsonElement>? {
        synchronized(memory) { memory[key]?.let { return it } }
        val file = cacheFile(key)
        if (!file.exists()) return null
        return runCatching {
            val o = Json.parseToJsonElement(file.readText()).jsonObject
            (o["etag"] as? JsonPrimitive)?.content to o["body"]!!
        }.getOrNull()
    }

    private fun writeCache(key: String, etag: String?, body: JsonElement) {
        synchronized(memory) { memory[key] = etag to body }
        runCatching {
            val o = buildMap<String, JsonElement> {
                put("body", body)
                etag?.let { put("etag", JsonPrimitive(it)) }
            }
            cacheFile(key).writeText(JsonObject(o).toString())
        }
    }

    /** GET `/v1/sdk<path>`. Network or server failure falls back to the last copy this device saw. */
    suspend fun fetch(path: String, query: String = ""): JsonObject = fetchBody(path, query).also { DeliveredAssets.register(Assets.of(it)) }

    private suspend fun fetchBody(path: String, query: String): JsonObject = withContext(Dispatchers.IO) {
        val key = apiKey ?: throw MiniwallException("Miniwall: call Miniwall.configure(context, apiKey) before loading a placement")
        val url = URL("${apiUrl.trimEnd('/')}/v1/sdk$path?key=${URLEncoder.encode(key, "UTF-8")}&v=2$query")
        val cacheKey = cacheKey(path, key)
        val cached = readCache(cacheKey)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.useCaches = false
        connection.setRequestProperty("X-Miniwall-SDK", "android/${Miniwall.VERSION}")
        Miniwall.appHeader?.let { connection.setRequestProperty("X-Miniwall-App", it) }
        cached?.first?.let { connection.setRequestProperty("If-None-Match", it) }
        val status = try {
            connection.responseCode
        } catch (e: IOException) {
            cached?.let { return@withContext it.second as JsonObject }
            throw if (e is SocketTimeoutException) MiniwallException("Miniwall: the screen took too long to load") else e
        }
        try {
            if (status == 304 && cached != null) return@withContext cached.second as JsonObject
            if (status !in 200..299) {
                if (cached != null && status >= 500) return@withContext cached.second as JsonObject
                val message = runCatching {
                    ((Json.parseToJsonElement(connection.errorStream.bufferedReader().readText()) as JsonObject)["error"] as JsonObject)["message"]
                        .let { (it as JsonPrimitive).content }
                }.getOrNull()
                throw MiniwallException("Miniwall: ${message ?: "could not load the screen ($status)"}")
            }
            val body = Json.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
            writeCache(cacheKey, connection.getHeaderField("ETag"), body)
            body
        } finally {
            connection.disconnect()
        }
    }

    // ---------------------------------------------------------------- events

    fun enqueue(event: JsonObject) {
        if (!analytics || apiKey == null) return
        scope.launch {
            eventsLock.withLock {
                val list = loadPending()
                list += event
                if (list.size >= Events.MAX_BATCH) {
                    // A full batch is written (and sent) now; smaller queues wait for the debounce.
                    persist(list)
                    flushLocked()
                } else {
                    schedulePersist()
                    scheduleFlush(2000)
                }
            }
        }
    }

    /** Sends what is queued; also writes the queue to disk right away (the app may be about to go away). */
    fun flush() {
        scope.launch {
            eventsLock.withLock {
                if (dirty) persist(loadPending())
                flushLocked()
            }
        }
    }

    private fun loadPending(): MutableList<JsonObject> = pending ?: runCatching {
        (Json.parseToJsonElement(eventsFile.readText()) as JsonArray).mapNotNull { it as? JsonObject }.toMutableList()
    }.getOrElse { mutableListOf() }.also { pending = it }

    /**
     * Rewriting the whole file on every event is wasteful: write at most once per [PERSIST_DEBOUNCE_MS]. A process
     * death loses at most the events of that window. Only touched under [eventsLock].
     */
    private fun schedulePersist() {
        dirty = true
        if (persistScheduled) return
        persistScheduled = true
        scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            eventsLock.withLock {
                persistScheduled = false
                if (dirty) persist(loadPending())
            }
        }
    }

    private fun persist(list: List<JsonObject>) {
        dirty = false
        runCatching { eventsFile.writeText(JsonArray(list).toString()) }
    }

    private fun scheduleFlush(delayMs: Long) {
        if (flushScheduled) return
        flushScheduled = true
        scope.launch {
            delay(delayMs)
            flushScheduled = false
            eventsLock.withLock { flushLocked() }
        }
    }

    /** Sends one batch; on success sends the next. Network/server errors keep the events and retry in 30 s. */
    private fun flushLocked() {
        val key = apiKey ?: return
        val list = loadPending()
        if (list.isEmpty()) return
        // Events older than 7 days are dropped (the API would not take them anyway).
        val cutoff = IsoTime.format(System.currentTimeMillis() - 7 * 86_400_000L)
        list.removeAll { ((it["occurredAt"] as? JsonPrimitive)?.content ?: "") < cutoff }
        val batch = list.take(Events.MAX_BATCH)
        if (batch.isEmpty()) return persist(list)
        val ok = runCatching {
            val connection = URL("${apiUrl.trimEnd('/')}/v1/sdk/events?key=${URLEncoder.encode(key, "UTF-8")}").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Miniwall-SDK", "android/${Miniwall.VERSION}")
            Miniwall.appHeader?.let { connection.setRequestProperty("X-Miniwall-App", it) }
            connection.outputStream.use { it.write(Events.batches(batch).first().toByteArray()) }
            val status = connection.responseCode
            connection.disconnect()
            // 4xx: rejected for good, drop it.
            status < 500
        }.getOrDefault(false)
        if (ok) {
            repeat(batch.size) { list.removeAt(0) }
            persist(list)
            if (list.isNotEmpty()) flushLocked()
        } else {
            persist(list)
            scheduleFlush(30_000)
        }
    }

    private companion object {
        const val PERSIST_DEBOUNCE_MS = 500L
    }
}

/** Downloads images once (memory cache) for screens, icons and backgrounds. */
internal object Images {
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val loading = Mutex()

    fun cached(url: String): ImageBitmap? = cache.get(url)

    suspend fun load(url: String): ImageBitmap? = cached(url) ?: withContext(Dispatchers.IO) {
        loading.withLock { cached(url) } ?: runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            val bytes = try {
                connection.inputStream.use { DownloadLimits.readLimited(it, DownloadLimits.MAX_IMAGE_BYTES, connection.contentLengthLong) }
            } finally {
                connection.disconnect()
            }
            if (bytes == null) {
                Log.w("Miniwall", "Image skipped, larger than ${DownloadLimits.MAX_IMAGE_BYTES} bytes: $url")
                return@runCatching null
            }
            // Decode at the size a phone can show: a 4000 px photo as a full bitmap is 64 MB.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            bitmap?.asImageBitmap()?.also { cache.put(url, it) }
        }.getOrNull()
    }
}

/**
 * Custom fonts of a screen: the link the server delivered for the name (`assets.fonts`), else
 * `components_config.fonts[name].android` when that is a link to a TTF/OTF, is
 * downloaded once (disk cache) and registered; the screen waits for it at most [FontFiles.SHOW_WAIT_MS] and otherwise
 * shows with the system font (the font serves the next screen). Names that are not links
 * (older screens) keep the system font. A font that fails to load is skipped: texts use the system font.
 */
internal object FontFiles {
    /** How long a screen waits for its fonts before it shows with the system font; the download goes on and serves the next screen. */
    const val SHOW_WAIT_MS = 1500L
    /** [Miniwall.preload] runs in the background: it can wait for slow fonts. */
    const val PRELOAD_WAIT_MS = 10_000L

    private val failed = HashSet<String>()
    private val inFlight = HashMap<String, kotlinx.coroutines.Job>()

    suspend fun load(paywall: JsonObject?, waitMs: Long = SHOW_WAIT_MS) {
        val fonts = (paywall?.get("components_config") as? JsonObject)?.get("fonts") as? JsonObject ?: return
        val dir = File(Miniwall.appContext.cacheDir, "miniwall/fonts").apply { mkdirs() }
        // All fonts at once, in the background, and the screen waits at most [waitMs]: a slow network costs one short wait,
        // not one per font, and the screen uses the system font for what did not arrive. A font that arrives later stays
        // registered (and cached on disk) for the next screen.
        val jobs = ArrayList<kotlinx.coroutines.Job>()
        for ((name, declaration) in fonts) {
            if (Fonts.isRegistered(name)) continue
            val url = DeliveredAssets.fontLink(name, declaration as? JsonObject) ?: continue
            if (synchronized(failed) { url in failed }) continue
            val file = File(dir, Integer.toHexString(url.hashCode()) + ".ttf")
            jobs += synchronized(inFlight) {
                inFlight.getOrPut(url) {
                    Miniwall.scope.launch(Dispatchers.IO) {
                        try {
                            runCatching { download(name, url, file) }
                                .onFailure { Log.w("Miniwall", "Font $name not loaded: ${it.message}"); file.delete(); synchronized(failed) { failed.add(url) } }
                        } finally {
                            synchronized(inFlight) { inFlight.remove(url) }
                        }
                    }
                }
            }
        }
        if (jobs.isNotEmpty()) kotlinx.coroutines.withTimeoutOrNull(waitMs) { jobs.forEach { it.join() } }
    }

    private fun download(name: String, url: String, file: File) {
        if (!file.exists() || file.length() == 0L) {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            val bytes = try {
                connection.inputStream.use { DownloadLimits.readLimited(it, DownloadLimits.MAX_FONT_BYTES, connection.contentLengthLong) }
            } finally {
                connection.disconnect()
            }
            // Too large: the system font is used (the failure above marks the link as failed).
            if (bytes == null) error("font larger than ${DownloadLimits.MAX_FONT_BYTES} bytes")
            val part = File(file.parentFile, file.name + ".part")
            part.writeBytes(bytes)
            part.renameTo(file)
        }
        Fonts.register(name, file)
    }
}

/**
 * Icons and font links the server delivered next to screens (`assets`). Kept for the process:
 * a name always maps to the same file, so what an earlier screen brought serves a later one.
 */
internal object DeliveredAssets {
    private val icons = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val fonts = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun register(assets: DeliveryAssets) {
        icons.putAll(assets.icons)
        fonts.putAll(assets.fonts)
    }

    fun iconMarkup(url: String): String? = Assets.iconKey(url)?.let { icons[it] }

    fun fontLink(name: String, declaration: JsonObject?): String? = Assets.fontLink(name, declaration, "android", DeliveryAssets(fonts = fonts))
}

/** SVG icons (the templates' icons are SVG files; BitmapFactory cannot read them), parsed once per URL. The delivered SVG comes first; the link is the fallback. */
internal object Icons {
    private val cache = LruCache<String, SvgIcon>(256)
    private val failed = HashSet<String>()

    fun cached(url: String): SvgIcon? = cache.get(url) ?: DeliveredAssets.iconMarkup(url)?.let { Svg.parse(it) }?.also { cache.put(url, it) }

    suspend fun load(url: String): SvgIcon? = cached(url) ?: withContext(Dispatchers.IO) {
        if (synchronized(failed) { url in failed }) return@withContext null
        runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            val text = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            connection.disconnect()
            Svg.parse(text)
        }.getOrNull()?.also { cache.put(url, it) } ?: run { synchronized(failed) { failed.add(url) }; null }
    }
}
