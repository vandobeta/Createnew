package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    val path = Path()
                    path.moveTo(x, y)
                    val stroke = GestureDescription.StrokeDescription(path, 0, 50)
                    val gestureBuilder = GestureDescription.Builder()
                    gestureBuilder.addStroke(stroke)
                    return service.dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            super.onCompleted(gestureDescription)
                            Log.d("DerivAccessibility", "Click gesture successfully simulated at ($x, $y)")
                        }
                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            super.onCancelled(gestureDescription)
                            Log.e("DerivAccessibility", "Click gesture simulation cancelled at ($x, $y)")
                        }
                    }, null)
                } catch (e: Exception) {
                    Log.e("DerivAccessibility", "Failed to dispatch gesture: ${e.message}")
                }
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
            height = sizePx + (24 * density).toInt()  // extra space for the bottom text label
            x = (resources.displayMetrics.widthPixels - sizePx) / 2
            y = (resources.displayMetrics.heightPixels - (sizePx + (24 * density).toInt())) / 2
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
            val textRectHeight = 18 * density
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
                textTop + (12 * density),
                textPaint
            )
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
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
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
