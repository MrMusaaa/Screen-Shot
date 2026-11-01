package com.example

import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * FloatingOverlayService
 * 
 * Ekranın üzerinde duran, Gemini Live benzeri ışıltılı yüzen simge (Floating Overlay).
 * Sürüklenebilir, ekran kenarlarına yapışabilir ve tıklandığında anlık ekran görüntüsü
 * alma ve video kaydı başlatma/durdurma menüsünü açar.
 */
class FloatingOverlayService : Service() {

    companion object {
        private const val TAG = "FloatingOverlayService"

        fun startService(context: Context) {
            if (Settings.canDrawOverlays(context)) {
                val intent = Intent(context, FloatingOverlayService::class.java)
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java)
            context.stopService(intent)
        }
    }

    private var windowManager: WindowManager? = null
    private var rootOverlayLayout: FrameLayout? = null
    private var floatingOrbView: FrameLayout? = null
    private var expandedMenuView: LinearLayout? = null
    private var recordingBadge: TextView? = null

    private lateinit var windowParams: WindowManager.LayoutParams
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isMenuExpanded = false
    private var screenWidth = 1080
    private var screenHeight = 1920

    // Sürükleme değişkenleri
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private val touchSlop = 16f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "SYSTEM_ALERT_WINDOW izni bulunamadı.")
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        calculateScreenDimensions()
        createFloatingWidget()

        // Dinleyici: State değişikliklerini dinle (Kayıt süresi, bildirimler)
        CaptureStateManager.state.onEach { state ->
            updateRecordingUI(state.isRecording, state.recordingDurationSec)
        }.launchIn(serviceScope)

        CaptureStateManager.events.onEach { event ->
            if (event is CaptureEvent.ShowToast) {
                Toast.makeText(applicationContext, event.message, Toast.LENGTH_SHORT).show()
            }
        }.launchIn(serviceScope)

        CaptureStateManager.updateOverlayActive(true)
    }

    private fun calculateScreenDimensions() {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getMetrics(dm)
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun createFloatingWidget() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - dpToPx(76)
            y = screenHeight / 3
        }

        rootOverlayLayout = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }

        // 1. Ana Yüzen Orb (Gemini Live benzeri)
        floatingOrbView = createGeminiOrbView()

        // 2. Açılır Menü (Quick Actions)
        expandedMenuView = createExpandedMenuView().apply {
            visibility = View.GONE
        }

        // Kapsayıcıya ekle
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            clipChildren = false
            clipToPadding = false
            addView(expandedMenuView)
            addView(floatingOrbView)
        }

        rootOverlayLayout?.addView(container)
        windowManager?.addView(rootOverlayLayout, windowParams)

        setupTouchListener()
    }

    /**
     * Gemini Live tarzı parıldayan dairesel yüzen simge
     */
    private fun createGeminiOrbView(): FrameLayout {
        val orbSize = dpToPx(60)

        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(orbSize, orbSize).apply {
                setMargins(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            }

            // Dış gradyan çerçeve (Gemini Cyan & Purple)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#4285F4"), Color.parseColor("#9B51E0"), Color.parseColor("#34A853"))
            ).apply {
                shape = GradientDrawable.OVAL
            }
            setPadding(dpToPx(3), dpToPx(3), dpToPx(3), dpToPx(3))

            // İç koyu gövde
            val innerView = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#13131A"))
                }
            }

            // Merkez İkon (Kamera / Sparkle)
            val icon = ImageView(context).apply {
                setImageResource(android.R.drawable.ic_menu_camera)
                setColorFilter(Color.WHITE)
                val iconSize = dpToPx(28)
                layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
            }
            innerView.addView(icon)

            // Kayıt rozeti (Kırmızı nokta / Sayaç)
            recordingBadge = TextView(context).apply {
                textSize = 10f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                visibility = View.GONE
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(8).toFloat()
                    setColor(Color.parseColor("#E53935"))
                }
                setPadding(dpToPx(4), dpToPx(1), dpToPx(4), dpToPx(1))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply {
                    bottomMargin = dpToPx(2)
                }
            }
            innerView.addView(recordingBadge)

            addView(innerView)
            elevation = dpToPx(8).toFloat()
        }
    }

    /**
     * Tıklanınca açılan eylem menüsü (Screenshot, Video, Uygulama, Kapat)
     */
    private fun createExpandedMenuView(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }

            // Glassmorphic koyu arka plan
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(18).toFloat()
                setColor(Color.parseColor("#E61A1C24")) // %90 opak koyu gri
                setStroke(dpToPx(1), Color.parseColor("#384252"))
            }
            elevation = dpToPx(12).toFloat()

            // 1. Ekran Görüntüsü Al Butonu
            addView(createActionButton("📸 Ekran Görüntüsü Al", Color.parseColor("#2979FF")) {
                handleTakeScreenshot()
            })

            // 2. Video Kaydı Butonu
            addView(createActionButton("🎥 Kayıt Başlat / Bitir", Color.parseColor("#FF5252")) {
                handleToggleRecording()
            })

            // 3. Ana Uygulamayı Aç Butonu
            addView(createActionButton("📱 Galeri & Ayarlar", Color.parseColor("#00E676")) {
                val appIntent = Intent(this@FloatingOverlayService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(appIntent)
                toggleMenu(false)
            })

            // 4. Kapat Butonu
            addView(createActionButton("✕ Kapat", Color.parseColor("#757575")) {
                toggleMenu(false)
                stopSelf()
            })
        }
    }

    private fun createActionButton(label: String, accentColor: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dpToPx(3), 0, dpToPx(3))
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(12).toFloat()
                setColor(Color.parseColor("#262A34"))
            }

            setOnClickListener {
                onClick()
            }
        }
    }

    private fun setupTouchListener() {
        floatingOrbView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = windowParams.x
                    initialY = windowParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY

                    if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                        isDragging = true
                        windowParams.x = (initialX + dx).toInt()
                        windowParams.y = (initialY + dy).toInt()
                        windowManager?.updateViewLayout(rootOverlayLayout, windowParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Tıklama olayı
                        toggleMenu(!isMenuExpanded)
                    } else {
                        // Sürükleme bitti -> En yakın ekrana yapış (Snap to edge)
                        snapToNearestEdge()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToNearestEdge() {
        val currentX = windowParams.x
        val targetX = if (currentX + dpToPx(30) > screenWidth / 2) {
            screenWidth - dpToPx(76) // Sağ kenar
        } else {
            dpToPx(12) // Sol kenar
        }

        val animator = ValueAnimator.ofInt(currentX, targetX).apply {
            duration = 200
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                windowParams.x = anim.animatedValue as Int
                windowManager?.updateViewLayout(rootOverlayLayout, windowParams)
            }
        }
        animator.start()
    }

    private fun toggleMenu(expand: Boolean) {
        isMenuExpanded = expand
        expandedMenuView?.visibility = if (expand) View.VISIBLE else View.GONE
    }

    private fun handleTakeScreenshot() {
        toggleMenu(false)

        // Ekran görüntüsünde yüzen simgenin çıkmaması için 150ms gizle
        rootOverlayLayout?.alpha = 0f

        mainHandler.postDelayed({
            CaptureStateManager.triggerScreenshot()
            // Ekran görüntüsü tamamlandıktan sonra tekrar görünür yap
            mainHandler.postDelayed({
                rootOverlayLayout?.alpha = 1f
            }, 300)
        }, 150)
    }

    private fun handleToggleRecording() {
        toggleMenu(false)
        CaptureStateManager.triggerToggleRecording()
    }

    private fun updateRecordingUI(isRecording: Boolean, durationSec: Int) {
        mainHandler.post {
            if (isRecording) {
                recordingBadge?.visibility = View.VISIBLE
                val minutes = durationSec / 60
                val seconds = durationSec % 60
                recordingBadge?.text = String.format("%02d:%02d", minutes, seconds)
            } else {
                recordingBadge?.visibility = View.GONE
            }
        }
    }

    override fun onDestroy() {
        CaptureStateManager.updateOverlayActive(false)
        try {
            if (rootOverlayLayout != null) {
                windowManager?.removeView(rootOverlayLayout)
                rootOverlayLayout = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Overlay kaldırılırken hata: ${e.message}", e)
        }
        super.onDestroy()
    }
}
