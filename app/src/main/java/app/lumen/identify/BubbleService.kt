// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.identify

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.lumen.Graph
import app.lumen.MainActivity
import app.lumen.R
import coil3.asDrawable
import app.lumen.data.IdentifiedSong
import app.lumen.data.source.Http
import app.lumen.engine.Guardian
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The floating Lumen bubble. It sits over every app (Instagram, YouTube, anything); tap it while a
 * song plays and it listens, names the song and offers to play it in Lumen. Drag it anywhere; drop
 * it on the ✕ at the bottom to turn it off.
 *
 * A foreground service of type "microphone": Android only lets an app use the mic from the
 * background this way, and it keeps a notification up so the user always knows it's on. The mic is
 * used only between a tap and the answer.
 */
class BubbleService : Service() {

    companion object {
        private const val CHANNEL = "song_finder"
        private const val NOTIFICATION_ID = 7301
        private const val ACTION_STOP = "app.lumen.identify.STOP"
        private const val PREFS = "bubble"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        fun canRun(context: Context) = Settings.canDrawOverlays(context) && SongFinder.hasMic(context)

        /** Starts the bubble. Call from the app while it's on screen (Android requires it for the mic). */
        fun start(context: Context): Boolean {
            if (!canRun(context)) return false
            return runCatching { ContextCompat.startForegroundService(context, Intent(context, BubbleService::class.java)); true }
                .onFailure { Guardian.record("bubble start", it) }.getOrDefault(false)
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, BubbleService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wm: WindowManager
    private var bubble: BubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var trash: View? = null
    private var card: Card? = null
    private var listening: Job? = null
    private var lastSong: IdentifiedSong? = null

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun dpi(v: Float) = dp(v).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (!goForeground() || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if (bubble == null) showBubble()
        _running.value = true
        // Not sticky: after the system ends the process, the user turns it on again from the app
        // (Android won't hand the mic to a service that restarts by itself in the background).
        return START_NOT_STICKY
    }

    private fun goForeground(): Boolean = runCatching {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Song finder bubble", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the floating song finder is on"
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 40, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(this, 41, Intent(this, BubbleService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lumen)
            .setContentTitle("Song finder is on")
            .setContentText("Tap the Lumen bubble while a song plays")
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
        true
    }.onFailure { Guardian.record("bubble foreground", it) }.getOrDefault(false)

    private fun overlayParams(w: Int, h: Int, focusable: Boolean = false) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    // ---- The bubble ----------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val view = BubbleView(this)
        val size = dpi(80f)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val screen = screenSize()
        val params = overlayParams(size, size).apply {
            x = prefs.getInt("x", screen.first - size)
            y = prefs.getInt("y", (screen.second * 0.42f).toInt())
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; dragging = false
                    view.pressed(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) { dragging = true; showTrash() }
                    if (dragging) {
                        params.x = (startX + dx).toInt()
                        params.y = (startY + dy).toInt()
                        safeUpdate(view, params)
                        trash?.isActivated = overTrash(params)
                        trash?.let { t -> t.scaleX = if (t.isActivated) 1.25f else 1f; t.scaleY = t.scaleX }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.pressed(false)
                    if (dragging) {
                        val drop = overTrash(params)
                        hideTrash()
                        if (drop) { stopSelf(); return@setOnTouchListener true }
                        snapToEdge(view, params)
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) onBubbleTap()
                }
            }
            true
        }
        runCatching { wm.addView(view, params) }.onFailure { Guardian.record("bubble add", it); stopSelf(); return }
        bubble = view
        bubbleParams = params
        view.appear()
    }

    private fun safeUpdate(v: View, p: WindowManager.LayoutParams) {
        runCatching { if (v.isAttachedToWindow) wm.updateViewLayout(v, p) }
    }

    private fun screenSize(): Pair<Int, Int> {
        val m = resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val (w, h) = screenSize()
        val size = view.width.takeIf { it > 0 } ?: dpi(80f)
        val targetX = if (params.x + size / 2 < w / 2) -dpi(6f) else w - size + dpi(6f)
        params.y = params.y.coerceIn(dpi(24f), h - size - dpi(48f))
        val from = params.x
        ValueAnimator.ofInt(from, targetX).apply {
            duration = 260
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener { params.x = it.animatedValue as Int; safeUpdate(view, params) }
            start()
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("x", targetX).putInt("y", params.y).apply()
    }

    private fun showTrash() {
        if (trash != null) return
        val size = dpi(64f)
        val v = object : View(this) {
            private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
            private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = dp(3f); strokeCap = Paint.Cap.ROUND }
            override fun onDraw(c: Canvas) {
                fill.color = if (isActivated) Color.parseColor("#E5484D") else Color.argb(200, 21, 23, 77)
                val r = width / 2f
                c.drawCircle(r, r, r, fill)
                val a = dp(9f)
                c.drawLine(r - a, r - a, r + a, r + a, line)
                c.drawLine(r + a, r - a, r - a, r + a, line)
            }
            override fun setActivated(activated: Boolean) { val ch = activated != isActivated; super.setActivated(activated); if (ch) invalidate() }
        }
        val (w, h) = screenSize()
        val p = overlayParams(size, size).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            x = (w - size) / 2; y = h - size - dpi(96f)
        }
        runCatching { wm.addView(v, p) }.onSuccess {
            trash = v
            v.alpha = 0f; v.animate().alpha(1f).setDuration(150).start()
        }
    }

    private fun hideTrash() {
        trash?.let { runCatching { wm.removeView(it) } }
        trash = null
    }

    private fun overTrash(params: WindowManager.LayoutParams): Boolean {
        val (w, h) = screenSize()
        val size = dpi(80f)
        val bx = params.x + size / 2f; val by = params.y + size / 2f
        val tx = w / 2f; val ty = h - dpi(64f) / 2f - dpi(96f)
        return hypot(bx - tx, by - ty) < dp(80f)
    }

    // ---- Listening -------------------------------------------------------------------------------

    private fun onBubbleTap() {
        if (listening?.isActive == true) {
            // Second tap: stop listening.
            listening?.cancel()
            bubble?.mode = BubbleView.Mode.IDLE
            hideCard()
            return
        }
        listen()
    }

    private fun listen() {
        val b = bubble ?: return
        if (!SongFinder.hasMic(this)) {
            showCard().message("Lumen needs the microphone", "Open Lumen and allow it, then try again.", retry = false)
            return
        }
        b.mode = BubbleView.Mode.LISTENING
        b.progress = 0f
        showCard().listening()
        listening = scope.launch {
            val outcome = SongFinder.identify(this@BubbleService) { p -> b.post { b.progress = p } }
            when (outcome) {
                is SongFinder.Outcome.Found -> {
                    lastSong = outcome.song
                    Graph.store.addIdentified(outcome.song)
                    b.mode = BubbleView.Mode.FOUND
                    showCard().found(outcome.song)
                }
                SongFinder.Outcome.NotFound -> { b.mode = BubbleView.Mode.IDLE; showCard().message("Couldn't name this one", "Try again closer to the music, or when the song is clearer.") }
                SongFinder.Outcome.TooQuiet -> { b.mode = BubbleView.Mode.IDLE; showCard().message("It's too quiet", "Play the song out loud (the mic can't hear headphones).") }
                SongFinder.Outcome.Offline -> { b.mode = BubbleView.Mode.IDLE; showCard().message("No internet", "Naming a song needs a connection. Try again in a moment.") }
                SongFinder.Outcome.MicBusy -> { b.mode = BubbleView.Mode.IDLE; showCard().message("The mic is busy", "Another app is using it (a call or a voice note). Try again after.") }
                SongFinder.Outcome.NoPermission -> { b.mode = BubbleView.Mode.IDLE; showCard().message("Lumen needs the microphone", "Open Lumen and allow it, then try again.", retry = false) }
            }
            if (outcome is SongFinder.Outcome.Found) {
                // The bubble settles back after a moment; the card stays until dismissed.
                delay(2500)
                if (b.mode == BubbleView.Mode.FOUND) b.mode = BubbleView.Mode.IDLE
            }
        }
    }

    private fun playInLumen(song: IdentifiedSong) {
        hideCard()
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_FIND_SONG, Http.json.encodeToString(IdentifiedSong.serializer(), song))
        runCatching { startActivity(intent) }.onFailure { Guardian.record("bubble open", it) }
    }

    // ---- The card --------------------------------------------------------------------------------

    private fun showCard(): Card {
        card?.let { return it }
        val c = Card()
        val (w, _) = screenSize()
        val p = overlayParams(minOf(w - dpi(24f), dpi(460f)), WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dpi(44f)
        }
        runCatching { wm.addView(c.root, p) }.onFailure { Guardian.record("bubble card", it) }
        c.root.translationY = -dp(40f); c.root.alpha = 0f
        c.root.animate().translationY(0f).alpha(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        card = c
        return c
    }

    private fun hideCard() {
        val c = card ?: return
        card = null
        c.root.animate().alpha(0f).translationY(-dp(30f)).setDuration(160).withEndAction { runCatching { wm.removeView(c.root) } }.start()
    }

    /** The answer panel at the top of the screen. */
    private inner class Card {
        val root = LinearLayout(this@BubbleService).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(12f), dpi(12f), dpi(8f), dpi(12f))
            background = GradientDrawable().apply { cornerRadius = dp(20f); setColor(Color.parseColor("#15174D")) }
            elevation = dp(10f)
        }
        private val art = ImageView(this@BubbleService).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(12f); setColor(Color.parseColor("#5466EC")) }
            clipToOutline = true
            setImageResource(R.drawable.lumen_mark)
            setPadding(dpi(12f), dpi(12f), dpi(12f), dpi(12f))
            setColorFilter(Color.WHITE)
        }
        private val title = text(16f, bold = true, color = Color.WHITE)
        private val subtitle = text(13f, bold = false, color = Color.argb(190, 255, 246, 240))
        private val action = pill("Play in Lumen")
        private val close = TextView(this@BubbleService).apply {
            text = "✕"; textSize = 16f; setTextColor(Color.argb(200, 255, 255, 255)); gravity = Gravity.CENTER
            setPadding(dpi(10f), dpi(8f), dpi(10f), dpi(8f))
            setOnClickListener { listening?.cancel(); bubble?.mode = BubbleView.Mode.IDLE; hideCard() }
        }

        init {
            root.addView(art, LinearLayout.LayoutParams(dpi(56f), dpi(56f)))
            val col = LinearLayout(this@BubbleService).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpi(12f), 0, dpi(8f), 0)
                addView(title); addView(subtitle)
            }
            root.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(action)
            root.addView(close)
        }

        private fun text(size: Float, bold: Boolean, color: Int) = TextView(this@BubbleService).apply {
            textSize = size; setTextColor(color); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        private fun pill(label: String) = TextView(this@BubbleService).apply {
            text = label; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#15174D")); gravity = Gravity.CENTER
            setPadding(dpi(14f), dpi(9f), dpi(14f), dpi(9f))
            background = GradientDrawable().apply { cornerRadius = dp(50f); setColor(Color.parseColor("#FFB29E")) }
        }

        private fun resetArt() {
            art.setImageResource(R.drawable.lumen_mark)
            art.setPadding(dpi(12f), dpi(12f), dpi(12f), dpi(12f))
            art.setColorFilter(Color.WHITE)
        }

        fun listening() {
            resetArt()
            title.text = "Listening…"
            subtitle.text = "Keep the music playing out loud"
            action.visibility = View.GONE
        }

        fun message(head: String, body: String, retry: Boolean = true) {
            resetArt()
            title.text = head
            subtitle.text = body
            action.visibility = if (retry) View.VISIBLE else View.GONE
            action.text = "Try again"
            action.setOnClickListener { listen() }
        }

        fun found(song: IdentifiedSong) {
            title.text = song.title
            subtitle.text = song.artist.ifBlank { "Found it" }
            action.visibility = View.VISIBLE
            action.text = "Play in Lumen"
            action.setOnClickListener { playInLumen(song) }
            song.artwork?.let { url ->
                runCatching {
                    val request = coil3.request.ImageRequest.Builder(this@BubbleService).data(url)
                        .target(onSuccess = { image ->
                            art.clearColorFilter(); art.setPadding(0, 0, 0, 0)
                            art.setImageDrawable(image.asDrawable(resources))
                        })
                        .build()
                    coil3.SingletonImageLoader.get(this@BubbleService).enqueue(request)
                }
            }
        }
    }

    override fun onDestroy() {
        _running.value = false
        listening?.cancel()
        scope.cancel()
        hideTrash()
        card?.let { runCatching { wm.removeView(it.root) } }
        card = null
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        super.onDestroy()
    }
}

/**
 * The bubble itself: the Lumen mark on a round indigo button. It breathes gently while waiting, sends
 * out ripples and fills a ring while listening, and flashes when it finds the song.
 */
private class BubbleView(context: Context) : FrameLayout(context) {
    enum class Mode { IDLE, LISTENING, FOUND }

    var mode = Mode.IDLE
        set(value) { field = value; invalidate() }
    var progress = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    private val d = resources.displayMetrics.density
    private val mark = androidx.core.content.res.ResourcesCompat.getDrawable(resources, R.drawable.lumen_mark, null)!!.mutate().apply { setTint(Color.WHITE) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5466EC") }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 10, 10, 40) }
    private val ripple = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f * d; color = Color.parseColor("#FFB29E") }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#FFB29E") }
    private val arc = RectF()
    private var phase = 0f
    private var pressScale = 1f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        interpolator = null
        addUpdateListener { phase = it.animatedFraction; invalidate() }
    }

    init {
        setWillNotDraw(false)
        contentDescription = "Lumen song finder. Tap to name the song playing."
    }

    fun pressed(down: Boolean) {
        animate().cancel()
        pressScale = if (down) 0.9f else 1f
        invalidate()
    }

    fun appear() {
        scaleX = 0f; scaleY = 0f
        animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(1.6f)).start()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val base = 26f * d
        val breathe = when (mode) {
            Mode.IDLE -> 1f + 0.035f * sin(phase * 2 * Math.PI).toFloat()
            Mode.LISTENING -> 1f + 0.06f * sin(phase * 6 * Math.PI).toFloat()
            Mode.FOUND -> 1.08f
        }
        val r = base * breathe * pressScale

        if (mode == Mode.LISTENING) {
            for (i in 0 until 3) {
                val t = (phase * 2 + i / 3f) % 1f
                ripple.alpha = ((1f - t) * 200).toInt()
                c.drawCircle(cx, cy, r + t * 13f * d, ripple)
            }
        } else if (mode == Mode.IDLE) {
            ripple.alpha = (60 + 50 * sin(phase * 2 * Math.PI)).toInt().coerceIn(0, 255)
            c.drawCircle(cx, cy, r + 4f * d, ripple)
        }

        c.drawCircle(cx, cy + 2f * d, r, shadow)
        fill.color = if (mode == Mode.FOUND) Color.parseColor("#3E4FD8") else Color.parseColor("#5466EC")
        c.drawCircle(cx, cy, r, fill)

        if (mode == Mode.LISTENING || mode == Mode.FOUND) {
            arc.set(cx - r - 5f * d, cy - r - 5f * d, cx + r + 5f * d, cy + r + 5f * d)
            c.drawArc(arc, -90f, 360f * (if (mode == Mode.FOUND) 1f else progress), false, ring)
        }

        val m = (r * 1.12f).toInt()
        mark.setBounds((cx - m / 2f).toInt(), (cy - m / 2f).toInt(), (cx + m / 2f).toInt(), (cy + m / 2f).toInt())
        mark.draw(c)
    }
}
