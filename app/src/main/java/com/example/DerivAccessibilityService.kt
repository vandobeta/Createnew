package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import android.view.WindowManager
import android.view.Gravity
import android.graphics.PixelFormat
import android.view.View
import android.view.MotionEvent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Canvas
import android.content.Context

class DerivAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: DerivAccessibilityService? = null

        // Reticle coordinates (default values until dragged)
        var reticleX: Float = 500f
        var reticleY: Float = 1100f
        
        // Static flow for overlay auto clicker override switch state
        val overlayClickerActive = kotlinx.coroutines.flow.MutableStateFlow(true)
        
        fun getInstance(): DerivAccessibilityService? {
            return instance
        }

        fun isServiceEnabled(): Boolean {
            return instance != null
        }

        fun showReticleOverlay(context: Context) {
            val service = instance ?: return
            service.showOverlay()
        }

        fun hideReticleOverlay() {
            val service = instance ?: return
            service.hideOverlay()
        }

        fun executeClickAtReticle(): Boolean {
            val service = instance ?: return false
            return executeClickAt(reticleX, reticleY)
        }

        fun executeClickAt(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            
            // 1. ALWAYS execute precision hardware gesture tap FIRST to bypass browser/WebView programmatic false-positive node click limits
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Log.d("DerivAccessibility", "Executing high-precision primary hardware gesture tap at ($x, $y)")
                try {
                    val path = Path()
                    path.moveTo(x, y)
                    path.lineTo(x, y) // Small path move traces a robust physical screen hit
                    val stroke = GestureDescription.StrokeDescription(path, 0, 80) // 80ms duration is standard tactile swipe/click hold
                    val gestureBuilder = GestureDescription.Builder()
                    gestureBuilder.addStroke(stroke)
                    val success = service.dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            super.onCompleted(gestureDescription)
                            Log.d("DerivAccessibility", "Primary precision gesture tap successfully completed at ($x, $y)")
                        }
                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            super.onCancelled(gestureDescription)
                            Log.e("DerivAccessibility", "Primary precision gesture tap cancelled/failed at ($x, $y)")
                        }
                    }, null)
                    
                    if (success) {
                        return true
                    }
                } catch (e: Exception) {
                    Log.e("DerivAccessibility", "Hardware gesture tap failed with exception, falling back to programmatic node clicks: ${e.message}")
                }
            }
            
            // 2. Programmatic click fallback if gesture dispatch is unsupported or fails
            try {
                if (performNodeClickAt(service, x, y)) {
                    Log.d("DerivAccessibility", "Programmatic fallback node click successful at ($x, $y)")
                    return true
                }
            } catch (e: Exception) {
                Log.e("DerivAccessibility", "Error during programmatic fallback click: ${e.message}")
            }
            
            return false
        }

        private fun performNodeClickAt(service: DerivAccessibilityService, x: Float, y: Float): Boolean {
            val xInt = x.toInt()
            val yInt = y.toInt()
            
            // Query modern system windows list to find nodes across all apps/dialogs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val windowList = service.windows
                if (windowList != null) {
                    for (window in windowList) {
                        val root = window.root ?: continue
                        val clicked = findAndClickNodeDeepest(root, xInt, yInt)
                        if (clicked) {
                            return true
                        }
                    }
                }
            }
            
            // Fallback: active window root node hierarchy directly
            val activeRoot = service.rootInActiveWindow ?: return false
            return findAndClickNodeDeepest(activeRoot, xInt, yInt)
        }

        private fun findAndClickNodeDeepest(node: AccessibilityNodeInfo, x: Int, y: Int): Boolean {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.contains(x, y)) {
                return false
            }

            // 1. Search children recursively to find the deepest match (prioritizing leaf elements like buttons and text keys)
            val childrenCount = node.childCount
            for (i in 0 until childrenCount) {
                val child = node.getChild(i) ?: continue
                val clicked = findAndClickNodeDeepest(child, x, y)
                if (clicked) {
                    return true
                }
            }

            // 2. If no matching child was clicked, search up starting from this element for the closest clickable parent
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable) {
                    val success = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (success) {
                        Log.d("DerivAccessibility", "Executed real programmatic click (no simulation) on ${current.className} at bounds: ${bounds.toShortString()}")
                        return true
                    }
                }
                current = current.parent
            }

            return false
        }
    }

    private var windowManager: WindowManager? = null
    private var reticleView: FloatingReticleView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Log.d("DerivAccessibility", "Accessibility service connected")
    }

    private fun showOverlay() {
        if (reticleView != null) return
        val wm = windowManager ?: return
        
        val density = resources.displayMetrics.density
        val sizePx = (70 * density).toInt() // Larger overlay size for high touch visibility
        
        val layoutParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            gravity = Gravity.TOP or Gravity.START
            width = sizePx
            height = sizePx + (48 * density).toInt()  // extra space for the bottom drag text label and custom status pill
            x = (resources.displayMetrics.widthPixels - sizePx) / 2
            y = (resources.displayMetrics.heightPixels - (sizePx + (48 * density).toInt())) / 2
        }

        // Initialize reticle position matching window layout start position
        reticleX = layoutParams.x + (sizePx / 2f)
        reticleY = layoutParams.y + (sizePx / 2f)

        val newView = FloatingReticleView(this, wm, layoutParams, sizePx)
        reticleView = newView
        try {
            wm.addView(newView, layoutParams)
        } catch (e: Exception) {
            Log.e("DerivAccessibility", "Error adding overlay view: ${e.message}")
        }
    }

    private fun hideOverlay() {
        val view = reticleView ?: return
        val wm = windowManager ?: return
        try {
            wm.removeView(view)
        } catch (e: Exception) {
            Log.e("DerivAccessibility", "Error removing overlay view: ${e.message}")
        }
        reticleView = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
        if (instance == this) {
            instance = null
        }
    }

    // Outer View class definition
    private class FloatingReticleView(
        context: Context,
        private val wm: WindowManager,
        val layoutParams: WindowManager.LayoutParams,
        private val sizePx: Int
    ) : View(context) {

        private var initialX: Int = 0
        private var initialY: Int = 0
        private var initialTouchX: Float = 0f
        private var initialTouchY: Float = 0f
        private var isPillClick: Boolean = false

        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#332979FF") // Beautiful translucent modern light blue overlay
            style = Paint.Style.FILL
        }

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF2979FF") // Solid energetic neon accent blue stroke
            style = Paint.Style.STROKE
            strokeWidth = 2 * resources.displayMetrics.density
        }

        private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * resources.displayMetrics.density
        }

        private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 9 * resources.displayMetrics.density
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC10121A") // Slate dark round background label
            style = Paint.Style.FILL
        }

        private val activePillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF00E676") // Neon green for active status
            style = Paint.Style.FILL
        }

        private val pausedPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFF3D00") // Neon red for paused status
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            val radius = sizePx / 2f
            
            // 1. Draw outer target circle
            canvas.drawCircle(radius, radius, radius - (4 * density), backgroundPaint)
            canvas.drawCircle(radius, radius, radius - (4 * density), borderPaint)
            
            // 2. Draw crosshairs
            val margin = 10 * density
            canvas.drawLine(margin, radius, sizePx - margin, radius, crosshairPaint)
            canvas.drawLine(radius, margin, radius, sizePx - margin, crosshairPaint)
            
            // 3. Draw high-contrast solid white center confirmation mark
            canvas.drawCircle(radius, radius, 4 * density, centerDotPaint)

            // 4. Draw drag text label beneath the clickable crosshair boundary
            val textRectHeight = 16 * density
            val textTop = sizePx + (2 * density)
            canvas.drawRoundRect(
                2 * density,
                textTop,
                sizePx.toFloat() - (2 * density),
                textTop + textRectHeight,
                4 * density,
                4 * density,
                textBgPaint
            )
            canvas.drawText(
                "DRAG TARGET",
                radius,
                textTop + (11 * density),
                textPaint
            )

            // 5. Draw interactive Play/Pause clicker button pill at the very bottom
            val pillTop = sizePx + (22 * density)
            val pillBottom = sizePx + (42 * density)
            val isActive = overlayClickerActive.value
            
            canvas.drawRoundRect(
                2 * density,
                pillTop,
                sizePx.toFloat() - (2 * density),
                pillBottom,
                4 * density,
                4 * density,
                if (isActive) activePillPaint else pausedPillPaint
            )
            
            canvas.drawText(
                if (isActive) "BOT ON ▶" else "BOT OFF ⏸",
                radius,
                pillTop + (13 * density),
                textPaint
            )
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val density = resources.displayMetrics.density
            val pillTop = sizePx + (22 * density)
            val pillBottom = sizePx + (42 * density)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Check if click occurred inside the BOT STATUS pill area
                    if (event.x >= 2 * density && event.x <= sizePx - (2 * density) &&
                        event.y >= pillTop && event.y <= pillBottom) {
                        isPillClick = true
                    } else {
                        isPillClick = false
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isPillClick) {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        
                        // Boundary checks matching display specs
                        val metrics = resources.displayMetrics
                        layoutParams.x = layoutParams.x.coerceIn(0, metrics.widthPixels - layoutParams.width)
                        layoutParams.y = layoutParams.y.coerceIn(0, metrics.heightPixels - layoutParams.height)
                        
                        wm.updateViewLayout(this, layoutParams)
                        
                        // Update exact current target click dimensions to static references
                        val radius = sizePx / 2f
                        reticleX = layoutParams.x + radius
                        reticleY = layoutParams.y + radius
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (isPillClick) {
                        // Check if release is still roughly within the pill area to prevent accidental toggles during drag
                        if (event.x >= 2 * density && event.x <= sizePx - (2 * density) &&
                            event.y >= pillTop && event.y <= pillBottom) {
                            val nextVal = !overlayClickerActive.value
                            overlayClickerActive.value = nextVal
                            invalidate() // Instantly redraw to reflect interactive visual color updates
                        }
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
