package open.source.white.ui.reader

import android.app.Activity
import android.provider.Settings
import android.view.MotionEvent
import android.view.ViewConfiguration
import open.source.white.common.AppConstants
import kotlin.math.abs

class LeftEdgeBrightnessController(
    private val activity: Activity,
    private val initialBrightness: Float,
    private val onBrightnessPersist: (Float) -> Unit
) {

    private val density = activity.resources.displayMetrics.density
    private val edgeWidthPx = AppConstants.LEFT_EDGE_GESTURE_DP * density
    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var startedInLeftEdge = false
    private var isDraggingBrightness = false
    private var currentBrightness = resolveInitialBrightness(initialBrightness)

    init {
        if (initialBrightness in AppConstants.MIN_BRIGHTNESS..AppConstants.MAX_BRIGHTNESS) {
            applyWindowBrightness(initialBrightness)
        }
    }

    fun onDispatchTouchEvent(
        event: MotionEvent,
        dispatchToSuper: (MotionEvent) -> Boolean
    ): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(event)
            MotionEvent.ACTION_MOVE -> {
                if (handleMove(event, dispatchToSuper)) {
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (handleFinish()) {
                    return true
                }
            }
        }
        return dispatchToSuper(event)
    }

    private fun handleDown(event: MotionEvent) {
        downX = event.x
        downY = event.y
        lastY = event.y
        startedInLeftEdge = event.x <= edgeWidthPx
        isDraggingBrightness = false
        if (startedInLeftEdge) {
            currentBrightness = readActiveBrightness()
        }
    }

    private fun handleMove(
        event: MotionEvent,
        dispatchToSuper: (MotionEvent) -> Boolean
    ): Boolean {
        if (!startedInLeftEdge) return false

        val totalDx = abs(event.x - downX)
        val totalDy = abs(event.y - downY)

        if (!isDraggingBrightness) {
            if (totalDx > touchSlop && totalDx > totalDy) {
                startedInLeftEdge = false
                return false
            }
            if (totalDy > touchSlop && totalDy > totalDx) {
                isDraggingBrightness = true
                cancelChildTouch(event, dispatchToSuper)
            } else {
                return false
            }
        }

        val dy = lastY - event.y
        lastY = event.y
        val screenHeight = activity.resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val swipeSpan = screenHeight * AppConstants.BRIGHTNESS_SWIPE_HEIGHT_RATIO
        val delta = dy / swipeSpan
        currentBrightness = (currentBrightness + delta).coerceIn(
            AppConstants.MIN_BRIGHTNESS,
            AppConstants.MAX_BRIGHTNESS
        )
        applyWindowBrightness(currentBrightness)
        return true
    }

    private fun handleFinish(): Boolean {
        val wasDragging = isDraggingBrightness
        startedInLeftEdge = false
        isDraggingBrightness = false
        if (wasDragging) {
            onBrightnessPersist(currentBrightness)
            return true
        }
        return false
    }

    private fun cancelChildTouch(
        event: MotionEvent,
        dispatchToSuper: (MotionEvent) -> Boolean
    ) {
        val cancelEvent = MotionEvent.obtain(event)
        cancelEvent.action = MotionEvent.ACTION_CANCEL
        dispatchToSuper(cancelEvent)
        cancelEvent.recycle()
    }

    private fun resolveInitialBrightness(saved: Float): Float {
        if (saved in AppConstants.MIN_BRIGHTNESS..AppConstants.MAX_BRIGHTNESS) {
            return saved
        }
        return readSystemBrightness()
    }

    private fun readActiveBrightness(): Float {
        val windowVal = activity.window.attributes.screenBrightness
        if (windowVal in AppConstants.MIN_BRIGHTNESS..AppConstants.MAX_BRIGHTNESS) {
            return windowVal
        }
        return currentBrightness.coerceIn(AppConstants.MIN_BRIGHTNESS, AppConstants.MAX_BRIGHTNESS)
    }

    private fun readSystemBrightness(): Float {
        return try {
            val raw = Settings.System.getInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
            (raw / 255f).coerceIn(AppConstants.MIN_BRIGHTNESS, AppConstants.MAX_BRIGHTNESS)
        } catch (error: Exception) {
            AppConstants.DEFAULT_FALLBACK_BRIGHTNESS
        }
    }

    private fun applyWindowBrightness(value: Float) {
        val params = activity.window.attributes
        params.screenBrightness = value.coerceIn(
            AppConstants.MIN_BRIGHTNESS,
            AppConstants.MAX_BRIGHTNESS
        )
        activity.window.attributes = params
    }
}

