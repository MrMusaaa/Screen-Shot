package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.GeminiBlue
import com.example.ui.theme.GeminiCyan
import com.example.ui.theme.GeminiGreen
import com.example.ui.theme.GeminiPurple
import com.example.ui.theme.GeminiRed
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val captureState by CaptureStateManager.state.collectAsState()

    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var includeAudio by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Kontrol Paneli, 1: Galeri, 2: Mimari

    var recentCaptures by remember { mutableStateOf<List<CapturedMediaItem>>(emptyList()) }
    var previewItem by remember { mutableStateOf<CapturedMediaItem?>(null) }

    // MediaProjection Activity Result Launcher
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            CaptureStateManager.setProjectionPermission(result.resultCode, result.data)
            ScreenCaptureService.startService(context, result.resultCode, result.data!!, includeAudio)
            Toast.makeText(context, "Ekran yakalama servisi başlatıldı!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Ekran yakalama izni verilmedi.", Toast.LENGTH_SHORT).show()
        }
    }

    // Overlay İzin İsteme Launcher
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasOverlayPermission = Settings.canDrawOverlays(context)
    }

    // Bildirim İzni İsteme Launcher
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
    }

    // Mikrofon İzni İsteme Launcher
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasAudioPermission = isGranted
        if (isGranted) includeAudio = true
    }

    // Galeri yenileme
    val refreshGallery: () -> Unit = {
        scope.launch {
            recentCaptures = CapturedMediaRepository.loadRecentCaptures(context)
        }
    }

    LaunchedEffect(captureState.lastCapturedImageUri, captureState.lastCapturedVideoUri) {
        refreshGallery()
    }

    LaunchedEffect(Unit) {
        refreshGallery()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(GeminiBlue, GeminiPurple, GeminiCyan)
                                    )
                                )
                                .padding(2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(DarkSurface),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = "Logo",
                                    tint = GeminiCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Screen Overlay Recorder",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = if (captureState.isServiceRunning) "● Canlı Servis Aktif" else "○ Servis Beklemede",
                                fontSize = 11.sp,
                                color = if (captureState.isServiceRunning) GeminiGreen else TextSecondary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkSurface
                )
            )
        },
        containerColor = DarkBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Sekmeler
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = DarkSurface,
                contentColor = GeminiCyan
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Kontroller") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        refreshGallery()
                    },
                    text = { Text("Galeri (${recentCaptures.size})") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Mimari") }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("APK İndir 🚀") }
                )
            }

            when (selectedTab) {
                0 -> ControlPanelTab(
                    context = context,
                    hasOverlayPermission = hasOverlayPermission,
                    hasNotificationPermission = hasNotificationPermission,
                    hasAudioPermission = hasAudioPermission,
                    includeAudio = includeAudio,
                    onToggleAudio = { enabled ->
                        if (enabled && !hasAudioPermission) {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            includeAudio = enabled
                        }
                    },
                    onRequestOverlay = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        overlayPermissionLauncher.launch(intent)
                    },
                    onRequestMediaProjection = {
                        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
                    },
                    onRequestNotification = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onToggleFloatingOverlay = {
                        if (captureState.isOverlayActive) {
                            FloatingOverlayService.stopService(context)
                        } else {
                            FloatingOverlayService.startService(context)
                        }
                    },
                    onStopService = {
                        ScreenCaptureService.stopService(context)
                        FloatingOverlayService.stopService(context)
                    }
                )
                1 -> GalleryTab(
                    items = recentCaptures,
                    onRefresh = refreshGallery,
                    onItemClick = { previewItem = it },
                    onDelete = { item ->
                        scope.launch {
                            val deleted = CapturedMediaRepository.deleteItem(context, item)
                            if (deleted) {
                                refreshGallery()
                                Toast.makeText(context, "Medya silindi.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onShare = { item ->
                        shareMediaUri(context, item.uri, item.isVideo)
                    }
                )
                2 -> ArchitectureTab()
                3 -> ApkDownloadTab(context = context)
            }
        }
    }

    // Medya Önizleme Modalı
    previewItem?.let { item ->
        MediaPreviewDialog(
            item = item,
            onDismiss = { previewItem = null },
            onShare = { shareMediaUri(context, item.uri, item.isVideo) }
        )
    }
}

@Composable
fun ControlPanelTab(
    context: Context,
    hasOverlayPermission: Boolean,
    hasNotificationPermission: Boolean,
    hasAudioPermission: Boolean,
    includeAudio: Boolean,
    onToggleAudio: (Boolean) -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestMediaProjection: () -> Unit,
    onRequestNotification: () -> Unit,
    onToggleFloatingOverlay: () -> Unit,
    onStopService: () -> Unit
) {
    val captureState by CaptureStateManager.state.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. İzinler Kartı
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Gerekli İzinler & Yapılandırma",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Overlay İzni
                    PermissionItem(
                        icon = Icons.Default.Layers,
                        title = "Yüzen Simge (Overlay)",
                        description = "Ekranın üzerinde görünme izni",
                        isGranted = hasOverlayPermission,
                        onRequest = onRequestOverlay,
                        testTag = "overlay_permission_button"
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // MediaProjection İzni
                    PermissionItem(
                        icon = Icons.Default.ScreenShare,
                        title = "Ekran Paylaşımı (MediaProjection)",
                        description = "VirtualDisplay ve ImageReader erişimi",
                        isGranted = captureState.isProjectionReady,
                        onRequest = onRequestMediaProjection,
                        testTag = "projection_permission_button"
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Spacer(modifier = Modifier.height(8.dp))
                        PermissionItem(
                            icon = Icons.Default.Notifications,
                            title = "Bildirim İzni",
                            description = "Foreground servisi arka planda tutmak için",
                            isGranted = hasNotificationPermission,
                            onRequest = onRequestNotification,
                            testTag = "notification_permission_button"
                        )
                    }
                }
            }
        }

        // 2. Yüzen Simge ve Servis Kontrolü
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Gemini Asistan Bandı & Yüzen Simge",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Gemini asistanı gibi ekranın altında duran ışıltılı bant. Ekran görüntüsü alırken butonlar otomatik gizlenir, sadece şık bant görünür ve çekilen fotoğraf hemen bantta önizlenir.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = onToggleFloatingOverlay,
                        enabled = hasOverlayPermission,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (captureState.isOverlayActive) GeminiRed else GeminiCyan,
                            contentColor = if (captureState.isOverlayActive) Color.White else Color.Black
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("toggle_floating_overlay_button")
                    ) {
                        Icon(
                            imageVector = if (captureState.isOverlayActive) Icons.Default.Close else Icons.Default.Layers,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (captureState.isOverlayActive) "Gemini Bandını Gizle" else "Gemini Asistan Bandını Aç",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (!hasOverlayPermission) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "⚠ Yüzen simgeyi açmak için yukarıdan Overlay İzni vermelisiniz.",
                            fontSize = 11.sp,
                            color = GeminiRed
                        )
                    }
                }
            }
        }

        // 3. Hızlı Eylemler (Uygulama İçi Test)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Hızlı Eylemler",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Ses Kaydı Ayarı
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = if (includeAudio) GeminiCyan else TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(text = "Mikrofon Sesi", color = TextPrimary, fontSize = 14.sp)
                                Text(
                                    text = if (includeAudio) "Video kaydına mikrofon sesi dahil edilir" else "Sessiz video kaydı",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                        Switch(
                            checked = includeAudio,
                            onCheckedChange = onToggleAudio,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = GeminiCyan
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Anlık Screenshot
                    Button(
                        onClick = { CaptureStateManager.triggerScreenshot() },
                        enabled = captureState.isProjectionReady && captureState.isServiceRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = GeminiBlue),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("instant_screenshot_button")
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Anlık Ekran Görüntüsü Al (ImageReader)")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Video Kayıt Butonu
                    Button(
                        onClick = { CaptureStateManager.triggerToggleRecording() },
                        enabled = captureState.isProjectionReady && captureState.isServiceRunning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (captureState.isRecording) GeminiRed else GeminiPurple
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("toggle_recording_button")
                    ) {
                        Icon(
                            imageVector = if (captureState.isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (captureState.isRecording) {
                                val min = captureState.recordingDurationSec / 60
                                val sec = captureState.recordingDurationSec % 60
                                "Kaydı Durdur (${String.format("%02d:%02d", min, sec)})"
                            } else {
                                "Ekran Videosu Kaydı Başlat"
                            }
                        )
                    }

                    if (captureState.isServiceRunning) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onStopService,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = GeminiRed),
                            border = androidx.compose.foundation.BorderStroke(1.dp, GeminiRed),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Servisi Tamamen Durdur")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionItem(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    onRequest: () -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurfaceVariant)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isGranted) GeminiGreen else TextSecondary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        if (isGranted) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Verildi",
                tint = GeminiGreen,
                modifier = Modifier.size(24.dp)
            )
        } else {
            Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(containerColor = GeminiCyan),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag(testTag)
            ) {
                Text(
                    text = "İzin Ver",
                    color = Color.Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun GalleryTab(
    items: List<CapturedMediaItem>,
    onRefresh: () -> Unit,
    onItemClick: (CapturedMediaItem) -> Unit,
    onDelete: (CapturedMediaItem) -> Unit,
    onShare: (CapturedMediaItem) -> Unit
) {
    if (items.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Henüz yakalanan bir görüntü veya video yok.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Yüzen simgeden veya kontrol panelinden ekran görüntüsü alın.",
                    color = TextSecondary.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${items.size} Yakalanan Dosya",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Yenile", tint = GeminiCyan)
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(items, key = { it.id }) { item ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onItemClick(item) }
                    ) {
                        Column {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .background(Color.Black)
                            ) {
                                AsyncImage(
                                    model = item.uri,
                                    contentDescription = item.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )

                                // Video Rozeti
                                if (item.isVideo) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(Color.Black.copy(alpha = 0.6f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Video Oynat",
                                            tint = Color.White,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }

                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = TextPrimary,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
                                            .format(Date(item.dateAdded * 1000)),
                                        fontSize = 10.sp,
                                        color = TextSecondary
                                    )

                                    Row {
                                        IconButton(
                                            onClick = { onShare(item) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Share,
                                                contentDescription = "Paylaş",
                                                tint = GeminiCyan,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(4.dp))
                                        IconButton(
                                            onClick = { onDelete(item) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Sil",
                                                tint = GeminiRed,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ArchitectureTab() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Doğrudan Bellek Katmanı Mimarisi",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = GeminiCyan
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Bu uygulama klasik 'Activity.ScreenCaptureCallback' veya sistem ekran görüntüsü API'lerini tetiklemez. Doğrudan SurfaceFlinger grafik tamponunu okur:",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }

        item {
            ArchStepItem(
                step = "1",
                title = "VirtualDisplay (DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR)",
                desc = "MediaProjection oturumu aracılığıyla fiziksel ekranın SurfaceFlinger çıkışını aynalayan bir sanal ekran oluşturulur."
            )
        }

        item {
            ArchStepItem(
                step = "2",
                title = "ImageReader & PixelFormat.RGBA_8888",
                desc = "VirtualDisplay'in hedef Surface'ı ImageReader'a bağlanır. En güncel kare acquireLatestImage() ile bellekten alınır."
            )
        }

        item {
            ArchStepItem(
                step = "3",
                title = "RowStride & PixelStride Hizalaması (FrameCaptureHelper)",
                desc = "Grafik sürücüsü bellek hizalama padding'ini (rowStride - pixelStride * width) otomatik olarak kırparak saf Bitmap oluşturur."
            )
        }

        item {
            ArchStepItem(
                step = "4",
                title = "MediaRecorder Surface Geçişi",
                desc = "Video kaydında VirtualDisplay surface'ı anında MediaRecorder'a yönlendirilir ve H264 MP4 olarak doğrudan depolanır."
            )
        }
    }
}

@Composable
fun ArchStepItem(step: String, title: String, desc: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurface)
            .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(GeminiPurple),
            contentAlignment = Alignment.Center
        ) {
            Text(text = step, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = desc, color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
fun MediaPreviewDialog(
    item: CapturedMediaItem,
    onDismiss: () -> Unit,
    onShare: () -> Unit
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DarkSurface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        maxLines = 1
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Kapat", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(9f / 16f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = item.uri,
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                    if (item.isVideo) {
                        IconButton(
                            onClick = {
                                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(item.uri, "video/mp4")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(viewIntent, "Videoyu Oynat"))
                            },
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.7f))
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = GeminiCyan, modifier = Modifier.size(32.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onShare,
                        colors = ButtonDefaults.buttonColors(containerColor = GeminiCyan)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Paylaş", color = Color.Black)
                    }
                }
            }
        }
    }
}

private fun shareMediaUri(context: Context, uri: Uri, isVideo: Boolean) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = if (isVideo) "video/mp4" else "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Medya Paylaş"))
}

@Composable
fun ApkDownloadTab(context: Context) {
    val prefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    var githubRepo by remember { mutableStateOf(prefs.getString("github_repo", "") ?: "") }
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    val cleanRepo = githubRepo.trim()
        .removePrefix("https://github.com/")
        .removePrefix("http://github.com/")
        .removeSuffix(".git")
        .trim('/')

    val hasRepo = cleanRepo.isNotBlank() && cleanRepo.contains("/")
    val rawRepoApkUrl = if (hasRepo) "https://github.com/$cleanRepo/raw/main/ScreenCaptureOverlay.apk" else "https://github.com"
    val releasesUrl = if (hasRepo) "https://github.com/$cleanRepo/releases" else "https://github.com"
    val directApkUrl = if (hasRepo) "https://github.com/$cleanRepo/releases/latest/download/ScreenCaptureOverlay-release.apk" else "https://github.com"
    val actionsUrl = if (hasRepo) "https://github.com/$cleanRepo/actions" else "https://github.com"

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "GitHub Otomatik APK İndirme",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = GeminiCyan
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Deponuzda hazır yapılandırılmış GitHub Actions CI/CD (.github/workflows/build-apk.yml) bulunmaktadır. Kodu GitHub'a push ettiğinizde otomatik olarak APK derlenip Releases kısmına yüklenir.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "GitHub Deponuz (kullanici/repo-adi):",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = githubRepo,
                        onValueChange = {
                            githubRepo = it
                            prefs.edit().putString("github_repo", it).apply()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("ornek: kullanici-adiniz/depo-adiniz", color = TextSecondary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GeminiCyan,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true
                    )

                    if (!hasRepo && githubRepo.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Lütfen 'kullanici/depo-adi' formatında giriniz.",
                            color = GeminiRed,
                            fontSize = 11.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 0. Prebuilt Raw APK from Repo (Immediate)
                    Button(
                        onClick = {
                            if (hasRepo) {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(rawRepoApkUrl))
                                context.startActivity(intent)
                            } else {
                                Toast.makeText(context, "Lütfen önce yukarıya GitHub deponuzu girin.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GeminiGreen),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("⚡ Hazır APK'yı İndir (Doğrudan Repodan)", color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 1. Direct Release Link
                    Button(
                        onClick = {
                            if (hasRepo) {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(releasesUrl))
                                context.startActivity(intent)
                            } else {
                                Toast.makeText(context, "Lütfen önce yukarıya GitHub deponuzu girin.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GeminiCyan),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🚀 Releases Sayfasına Git", color = Color.Black, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 2. Direct APK Download link
                    OutlinedButton(
                        onClick = {
                            if (hasRepo) {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(directApkUrl))
                                context.startActivity(intent)
                            } else {
                                Toast.makeText(context, "Lütfen önce yukarıya GitHub deponuzu girin.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GeminiCyan),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GeminiCyan),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("⚡ Doğrudan APK Dosyasını İndir (.apk)")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 3. Actions Live Workflow Link
                    Button(
                        onClick = {
                            if (hasRepo) {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(actionsUrl))
                                context.startActivity(intent)
                            } else {
                                Toast.makeText(context, "Lütfen önce yukarıya GitHub deponuzu girin.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GeminiPurple),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("⚙️ GitHub Actions Derleme Takibi", color = Color.White)
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "GitHub'a Yükleme ve Kurulum Adımları",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    StepCard(
                        number = "1",
                        title = "Projeyi GitHub'a Gönderin",
                        content = "git add .\ngit commit -m \"Add Screen Capture Overlay with CI/CD\"\ngit push origin main"
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    StepCard(
                        number = "2",
                        title = "GitHub Actions Otomatik Başlar",
                        content = "Deponuzdaki .github/workflows/build-apk.yml dosyası otomatik olarak tetiklenir ve bulutta Android ortamında APK'yı derler."
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    StepCard(
                        number = "3",
                        title = "APK'yı İndirin ve Yükleyin",
                        content = "GitHub deponuzun 'Releases' sekmesine gidin veya yukarıdaki 'En Son APK'yı İndir' butonuna basın. ScreenCaptureOverlay-debug.apk dosyasını indirip telefonunuza kurun!"
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = {
                            val clip = ClipData.newPlainText("Git Commands", "git add .\ngit commit -m \"Add Screen Capture Overlay with CI/CD\"\ngit push origin main")
                            clipboardManager.setPrimaryClip(clip)
                            Toast.makeText(context, "Git komutları panoya kopyalandı!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📋 Git Komutlarını Panoya Kopyala")
                    }
                }
            }
        }

        // 3. 404 Çözüm Kartı
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, GeminiRed.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "🚨 404 Hatası veya Workflow Çalışmıyorsa Çözümü:",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = GeminiRed
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "GitHub Releases sayfası henüz en az 1 kez APK derlenip yayımlanmadığında '404 Not Found' verir. Çözmek için:",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "1. GitHub'da deponuzun 'Actions' sekmesine gidin. Eğer yeşil bir buton ('I understand my workflows...') çıkarsa tıklayıp onaylayın.\n\n" +
                               "2. Depo Ayarlarından: Settings > Actions > General > Workflow permissions bölümüne gidin ve 'Read and write permissions' seçip Save yapın.\n\n" +
                               "3. Actions sekmesinden sol menüdeki 'Build & Release Android APK' iş akışını seçip sağdaki 'Run workflow' butonuna basarak derlemeyi hemen başlatın!",
                        fontSize = 12.sp,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
fun StepCard(number: String, title: String, content: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurfaceVariant)
            .padding(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(GeminiBlue),
            contentAlignment = Alignment.Center
        ) {
            Text(text = number, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = content, color = TextSecondary, fontSize = 12.sp)
        }
    }
}
