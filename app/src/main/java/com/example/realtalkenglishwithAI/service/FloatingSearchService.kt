package com.example.realtalkenglishwithAI.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import com.example.realtalkenglishwithAI.R
import com.example.realtalkenglishwithAI.activity.MainActivity

class FloatingSearchService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var params: WindowManager.LayoutParams

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var collapseRunnable: Runnable
    private var isMenuExpanded = true

    private lateinit var searchIcon: ImageView
    private lateinit var dragHandle: ImageView
    private lateinit var appIcon: ImageView

    private var dragView: View? = null
    private lateinit var dragViewParams: WindowManager.LayoutParams
    
    private var dragIconSizePx: Int = 0

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()

        val sizeInDp = 47
        dragIconSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, sizeInDp.toFloat(), resources.displayMetrics
        ).toInt()

        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_search, null)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val screenHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            displayMetrics.heightPixels
        }
        params.gravity = Gravity.TOP or Gravity.START
        params.x = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics).toInt() // Khoảng cách 4dp từ cạnh trái
        params.y = (screenHeight * 0.4).toInt()

        windowManager.addView(floatingView, params)

        val closeButton = floatingView.findViewById<ImageView>(R.id.close_btn)
        searchIcon = floatingView.findViewById(R.id.floating_search_icon)
        dragHandle = floatingView.findViewById(R.id.drag_handle)
        appIcon = floatingView.findViewById(R.id.app_icon)

        closeButton.setOnClickListener { stopSelf() }

        setupSearchIconTouchListener()
        setupDragHandleTouchListener(screenHeight)

        appIcon.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(intent)
            collapseMenu()
        }

        collapseRunnable = Runnable {
            collapseMenu()
            // minimizeApp() // Đã xóa lệnh gọi minimizeApp()
        }
        handler.postDelayed(collapseRunnable, 1000)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragHandleTouchListener(screenHeight: Int) {
        dragHandle.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0.toFloat()
            private var initialTouchY: Float = 0.toFloat()

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        collapseMenu()
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val topLimit = 0
                        val bottomLimit = screenHeight - floatingView.height
                        if (params.y < topLimit) params.y = topLimit
                        if (params.y > bottomLimit) params.y = bottomLimit
                        // Đảm bảo nó quay về cạnh trái với khoảng cách 4dp
                        params.x = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics).toInt()
                        windowManager.updateViewLayout(floatingView, params)
                        return true
                    }
                }
                return false
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSearchIconTouchListener() {
        val longPressHandler = Handler(Looper.getMainLooper())
        var longPressRunnable: Runnable? = null
        var isDragging = false
        var longPressTriggered = false

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        var initialTouchX = 0f
        var initialTouchY = 0f

        searchIcon.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    longPressTriggered = false

                    longPressRunnable = Runnable { longPressTriggered = true }
                    longPressHandler.postDelayed(longPressRunnable!!, 150)

                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isDragging) {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (longPressTriggered || Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                            isDragging = true
                            longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                            floatingView.alpha = 0f
                            showDragView(
                                (event.rawX - dragIconSizePx / 2).toInt(),
                                (event.rawY - dragIconSizePx * 2.0f).toInt()
                            )
                            // minimizeApp() // Đã xóa dòng này
                        }
                    }

                    if (isDragging) {
                        dragViewParams.x = (event.rawX - dragIconSizePx / 2).toInt()
                        dragViewParams.y = (event.rawY - dragIconSizePx * 2.0f).toInt()
                        if (dragView != null) {
                            windowManager.updateViewLayout(dragView, dragViewParams)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }

                    if (isDragging) {
                        removeDragView()
                        floatingView.alpha = 1f
                    } else {
                        toggleMenu()
                    }
                    isDragging = false
                    longPressTriggered = false
                    true
                }
                else -> false
            }
        }
    }

    private fun showDragView(x: Int, y: Int) {
        if (dragView != null) return

        dragView = LayoutInflater.from(this).inflate(R.layout.layout_drag_icon, null)

        dragViewParams = WindowManager.LayoutParams(
            dragIconSizePx,
            dragIconSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        windowManager.addView(dragView, dragViewParams)
    }

    private fun removeDragView() {
        dragView?.let {
            ObjectAnimator.ofFloat(it, "alpha", 1f, 0f).apply {
                duration = 200
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (dragView != null) { windowManager.removeView(it); dragView = null }
                    }
                })
                start()
            }
        }
    }

    private fun toggleMenu() {
        handler.removeCallbacks(collapseRunnable)
        if (isMenuExpanded) {
            collapseMenu()
        } else {
            expandMenu()
        }
    }

    private fun expandMenu() {
        dragHandle.visibility = View.VISIBLE
        appIcon.visibility = View.VISIBLE
        isMenuExpanded = true
    }

    private fun collapseMenu() {
        handler.removeCallbacks(collapseRunnable)
        dragHandle.visibility = View.GONE
        appIcon.visibility = View.GONE
        isMenuExpanded = false
    }

    // Đã xóa hàm minimizeApp() tại đây

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(collapseRunnable)
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
        removeDragView()
    }
}
