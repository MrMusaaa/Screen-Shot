package com.example

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
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
 * Tam olarak Android üzerindeki Gemini Asistanı gibi çalışan yüzen ekran yakalama aracı.
 * Ekranın altında şık, ışıltılı bir Gemini Bandı (Assistant Bar) ve kenara yaslanabilen
 * yüzen bir Gemini simgesi sunar.
 * 
 * Ekran görüntüsü alındığında butonlar gizlenir ve sadece ışıltılı Gemini bandı görünür;
 * ardından yakalanan ekran görüntüsü bant üzerinde önizleme ve paylaşım seçenekleriyle belirir.
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

    // 1. Ana Gemini Asistan Bandı (Alt Bant)
    private var assistantBandLayout: FrameLayout? = null
    private lateinit var bandParams: WindowManager.LayoutParams

    // 2. Yüzen Küçük Simge (Bant simge durumuna küçüldüğünde)
    private var floatingOrbLayout: FrameLayout? = null
    private lateinit var orbParams: WindowManager.LayoutParams

    // Bant içi dinamik görünümler
    private var bandButtonsContainer: LinearLayout? = null
    private var bandResultContainer: LinearLayout? = null
    private var bandRecordingContainer: LinearLayout? = null
    private var resultThumbnail: ImageView? = null
    private var recordingTimerText: TextView? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var screenWidth = 1080
    private var screenHeight = 1920
    private var isBandVisible = true

    // Sürükleme değişkenleri (Orb için)
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "SYSTEM_ALERT_WINDOW izni bulunamadı.")
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getMetrics(dm)
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels

        createGeminiAssistantBand()
        createFloatingOrb()

        // State dinleyici
        CaptureStateManager.state.onEach { state ->
            handleStateUpdate(state)
        }.launchIn(serviceScope)

        CaptureStateManager.events.onEach { event ->
            when (event) {
                is CaptureEvent.ShowToast -> {
                    Toast.makeText(applicationContext, event.message, Toast.LENGTH_SHORT).show()
                }
                is CaptureEvent.CaptureResult -> {
                    mainHandler.removeCallbacks(safetyButtonRestorer)
                    if (event.uri != null) {
                        showResultBanner(event.uri)
                    } else {
                        resetBandToIdle()
                    }
                }
                is CaptureEvent.VideoResult -> {
                    mainHandler.removeCallbacks(safetyButtonRestorer)
                    resetBandToIdle()
                }
                else -> {}
            }
        }.launchIn(serviceScope)

        CaptureStateManager.updateOverlayActive(true)
    }

    private val safetyButtonRestorer = Runnable {
        resetBandToIdle()
    }

    private val resultDismissRunnable = Runnable {
        resetBandToIdle()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    /**
     * Gemini Asistanı Alt Bandını Oluşturur (Gemini Bant)
     */
    private fun createGeminiAssistantBand() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bandParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dpToPx(24) // Ekranın altından biraz yukarıda yüzer
        }

        assistantBandLayout = FrameLayout(this).apply {
            setPadding(dpToPx(16), 0, dpToPx(16), 0)
            clipChildren = false
            clipToPadding = false
        }

        // Dış Işıltılı Gradyan Bant Çerçevesi
        val cardContainer = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.parseColor("#4285F4"), // Gemini Blue
                    Color.parseColor("#9B51E0"), // Purple
                    Color.parseColor("#00E5FF"), // Cyan
                    Color.parseColor("#FF4081")  // Pink
                )
            ).apply {
                cornerRadius = dpToPx(28).toFloat()
            }
            setPadding(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2)) // 2dp ışıltılı kenar
            elevation = dpToPx(12).toFloat()
        }

        // İç Koyu Frosted Gövde
        val innerContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(26).toFloat()
                setColor(Color.parseColor("#F012141C")) // %94 koyu cam efekti
            }
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
        }

        // 1. Sol Kısım: Gemini Yıldızı / Işıltı Simgesi
        val geminiIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.btn_star_big_on)
            setColorFilter(Color.parseColor("#00E5FF"))
            layoutParams = LinearLayout.LayoutParams(dpToPx(26), dpToPx(26)).apply {
                marginEnd = dpToPx(10)
            }
        }
        innerContent.addView(geminiIcon)

        // 2. Normal Durum: Butonlar Konteynırı
        bandButtonsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            // 📸 Ekran Görüntüsü Al Butonu (Gemini Pill)
            addView(createPillButton("📸 Ekranı Çek", Color.parseColor("#4285F4")) {
                handleAssistantScreenshot()
            })

            // 🎥 Video Kaydı Butonu
            addView(createPillButton("🎥 Video", Color.parseColor("#9B51E0")) {
                CaptureStateManager.triggerToggleRecording()
            })
        }
        innerContent.addView(bandButtonsContainer)

        // 3. Kayıt Durumu Konteynırı (Video kaydı esnasında)
        bandRecordingContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            val redDot = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#FF5252"))
                }
                layoutParams = LinearLayout.LayoutParams(dpToPx(12), dpToPx(12)).apply {
                    marginEnd = dpToPx(8)
                }
            }
            addView(redDot)

            recordingTimerText = TextView(context).apply {
                text = "Kayıt: 00:00"
                textSize = 13f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(recordingTimerText)

            addView(createPillButton("⏹ Durdur", Color.parseColor("#FF5252")) {
                CaptureStateManager.triggerToggleRecording()
            })
        }
        innerContent.addView(bandRecordingContainer)

        // 4. Sonuç Durumu Konteynırı (SS alındıktan sonra beliren önizleme bandı)
        bandResultContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            resultThumbnail = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36)).apply {
                    marginEnd = dpToPx(8)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(8).toFloat()
                    setColor(Color.parseColor("#262A34"))
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setOnClickListener {
                    val uri = CaptureStateManager.state.value.lastCapturedImageUri
                    if (uri != null) {
                        try {
                            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "image/jpeg")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(viewIntent)
                        } catch (e: Exception) {
                            startActivity(Intent(this@FloatingOverlayService, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            })
                        }
                    }
                }
            }
            addView(resultThumbnail)

            val statusText = TextView(context).apply {
                text = "Kaydedildi"
                textSize = 12f
                setTextColor(Color.parseColor("#00E5FF"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(statusText)

            addView(createPillButton("Paylaş", Color.parseColor("#4285F4")) {
                val uri = CaptureStateManager.state.value.lastCapturedImageUri
                if (uri != null) {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Ekran Görüntüsünü Paylaş").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            })

            addView(createPillButton("Galeriye Kaydet", Color.parseColor("#00E5FF")) {
                val uri = CaptureStateManager.state.value.lastCapturedImageUri
                if (uri != null) {
                    try {
                        val capturesDir = java.io.File(filesDir, "captures")
                        val lastFile = capturesDir.listFiles()
                            ?.filter { it.isFile && (it.name.endsWith(".jpg") || it.name.endsWith(".mp4")) }
                            ?.maxByOrNull { it.lastModified() }
                        if (lastFile != null && lastFile.exists()) {
                            val savedUri = FrameCaptureHelper.exportToPublicGallery(this@FloatingOverlayService, lastFile)
                            if (savedUri != null) {
                                Toast.makeText(this@FloatingOverlayService, "✓ Genel Galeriye (Fotoğraflar) aktarıldı!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@FloatingOverlayService, "Galeriye aktarılamadı.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@FloatingOverlayService, "Hata: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            })

            addView(createPillButton("Uygulama", Color.parseColor("#34A853")) {
                startActivity(Intent(this@FloatingOverlayService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
            })

            addView(createPillButton("✕", Color.parseColor("#757575")) {
                resetBandToIdle()
            })
        }
        innerContent.addView(bandResultContainer)

        // 5. Sağ Kısım: Küçültme (Minimize) Butonu
        val minimizeBtn = TextView(this).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.parseColor("#8E99A8"))
            setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
            setOnClickListener {
                switchViewMode(showBand = false)
            }
        }
        innerContent.addView(minimizeBtn)

        cardContainer.addView(innerContent)
        assistantBandLayout?.addView(cardContainer)

        windowManager?.addView(assistantBandLayout, bandParams)
    }

    private fun createPillButton(text: String, accentColor: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dpToPx(6)
            }
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(16).toFloat()
                setColor(Color.parseColor("#262A34"))
                setStroke(dpToPx(1), accentColor)
            }
            setOnClickListener { onClick() }
        }
    }

    /**
     * Yüzen Küçük Simge (Bant küçüldüğünde ekranda durur)
     */
    private fun createFloatingOrb() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val orbSize = dpToPx(56)

        orbParams = WindowManager.LayoutParams(
            orbSize,
            orbSize,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - dpToPx(68)
            y = screenHeight / 2
        }

        floatingOrbLayout = FrameLayout(this).apply {
            visibility = View.GONE

            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#4285F4"), Color.parseColor("#9B51E0"), Color.parseColor("#00E5FF"))
            ).apply { shape = GradientDrawable.OVAL }
            setPadding(dpToPx(3), dpToPx(3), dpToPx(3), dpToPx(3))

            val inner = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#12141C"))
                }
            }

            val icon = ImageView(context).apply {
                setImageResource(android.R.drawable.btn_star_big_on)
                setColorFilter(Color.parseColor("#00E5FF"))
                layoutParams = FrameLayout.LayoutParams(dpToPx(24), dpToPx(24), Gravity.CENTER)
            }
            inner.addView(icon)
            addView(inner)
        }

        windowManager?.addView(floatingOrbLayout, orbParams)
        setupOrbTouchListener()
    }

    private fun setupOrbTouchListener() {
        floatingOrbLayout?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = orbParams.x
                    initialY = orbParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > 16f || Math.abs(dy) > 16f) {
                        isDragging = true
                        orbParams.x = (initialX + dx).toInt()
                        orbParams.y = (initialY + dy).toInt()
                        windowManager?.updateViewLayout(floatingOrbLayout, orbParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Tıklama -> Gemini Bandını Geri Aç
                        switchViewMode(showBand = true)
                    } else {
                        // Kenara yapış
                        val currentX = orbParams.x
                        val targetX = if (currentX + dpToPx(28) > screenWidth / 2) screenWidth - dpToPx(68) else dpToPx(12)
                        val animator = ValueAnimator.ofInt(currentX, targetX).apply {
                            duration = 200
                            addUpdateListener {
                                orbParams.x = it.animatedValue as Int
                                windowManager?.updateViewLayout(floatingOrbLayout, orbParams)
                            }
                        }
                        animator.start()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun switchViewMode(showBand: Boolean) {
        isBandVisible = showBand
        assistantBandLayout?.visibility = if (showBand) View.VISIBLE else View.GONE
        floatingOrbLayout?.visibility = if (!showBand) View.VISIBLE else View.GONE
    }

    /**
     * Gemini Asistanı Gibi Ekran Görüntüsü Alma Akışı:
     * 1. Butonlar gizlenir (kullanıcının istediği gibi fotoğrafta butonlar çıkmaz, sadece zarif bant kalır).
     * 2. SurfaceFlinger'dan temiz kare yakalanır.
     * 3. Yakalanan kare hemen bant üzerinde önizleme olarak belirir.
     */
    private fun handleAssistantScreenshot() {
        if (!CaptureStateManager.state.value.isServiceRunning || !CaptureStateManager.state.value.isProjectionReady) {
            Toast.makeText(this, "Lütfen önce ana uygulamadan Ekran İzni verin!", Toast.LENGTH_LONG).show()
            val appIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(appIntent)
            resetBandToIdle()
            return
        }

        // Butonları derhal gizle (fotoğrafta sadece şık bant gözüksün, SS butonu asla çıkmasın)
        bandButtonsContainer?.visibility = View.INVISIBLE

        // EMNİYET ZAMANLAYICISI: 2.5 saniye sonra butonlar her koşulda geri gelir, asla kaybolmaz!
        mainHandler.removeCallbacks(safetyButtonRestorer)
        mainHandler.postDelayed(safetyButtonRestorer, 2500)

        mainHandler.postDelayed({
            // Ekran görüntüsü servisini tetikle
            CaptureStateManager.triggerScreenshot()
        }, 120)
    }

    private fun handleStateUpdate(state: CaptureState) {
        mainHandler.post {
            // Video kaydı durumu
            if (state.isRecording) {
                bandButtonsContainer?.visibility = View.GONE
                bandResultContainer?.visibility = View.GONE
                bandRecordingContainer?.visibility = View.VISIBLE
                val m = state.recordingDurationSec / 60
                val s = state.recordingDurationSec % 60
                recordingTimerText?.text = String.format("Kayıt: %02d:%02d", m, s)
            } else {
                bandRecordingContainer?.visibility = View.GONE
                if (bandResultContainer?.visibility != View.VISIBLE) {
                    bandButtonsContainer?.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showResultBanner(imageUri: Uri) {
        try {
            bandButtonsContainer?.visibility = View.GONE
            bandRecordingContainer?.visibility = View.GONE
            bandResultContainer?.visibility = View.VISIBLE

            // Küçük önizleme resmini yükle
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, imageUri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.setTargetSampleSize(4)
                }
                resultThumbnail?.setImageBitmap(bitmap)
            } else {
                @Suppress("DEPRECATION")
                val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, imageUri)
                resultThumbnail?.setImageBitmap(bitmap)
            }

            // 6 saniye sonra normal butonlu moda geri dön
            mainHandler.removeCallbacks(resultDismissRunnable)
            mainHandler.postDelayed(resultDismissRunnable, 6000)

        } catch (e: Exception) {
            Log.e(TAG, "Önizleme gösterilemedi: ${e.message}", e)
            resetBandToIdle()
        }
    }

    private fun resetBandToIdle() {
        mainHandler.post {
            bandResultContainer?.visibility = View.GONE
            bandRecordingContainer?.visibility = View.GONE
            bandButtonsContainer?.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        CaptureStateManager.updateOverlayActive(false)
        try {
            if (assistantBandLayout != null) windowManager?.removeView(assistantBandLayout)
            if (floatingOrbLayout != null) windowManager?.removeView(floatingOrbLayout)
        } catch (ignored: Exception) {}
        super.onDestroy()
    }
}
