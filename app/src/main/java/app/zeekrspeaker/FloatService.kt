package app.zeekrspeaker

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class FloatService : Service() {

    companion object {
        @Volatile
        var visible = false

        @Volatile
        var panelExpanded = false

        const val ACTION_REFRESH_SIZE =
            "app.zeekrspeaker.ACTION_REFRESH_SIZE"
    }

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        // Start the vehicle-speed reader as soon as the floating-button service
        // starts. This gives the ECARX sensor time to initialise before the
        // user presses the button, without delaying microphone activation.
        VehicleSpeed.start(this)

        if (intent?.action == ACTION_REFRESH_SIZE && rootView != null) {
            try {
                windowManager?.removeView(rootView)
            } catch (e: Exception) {
                Log.w("FloatService", "Could not remove old overlay", e)
            }

            rootView = null
            visible = false
        }

        if (rootView == null) {
            if (!Settings.canDrawOverlays(this)) {
                stopSelf()
                return START_NOT_STICKY
            }

            addButton()
        }

        armEngine()
        return START_STICKY
    }

    private fun armEngine() {
        if (LiveVoiceService.armed) return

        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, LiveVoiceService::class.java)
                    .setAction(LiveVoiceService.ACTION_ARM)
            )
        } catch (e: Exception) {
            Log.w("FloatService", "Could not start the audio service", e)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addButton() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val d = resources.displayMetrics.density

        fun dp(v: Float) = (v * d).toInt()

        val sizeScale = (Prefs.floatButtonSize(this) / 100f) * 1.2f

        // Independent scaling for the preset buttons.
        // At 100%, their original default dimensions are retained.
        val presetButtonScale = Prefs.presetButtonSize(this) / 100f

        // Independent scaling for preset label text.
        // This does not change the preset button dimensions.
        val presetTextScale = Prefs.presetTextSize(this) / 100f

        fun presetSdp(v: Float) =
            (v * d * 1.2f * presetButtonScale).toInt().coerceAtLeast(1)

        fun presetPanelDp(v: Float) =
            presetSdp(v * 1.5f)

        fun presetPanelWidthDp(v: Float) =
            presetSdp(v * 1.5f)

        fun sdp(v: Float) =
            (v * d * sizeScale).toInt().coerceAtLeast(1)

        fun panelDp(v: Float) = sdp(v * 1.5f)
        fun panelWidthDp(v: Float) = sdp(v * 1.5f)

        val clusterSize = sdp(80f)
        val mainSize = sdp(64f)
        val badgeSize = sdp(28f)

        val prefs = getSharedPreferences("float", Context.MODE_PRIVATE)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", dp(40f))
            y = prefs.getInt("y", dp(200f))
        }

        val idleColor = Color.argb(225, 40, 40, 44)
        val activeColor = Color.argb(245, 205, 40, 40)
        val orange = Color.rgb(255, 122, 26)

        val mainBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                if (LiveVoiceService.holdActive) activeColor else idleColor
            )
            setStroke(sdp(2f), Color.WHITE)
        }

        val icon = SpeakerIconView(this).apply {
            background = mainBg
            active = LiveVoiceService.holdActive
        }

        val grip = GripView(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(200, 90, 90, 96))
            }
        }

        val isLocked = Prefs.floatButtonLocked(this)

        grip.visibility = if (isLocked) View.GONE else View.VISIBLE

        val arrow = ChevronView(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(orange)
            }
        }

        val cluster = FrameLayout(this)

        cluster.addView(
            icon,
            FrameLayout.LayoutParams(
                mainSize,
                mainSize,
                Gravity.BOTTOM or Gravity.START
            )
        )

        cluster.addView(
            grip,
            FrameLayout.LayoutParams(
                badgeSize,
                badgeSize,
                Gravity.TOP or Gravity.END
            )
        )

        cluster.addView(
            arrow,
            FrameLayout.LayoutParams(
                badgeSize,
                badgeSize,
                Gravity.BOTTOM or Gravity.END
            )
        )

        // ---------------------------------------------------------
        // SOUND PANEL
        // ---------------------------------------------------------

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE

            setPadding(
                panelDp(8f),
                panelDp(8f),
                panelDp(8f),
                panelDp(8f)
            )

            background = GradientDrawable().apply {
                cornerRadius = panelDp(18f).toFloat()
                val presetColour = Prefs.presetButtonColour(this@FloatService)
                setColor(Color.argb(240, 26, 32, 38))
                setStroke(
                    panelDp(1.5f),
                    Color.argb(255, 70, 80, 90)
                )
            }
        }

        val slotViews = ArrayList<TextView>()

        fun styleSlot(view: TextView, playing: Boolean) {
            val presetColour = Prefs.presetButtonColour(this)
            val normalBackground = when (presetColour) {
                Prefs.PRESET_COLOUR_WHITE -> Color.WHITE
                Prefs.PRESET_COLOUR_SEMI_TRANSPARENT -> Color.TRANSPARENT
                else -> Color.argb(255, 45, 54, 62)
            }
            val normalText = if (presetColour == Prefs.PRESET_COLOUR_WHITE) {
                Color.rgb(27, 32, 38)
            } else {
                Color.WHITE
            }
            val normalStroke = when (presetColour) {
                Prefs.PRESET_COLOUR_WHITE -> Color.rgb(190, 198, 205)
                Prefs.PRESET_COLOUR_SEMI_TRANSPARENT -> Color.WHITE
                else -> orange
            }

            view.background = GradientDrawable().apply {
                cornerRadius = presetPanelDp(14f).toFloat()
                setColor(if (playing) orange else normalBackground)
                setStroke(presetPanelDp(1.5f), if (playing) orange else normalStroke)
            }
            view.setTextColor(if (playing) Color.rgb(27, 16, 4) else normalText)
        }

        // ---------------------------------------------------------
        // SELECTED PRESET COUNT AND GRID LAYOUT
        // ---------------------------------------------------------

        val presetCount = Prefs.floatPresetCount(this)

        val layout = Prefs.floatGridLayout(this)
        val parts = layout.split("x")

        val rows = parts[0].toInt()
        val columns = parts[1].toInt()

        // Keep all buttons the same width, even when labels are long.
        val screenWidth = resources.displayMetrics.widthPixels

        // Outer panel padding remains controlled by floating button size.
        val panelPadding = panelDp(8f) * 2

        // Individual preset button margins use the preset size setting.
        val buttonMargins = presetPanelDp(4f) * 2 * columns

        val availableWidth = (
                screenWidth - panelPadding - buttonMargins
                ).coerceAtLeast(columns)

        val adaptiveButtonWidth = (
                availableWidth / columns
                ).coerceAtMost(presetPanelWidthDp(76f))

        for (row in 0 until rows) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            for (col in 0 until columns) {
                val slot = row * columns + col

                // Only create buttons for the selected preset count.
                // Presets beyond this count remain saved in Prefs.
                if (slot >= presetCount) continue

                val button = TextView(this).apply {
                    gravity = Gravity.CENTER

                    // Text size is independent of button dimensions.
                    textSize = 15.6f * 1.2f * presetTextScale

                    typeface = Typeface.DEFAULT_BOLD

                    setLines(2)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    includeFontPadding = false
                    setHorizontallyScrolling(false)

                    setPadding(
                        presetPanelDp(4f),
                        presetPanelDp(4f),
                        presetPanelDp(4f),
                        presetPanelDp(4f)
                    )

                    setOnClickListener {
                        onSlotPressed(slot)
                    }
                }

                styleSlot(button, false)

                line.addView(
                    button,
                    LinearLayout.LayoutParams(
                        adaptiveButtonWidth,
                        presetSdp(78f)
                    ).apply {
                        setMargins(
                            presetPanelDp(4f),
                            presetPanelDp(4f),
                            presetPanelDp(4f),
                            presetPanelDp(4f)
                        )
                    }
                )

                slotViews.add(button)
            }

            panel.addView(line)
        }

        // ---------------------------------------------------------
        // EXPANSION DIRECTION
        // ---------------------------------------------------------

        val expandLeft =
            Prefs.floatExpandDirection(this) == Prefs.FLOAT_EXPAND_LEFT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            gravity = if (expandLeft) {
                Gravity.END
            } else {
                Gravity.START
            }
        }

        root.addView(
            cluster,
            LinearLayout.LayoutParams(clusterSize, clusterSize)
        )

        root.addView(
            panel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = sdp(4f)
            }
        )

        // ---------------------------------------------------------
        // REFRESH LABELS AND PLAYING STATE
        // ---------------------------------------------------------

        fun refreshLabels() {
            for (i in slotViews.indices) {
                slotViews[i].text = Prefs.displayLabel(this, i)
            }
        }

        fun highlight(playing: Int) {
            for (i in slotViews.indices) {
                styleSlot(slotViews[i], i == playing)
            }
        }

        // ---------------------------------------------------------
        // KEEP OVERLAY ON SCREEN
        // ---------------------------------------------------------

        fun clampToScreen() {
            val dm = resources.displayMetrics
            val w = root.width
            val h = root.height

            if (w <= 0 || h <= 0) return

            val nx = params.x.coerceIn(
                0,
                (dm.widthPixels - w).coerceAtLeast(0)
            )

            val ny = params.y.coerceIn(
                0,
                (dm.heightPixels - h).coerceAtLeast(0)
            )

            if (nx != params.x || ny != params.y) {
                params.x = nx
                params.y = ny

                try {
                    wm.updateViewLayout(root, params)
                } catch (e: Exception) {
                    Log.w("FloatService", "Could not reposition overlay", e)
                }
            }
        }

        // ---------------------------------------------------------
        // EXPAND / COLLAPSE
        // ---------------------------------------------------------

        var expanded = false
        var expandedDelta = 0

        fun setExpanded(on: Boolean) {
            if (expanded == on) return

            panelExpanded = on

            if (on) {
                refreshLabels()
                highlight(SoundPlayer.playingSlot)

                expanded = true
                arrow.expanded = true

                if (expandLeft) {
                    root.alpha = 0f
                }

                panel.visibility = View.VISIBLE

                try {
                    wm.updateViewLayout(root, params)
                } catch (e: Exception) {
                    Log.w("FloatService", "Could not update overlay", e)
                }

                if (expandLeft) {
                    root.post {
                        if (!expanded) return@post

                        expandedDelta = (
                                root.width - clusterSize
                                ).coerceAtLeast(0)

                        params.x -= expandedDelta

                        try {
                            wm.updateViewLayout(root, params)
                        } catch (e: Exception) {
                            Log.w("FloatService", "Could not reposition overlay", e)
                        }

                        root.alpha = 1f

                        handler.postDelayed({
                            if (expanded) {
                                clampToScreen()
                            }
                        }, 100)
                    }
                } else {
                    handler.postDelayed({
                        if (expanded) {
                            clampToScreen()
                        }
                    }, 100)
                }

            } else {
                expanded = false
                arrow.expanded = false

                if (expandLeft) {
                    // Keep the overlay invisible while it shrinks and is repositioned.
                    root.alpha = 0f

                    // Save the amount by which the expanded overlay was shifted.
                    val deltaToRestore = expandedDelta
                    expandedDelta = 0

                    // Shrink the root first. Do not move the window while it still
                    // has the expanded width, otherwise the button briefly jumps.
                    panel.visibility = View.GONE

                    try {
                        wm.updateViewLayout(root, params)
                    } catch (e: Exception) {
                        Log.w("FloatService", "Could not update overlay", e)
                    }

                    // Wait until Android has completed the collapsed layout before
                    // restoring the window's original X position.
                    val collapseObserver = root.viewTreeObserver
                    collapseObserver.addOnPreDrawListener(
                        object : ViewTreeObserver.OnPreDrawListener {
                            override fun onPreDraw(): Boolean {
                                if (root.viewTreeObserver.isAlive) {
                                    root.viewTreeObserver.removeOnPreDrawListener(this)
                                }

                                root.post {
                                    if (!expanded) {
                                        params.x += deltaToRestore

                                        try {
                                            wm.updateViewLayout(root, params)
                                        } catch (e: Exception) {
                                            Log.w(
                                                "FloatService",
                                                "Could not reposition collapsed overlay",
                                                e
                                            )
                                        }

                                        // Reveal only after the corrected position
                                        // has reached the next layout/draw pass.
                                        val revealObserver = root.viewTreeObserver
                                        revealObserver.addOnPreDrawListener(
                                            object : ViewTreeObserver.OnPreDrawListener {
                                                override fun onPreDraw(): Boolean {
                                                    if (root.viewTreeObserver.isAlive) {
                                                        root.viewTreeObserver
                                                            .removeOnPreDrawListener(this)
                                                    }

                                                    if (!expanded) {
                                                        root.alpha = 1f
                                                    }
                                                    return true
                                                }
                                            }
                                        )
                                    }
                                }
                                return true
                            }
                        }
                    )
                } else {
                    panel.visibility = View.GONE

                    try {
                        wm.updateViewLayout(root, params)
                    } catch (e: Exception) {
                        Log.w("FloatService", "Could not update overlay", e)
                    }
                }
            }
        }

        // ---------------------------------------------------------
        // MAIN BUTTON APPEARANCE
        // ---------------------------------------------------------

        fun setLook(on: Boolean) {
            mainBg.setColor(if (on) activeColor else idleColor)
            icon.active = on
        }

        // ---------------------------------------------------------
        // MAIN BUTTON TOUCH CONTROLS
        // ---------------------------------------------------------

        var pressedMode = Prefs.mode(this)

        icon.setOnTouchListener { _, event ->
            when (event.action) {

                MotionEvent.ACTION_DOWN -> {
                    pressedMode = Prefs.mode(this)

                    if (pressedMode == Prefs.MODE_ACTIVATE) {
                        if (LiveVoiceService.holdActive) {
                            LiveVoiceService.setHeld(false)
                            setLook(false)
                        } else {
                            setLook(true)

                            LiveVoiceService.setHeld(
                                true,
                                Prefs.offSeconds(this) * 1000L
                            )

                            armEngine()
                        }
                    } else {
                        setLook(true)
                        LiveVoiceService.setHeld(true)
                        armEngine()
                    }

                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (pressedMode == Prefs.MODE_TALK) {
                        LiveVoiceService.setHeld(false)
                        setLook(false)
                    }

                    true
                }

                else -> true
            }
        }

        // ---------------------------------------------------------
        // DRAG HANDLE
        // ---------------------------------------------------------

        if (!isLocked) {
            var startX = 0f
            var startY = 0f
            var startParamX = 0
            var startParamY = 0

            grip.setOnTouchListener { _, event ->
                when (event.action) {

                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        startParamX = params.x
                        startParamY = params.y
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        params.x =
                            startParamX + (event.rawX - startX).toInt()

                        params.y =
                            startParamY + (event.rawY - startY).toInt()

                        try {
                            wm.updateViewLayout(root, params)
                        } catch (e: Exception) {
                            Log.w("FloatService", "Could not move overlay", e)
                        }

                        true
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        prefs.edit()
                            .putInt("x", params.x)
                            .putInt("y", params.y)
                            .apply()

                        true
                    }

                    else -> false
                }
            }
        }

        // ---------------------------------------------------------
        // EXPANSION ARROW
        // ---------------------------------------------------------

        arrow.setOnClickListener {
            setExpanded(!expanded)
        }

        // ---------------------------------------------------------
        // SOUND PLAYER CALLBACKS
        // ---------------------------------------------------------

        SoundPlayer.listener = { playing ->
            handler.post {
                highlight(playing)
            }
        }

        LiveVoiceService.talkingListener = { talking ->
            if (!talking) {
                handler.post {
                    if (!LiveVoiceService.holdActive) {
                        setLook(false)
                    }
                }
            }
        }

        refreshLabels()

        // ---------------------------------------------------------
        // RESTORE PANEL STATE BEFORE DISPLAYING OVERLAY
        // ---------------------------------------------------------

        // Prepare the expanded layout before adding the overlay.
        // For expand-left, setExpanded() hides the root and posts
        // the width correction. The root is added before that
        // posted correction executes, so the first visible frame
        // is already in the correct position.
        if (panelExpanded) {
            setExpanded(true)
        }

        wm.addView(root, params)

        rootView = root
        visible = true

        SoundPlayer.preload(applicationContext)
    }

    // ---------------------------------------------------------
    // PRESET BUTTON PRESSED
    // ---------------------------------------------------------

    private fun onSlotPressed(slot: Int) {
        if (Prefs.slot(this, slot).source.isEmpty()) {
            Toast.makeText(
                this,
                "No sound chosen for ${Prefs.displayLabel(this, slot)}. Choose one in Settings.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        SoundPlayer.toggle(applicationContext, slot)
    }

    // ---------------------------------------------------------
    // SERVICE DESTROYED
    // ---------------------------------------------------------

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)

        LiveVoiceService.talkingListener = null
        LiveVoiceService.setHeld(false)

        SoundPlayer.listener = null
        SoundPlayer.stop()

        rootView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.w("FloatService", "Could not remove overlay", e)
            }
        }

        rootView = null
        visible = false
        panelExpanded = false

        stopService(Intent(this, LiveVoiceService::class.java))

        super.onDestroy()
    }
}


// ---------------------------------------------------------
// SPEAKER ICON
// ---------------------------------------------------------

private class SpeakerIconView(context: Context) : View(context) {

    var active: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val body = Path()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val u = width / 10f

        body.reset()
        body.moveTo(cx - 3.2f * u, cy - 1.4f * u)
        body.lineTo(cx - 1.6f * u, cy - 1.4f * u)
        body.lineTo(cx + 0.6f * u, cy - 3.0f * u)
        body.lineTo(cx + 0.6f * u, cy + 3.0f * u)
        body.lineTo(cx - 1.6f * u, cy + 1.4f * u)
        body.lineTo(cx - 3.2f * u, cy + 1.4f * u)
        body.close()

        canvas.drawPath(body, fill)

        line.strokeWidth = 0.55f * u

        val ax = cx + 0.6f * u
        val waves = if (active) 2 else 1

        for (i in 1..waves) {
            val r = (0.9f + 1.5f * i) * u

            canvas.drawArc(
                RectF(ax - r, cy - r, ax + r, cy + r),
                -45f,
                90f,
                false,
                line
            )
        }
    }
}


// ---------------------------------------------------------
// DRAG HANDLE
// ---------------------------------------------------------

private class GripView(context: Context) : View(context) {

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = w * 0.055f

        for (ix in 0..1) {
            for (iy in 0..2) {
                canvas.drawCircle(
                    w * (0.40f + 0.20f * ix),
                    h * (0.28f + 0.22f * iy),
                    r,
                    dot
                )
            }
        }
    }
}


// ---------------------------------------------------------
// ARROW
// ---------------------------------------------------------

private class ChevronView(context: Context) : View(context) {

    var expanded: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        paint.strokeWidth = w * 0.10f

        val cx = w / 2f
        val cy = h / 2f
        val a = w * 0.20f
        val dir = if (expanded) -1f else 1f

        path.reset()
        path.moveTo(cx - a, cy - dir * a * 0.5f)
        path.lineTo(cx, cy + dir * a * 0.5f)
        path.lineTo(cx + a, cy - dir * a * 0.5f)

        canvas.drawPath(path, paint)
    }
}
