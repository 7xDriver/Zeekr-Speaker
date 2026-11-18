package app.zeekrspeaker

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

private val Orange = Color(0xFFFF7A1A)
private val OnOrange = Color(0xFF1B1004)
private val Background = Color(0xFF0F1316)
private val Panel = Color(0xFF1A2026)
private val PanelHigh = Color(0xFF232B33)
private val TextMain = Color(0xFFE8ECEF)
private val TextMuted = Color(0xFF9AA5AF)
private val Good = Color(0xFF34D399)
private val Bad = Color(0xFFEF4444)
private val Pressed = Color(0xFFD62828)

private data class Status(
    val mic: Boolean,
    val overlay: Boolean,
    val speakerReady: Boolean,
    val floating: Boolean,
    val talking: Boolean,
    val lastRun: String
)

private data class AudioChoice(
    val uri: Uri,
    val name: String
)

class MainActivity : ComponentActivity() {

    private var askedOverlay = false
    private var pendingSlot = -1
    private var pendingAudioPicker = false

    private val slots = mutableStateListOf<Prefs.Slot>()
    private val audioFiles = mutableStateListOf<AudioChoice>()

    private var showAudioBrowser by mutableStateOf(false)
    private var updateStatus by mutableStateOf("Ready to check for updates.")
    private var updateBusy by mutableStateOf(false)
    private var pendingUpdate by mutableStateOf<GitHubUpdater.PendingUpdate?>(null)
    private var audioLoading by mutableStateOf(false)

    private var usbVolumes by mutableStateOf<List<UsbStorageVolumes.Volume>>(emptyList())
    private var selectedUsbPath by mutableStateOf("")
    private var usbRecording by mutableStateOf(false)
    private var usbRecorder: UsbWavRecorder? = null
    private var usbRecorderMessage by mutableStateOf("Connect a USB drive, then tap Refresh.")
    private var usbRecordingFileName by mutableStateOf("")
    private var carMusicActive by mutableStateOf(false)
    private var carMusicMessage by mutableStateOf("Tap Start to request permission and route head-unit music to the exterior speaker.")

    private val carMusicPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            carMusicMessage = "Starting car audio capture…"
            CarMusicRouter.start(this, result.resultCode, data) { active, message ->
                runOnUiThread {
                    carMusicActive = active
                    carMusicMessage = message
                }
            }
        } else {
            carMusicMessage = "Audio capture permission was not granted."
        }
    }

    private val askRuntime =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            if (pendingAudioPicker) {
                pendingAudioPicker = false

                if (hasAudioPermission()) {
                    showAudioBrowser()
                } else {
                    toast("Audio permission is required to browse audio files")
                }
            }

            requestOverlayIfNeeded()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VehicleSpeed.start(this)

        for (i in 0 until Prefs.SLOT_COUNT) {
            slots.add(Prefs.slot(this, i))
        }

        selectedUsbPath = getSharedPreferences("usb_recorder", MODE_PRIVATE).getString("selected_path", "") ?: ""
        refreshUsbVolumes()

        val savedVolume = Prefs.volumePercent(this)
        LiveVoiceService.setVolumePercent(savedVolume)
        SoundPlayer.setVolumePercent(savedVolume)
        LiveVoiceService.setMicrophoneGain(Prefs.microphoneGain(this))

        if (savedInstanceState == null && Settings.canDrawOverlays(this)) {
            startService(Intent(this, FloatService::class.java))
        }

        setContent {
            AppScreen()
        }
    }

    private fun refreshUsbVolumes() {
        usbVolumes = UsbStorageVolumes.discover(this)
        if (usbVolumes.none { it.root.absolutePath == selectedUsbPath }) {
            selectedUsbPath = usbVolumes.firstOrNull()?.root?.absolutePath ?: ""
        }
        if (selectedUsbPath.isNotBlank()) {
            getSharedPreferences("usb_recorder", MODE_PRIVATE).edit()
                .putString("selected_path", selectedUsbPath).apply()
        }
        usbRecorderMessage = if (usbVolumes.isEmpty()) {
            "No USB drives detected. Check that the drive is mounted, then refresh."
        } else {
            "${usbVolumes.size} USB drive(s) detected."
        }
    }

    private fun selectUsbVolume(path: String) {
        if (usbRecording) return
        selectedUsbPath = path
        getSharedPreferences("usb_recorder", MODE_PRIVATE).edit()
            .putString("selected_path", path).apply()
    }

    private fun startUsbRecording() {
        if (usbRecordingFileName.isBlank()) {
            usbRecorderMessage = "Enter a file name before recording."
            toast("Enter a file name before recording")
            return
        }
        val volume = usbVolumes.firstOrNull { it.root.absolutePath == selectedUsbPath }
        if (volume == null) {
            toast("Select a connected USB drive first")
            refreshUsbVolumes()
            return
        }
        if (!hasMic()) {
            toast("Microphone permission is required")
            return
        }
        val folder = File(volume.root, "ZEEKR-Speaker/Recordings")
        val mainHandler = Handler(Looper.getMainLooper())
        usbRecorder = UsbWavRecorder(
            this,
            folder,
            usbRecordingFileName,
            onFinished = { filename -> mainHandler.post {
                usbRecording = false
                usbRecorder = null
                usbRecorderMessage = "Saved $filename to ${volume.root.name}/ZEEKR-Speaker/Recordings"
                toast("WAV saved to USB")
            } },
            onError = { message -> mainHandler.post {
                usbRecording = false
                usbRecorder = null
                usbRecorderMessage = message
                toast(message)
            } }
        )
        usbRecording = true
        usbRecorderMessage = "Recording… tap Stop to finish and save the WAV file."
        usbRecorder?.start()
    }

    private fun stopUsbRecording() {
        usbRecorder?.stop()
        usbRecorderMessage = "Finishing recording and saving WAV file…"
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        return ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun readStatus() = Status(
        mic = hasMic(),
        overlay = Settings.canDrawOverlays(this),
        speakerReady = LiveVoiceService.armed,
        floating = FloatService.visible,
        talking = LiveVoiceService.holdActive,
        lastRun = getSharedPreferences("status", MODE_PRIVATE)
            .getString("last_result", "none yet") ?: "none yet"
    )

    private fun requestPermissions() {
        val needed = buildList {
            if (!hasMic()) add(Manifest.permission.RECORD_AUDIO)

            if (
                Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (needed.isEmpty()) {
            requestOverlayIfNeeded()
        } else {
            askRuntime.launch(needed.toTypedArray())
        }
    }

    private fun requestOverlayIfNeeded() {
        if (Settings.canDrawOverlays(this) || askedOverlay) return

        askedOverlay = true

        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: ActivityNotFoundException) {
            toast("This head unit has no overlay settings page")
        }
    }

    private fun setFloatingButton(show: Boolean) {
        if (!show) {
            stopService(Intent(this, FloatService::class.java))
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            toast("Grant the overlay permission first")
            return
        }

        startService(Intent(this, FloatService::class.java))
    }

    private fun refreshFloatingButtonSize() {
        if (!FloatService.visible) return

        startService(
            Intent(this, FloatService::class.java)
                .setAction(FloatService.ACTION_REFRESH_SIZE)
        )
    }

    private fun armEngine() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, LiveVoiceService::class.java)
                    .setAction(LiveVoiceService.ACTION_ARM)
            )
        } catch (e: Exception) {
            toast("Could not start the audio service: ${e.message}")
        }
    }

    private fun startTalking(durationMillis: Long? = null) {
        if (!hasMic()) {
            toast("Grant the microphone permission first")
            return
        }

        if (durationMillis != null) {
            LiveVoiceService.setHeld(true, durationMillis)
        } else {
            LiveVoiceService.setHeld(true)
        }

        if (!LiveVoiceService.armed) armEngine()
    }

    private fun toggleActivate() {
        if (LiveVoiceService.holdActive) {
            LiveVoiceService.setHeld(false)
        } else {
            startTalking(Prefs.offSeconds(this) * 1000L)
        }
    }

    private fun minimizeApp() {
        // Send the MainActivity task to the background without stopping audio services.
        moveTaskToBack(true)
    }

    private fun exitApp() {
        LiveVoiceService.setHeld(false)
        stopService(Intent(this, FloatService::class.java))
        stopService(Intent(this, LiveVoiceService::class.java))
        SoundPlayer.stop()
        finishAndRemoveTask()
    }

    // ---------------------------------------------------------
    // AUDIO VOLUME
    // ---------------------------------------------------------

    private fun changeVolume(percentage: Int) {
        val volume = percentage.coerceIn(0, 100)
        Prefs.setVolumePercent(this, volume)
        LiveVoiceService.setVolumePercent(volume)
        SoundPlayer.setVolumePercent(volume)
    }

    // ---------------------------------------------------------
    // AUDIO FILE BROWSER
    // ---------------------------------------------------------

    private fun chooseFile(slot: Int) {
        pendingSlot = slot

        if (hasAudioPermission()) {
            showAudioBrowser()
            return
        }

        pendingAudioPicker = true

        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        askRuntime.launch(arrayOf(permission))
    }

    private fun showAudioBrowser() {
        audioFiles.clear()
        showAudioBrowser = true
        loadAudioFiles()
    }

    private fun loadAudioFiles() {
        audioLoading = true
        audioFiles.clear()

        try {
            val volumes = if (Build.VERSION.SDK_INT >= 29) {
                MediaStore.getExternalVolumeNames(this).toList()
            } else {
                listOf("external")
            }

            for (volume in volumes) {
                val collection = if (Build.VERSION.SDK_INT >= 29) {
                    MediaStore.Audio.Media.getContentUri(volume)
                } else {
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }

                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME
                )

                contentResolver.query(
                    collection,
                    projection,
                    null,
                    null,
                    "${MediaStore.Audio.Media.DISPLAY_NAME} COLLATE NOCASE ASC"
                )?.use { cursor ->

                    val idColumn = cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Media._ID
                    )

                    val nameColumn = cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Media.DISPLAY_NAME
                    )

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val name = cursor.getString(nameColumn)
                            ?: "Unknown audio file"

                        val uri = android.content.ContentUris.withAppendedId(
                            collection,
                            id
                        )

                        audioFiles.add(AudioChoice(uri, name))
                    }
                }
            }
        } catch (e: Exception) {
            toast("Could not read audio files: ${e.message}")
        }

        audioLoading = false
    }

    private fun selectAudioFile(file: AudioChoice) {
        val slot = pendingSlot
        if (slot !in 0 until Prefs.SLOT_COUNT) return

        val fileLabel = file.name
            .substringBeforeLast('.', file.name)
            .take(26)

        Prefs.setSource(this, slot, "uri:${file.uri}", file.name)
        Prefs.setLabel(this, slot, fileLabel)

        slots[slot] = Prefs.slot(this, slot)

        SoundPlayer.preload(applicationContext)
        showAudioBrowser = false

        toast("${file.name} assigned to ${Prefs.slotName(slot)}")
    }

    private fun resetSlot(slot: Int) {
        Prefs.resetSlot(this, slot)
        slots[slot] = Prefs.slot(this, slot)
        SoundPlayer.clearCache()
        refreshFloatingButtonSize()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------
    // MAIN SCREEN
    // ---------------------------------------------------------

    @Composable
    private fun AppScreen() {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Orange,
                onPrimary = Color.Black,
                background = Background,
                onBackground = TextMain,
                surface = Panel,
                onSurface = TextMain
            )
        ) {
            var status by remember { mutableStateOf(readStatus()) }

            LaunchedEffect(Unit) {
                while (true) {
                    status = readStatus()
                    delay(1000)
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Background
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp, vertical = 20.dp)
                ) {
                    Header()
                    Spacer(Modifier.height(16.dp))
                    SettingsTab(status)
                }
            }

            if (showAudioBrowser) AudioBrowserDialog()
        }
    }

    @Composable
    private fun Header() {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "ZEEKR Speaker",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Orange,
                modifier = Modifier.weight(1f)
            )

            TextButton(onClick = { minimizeApp() }) {
                Text(
                    "Minimise",
                    color = Orange,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            TextButton(onClick = { exitApp() }) {
                Text(
                    "Exit",
                    color = Orange,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    override fun onDestroy() {
        CarMusicRouter.stop()
        super.onDestroy()
    }

    @Composable
    private fun SettingsTab(status: Status) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!status.mic || !status.overlay) PermissionCard()

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    FloatingCard(Modifier.fillMaxWidth(), status)
                    PresetConfigurationCard(Modifier.fillMaxWidth())
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    TalkCard(Modifier.fillMaxWidth(), status)
                    MaximumVehicleSpeedCard(Modifier.fillMaxWidth())
                    // Play Car Music is temporarily hidden in version 0.2.4.
                    // CarMusicCard(Modifier.fillMaxWidth())
                }
            }

            VolumeCard()
            CabinAudioMutingCard()
            SoundButtonsCard()
            UsbWavRecorderCard(status)
            UpdateCard()
            DiagnosticsCard(status)
            Spacer(Modifier.height(16.dp))
        }
    }

    // ---------------------------------------------------------
    // PERMISSIONS
    // ---------------------------------------------------------

    @Composable
    private fun PermissionCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF3A2410)
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Row(
                Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Permissions needed",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )

                    Text(
                        "Allow the microphone and display over other apps so the talk button can work.",
                        fontSize = 15.sp,
                        color = TextMuted
                    )
                }

                Spacer(Modifier.width(16.dp))

                Button(
                    onClick = { requestPermissions() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Orange,
                        contentColor = Color.Black
                    )
                ) {
                    Text("Grant permissions", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // ---------------------------------------------------------
    // VOICE CONTROLS
    // ---------------------------------------------------------

    @Composable
    private fun TalkCard(
        modifier: Modifier,
        status: Status
    ) {
        var mode by remember {
            mutableStateOf(Prefs.mode(this@MainActivity))
        }

        var pressed by remember { mutableStateOf(false) }

        val active = status.talking

        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Text(
                    "Voice Controls",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    "Select a mode, then use the speaker button.",
                    fontSize = 14.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(18.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(PanelHigh)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ModeChip(
                        text = "Push to Talk",
                        selected = mode == Prefs.MODE_TALK,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (active) LiveVoiceService.setHeld(false)
                        mode = Prefs.MODE_TALK
                        Prefs.setMode(this@MainActivity, mode)
                    }

                    ModeChip(
                        text = "Push to Activate",
                        selected = mode == Prefs.MODE_ACTIVATE,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (active) LiveVoiceService.setHeld(false)
                        mode = Prefs.MODE_ACTIVATE
                        Prefs.setMode(this@MainActivity, mode)
                    }
                }

                Spacer(Modifier.height(16.dp))

                val buttonActive = if (mode == Prefs.MODE_TALK) {
                    pressed
                } else {
                    active
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(350.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (buttonActive) Pressed else Orange)
                        .then(
                            if (mode == Prefs.MODE_TALK) {
                                Modifier.pointerInput(mode) {
                                    detectTapGestures(
                                        onPress = {
                                            pressed = true
                                            startTalking()

                                            try {
                                                tryAwaitRelease()
                                            } finally {
                                                pressed = false
                                                LiveVoiceService.setHeld(false)
                                            }
                                        }
                                    )
                                }
                            } else {
                                Modifier.clickable {
                                    toggleActivate()
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        SpeakerSymbol(
                            modifier = Modifier.size(90.dp),
                            color = if (buttonActive) Color.White else OnOrange
                        )

                        Spacer(Modifier.height(18.dp))

                        Text(
                            if (mode == Prefs.MODE_TALK) {
                                "Push to Talk"
                            } else {
                                "Push to Activate"
                            },
                            fontSize = 23.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (buttonActive) Color.White else OnOrange,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            when {
                                mode == Prefs.MODE_TALK -> "Hold to speak"
                                active -> "Tap to stop"
                                else -> "Tap to start"
                            },
                            fontSize = 16.sp,
                            color = if (buttonActive) Color.White else OnOrange
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    "Play Sound Presets",
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))

                // Ten quick-play sound buttons: two rows of five.
                for (row in 0..1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (column in 0..4) {
                            val slotIndex = row * 5 + column
                            val slot = slots[slotIndex]
                            val hasSound = slot.source.isNotEmpty()

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(58.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (hasSound) Orange else PanelHigh)
                                    .clickable {
                                        if (hasSound) {
                                            SoundPlayer.toggle(applicationContext, slotIndex)
                                        } else {
                                            toast("No sound chosen for ${Prefs.slotName(slotIndex)}")
                                        }
                                    }
                                    .padding(horizontal = 3.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = slot.label,
                                    color = if (hasSound) OnOrange else TextMuted,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2
                                )
                            }
                        }
                    }

                    if (row == 0) {
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun MaximumVehicleSpeedCard(modifier: Modifier = Modifier) {
        var maxSpeed by remember { mutableStateOf(Prefs.maxVehicleSpeed(this@MainActivity)) }
        var pendingSpeed by remember { mutableStateOf<Int?>(null) }
        var showAvasAcceptance by remember {
            mutableStateOf(maxSpeed > 0 && !Prefs.avasWarningAccepted(this@MainActivity))
        }

        fun applySpeed(speed: Int) {
            maxSpeed = speed
            Prefs.setMaxVehicleSpeed(this@MainActivity, speed)
        }

        fun requestSpeed(speed: Int) {
            if (speed > 0 && !Prefs.avasWarningAccepted(this@MainActivity)) {
                pendingSpeed = speed
                showAvasAcceptance = true
            } else {
                applySpeed(speed)
            }
        }

        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Maximum Vehicle Speed",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Text(
                    if (maxSpeed == 0) "0 km/h — stationary only" else "Playback up to $maxSpeed km/h",
                    fontSize = 15.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StepButton("−", enabled = maxSpeed > 0) {
                        requestSpeed((maxSpeed - 10).coerceAtLeast(0))
                    }
                    Text(
                        "$maxSpeed km/h",
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Bold,
                        color = Orange
                    )
                    StepButton("+", enabled = maxSpeed < 100) {
                        requestSpeed((maxSpeed + 10).coerceAtMost(100))
                    }
                }
                Text(
                    "Live microphone and sound presets stop if the limit is exceeded or vehicle speed data is unavailable.",
                    fontSize = 12.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center
                )
            }
        }

        if (showAvasAcceptance) {
            AlertDialog(
                onDismissRequest = {
                    if (!Prefs.avasWarningAccepted(this@MainActivity) && maxSpeed > 0) {
                        applySpeed(0)
                    }
                    showAvasAcceptance = false
                    pendingSpeed = null
                },
                title = { Text("AVAS and vehicle warning sounds") },
                text = {
                    Text(
                        "By selecting a maximum speed above 0 km/h, you acknowledge that ZEEKR Speaker audio may mix or overlap with AVAS (pedestrian warning) sounds, and may affect how clearly those sounds can be heard. The app does not control or verify AVAS operation. Do not use this setting if it could interfere with vehicle warning sounds."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        Prefs.setAvasWarningAccepted(this@MainActivity, true)
                        pendingSpeed?.let(::applySpeed)
                        pendingSpeed = null
                        showAvasAcceptance = false
                    }) {
                        Text("I understand and accept")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        if (!Prefs.avasWarningAccepted(this@MainActivity) && maxSpeed > 0) {
                            applySpeed(0)
                        }
                        pendingSpeed = null
                        showAvasAcceptance = false
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }

    @Composable
    private fun ModeChip(
        text: String,
        selected: Boolean,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) Orange else PanelHigh)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) OnOrange else TextMain,
                textAlign = TextAlign.Center
            )
        }
    }

    @Composable
    private fun SpeakerSymbol(
        modifier: Modifier = Modifier,
        color: Color
    ) {
        Canvas(modifier = modifier) {
            val speaker = Path().apply {
                moveTo(size.width * 0.10f, size.height * 0.38f)
                lineTo(size.width * 0.32f, size.height * 0.38f)
                lineTo(size.width * 0.60f, size.height * 0.15f)
                lineTo(size.width * 0.60f, size.height * 0.85f)
                lineTo(size.width * 0.32f, size.height * 0.62f)
                lineTo(size.width * 0.10f, size.height * 0.62f)
                close()
            }

            drawPath(path = speaker, color = color)

            drawArc(
                color = color,
                startAngle = -45f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(
                    size.width * 0.48f,
                    size.height * 0.29f
                ),
                size = Size(
                    size.width * 0.30f,
                    size.height * 0.42f
                ),
                style = Stroke(width = size.width * 0.055f)
            )

            drawArc(
                color = color,
                startAngle = -45f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(
                    size.width * 0.48f,
                    size.height * 0.12f
                ),
                size = Size(
                    size.width * 0.48f,
                    size.height * 0.76f
                ),
                style = Stroke(width = size.width * 0.055f)
            )
        }
    }

    // ---------------------------------------------------------
    // VOLUME CONTROL
    // ---------------------------------------------------------

    @Composable
    private fun VolumeCard() {
        var volume by remember {
            mutableStateOf(Prefs.volumePercent(this@MainActivity))
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Volume",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )

                    Text(
                        "$volume%",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Orange
                    )
                }

                Slider(
                    value = volume.toFloat(),
                    onValueChange = { newValue ->
                        val newVolume = newValue.toInt().coerceIn(0, 100)
                        volume = newVolume
                        changeVolume(newVolume)
                    },
                    valueRange = 0f..100f,
                    steps = 99,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Muted", fontSize = 13.sp, color = TextMuted)
                    Text("Maximum", fontSize = 13.sp, color = TextMuted)
                }
            }
        }
    }

    // ---------------------------------------------------------
    // CABIN AUDIO MUTING SETTINGS
    // ---------------------------------------------------------

    @Composable
    private fun CabinAudioMutingCard() {
        var scope by remember {
            mutableStateOf(Prefs.audioDuckingScope(this@MainActivity))
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Cabin Audio Muting",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    "Temporarily lowers other vehicle audio while the exterior speaker is in use. Choose when cabin audio muting is requested.",
                    fontSize = 14.sp,
                    color = TextMuted
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    listOf(
                        Prefs.DUCKING_OFF to "Off",
                        Prefs.DUCKING_MICROPHONE_ONLY to "Microphone only",
                        Prefs.DUCKING_MICROPHONE_AND_PRESETS to "Microphone + presets"
                    ).forEach { (value, label) ->
                        val selected = scope == value
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (selected) Orange else PanelHigh)
                                .clickable {
                                    scope = value
                                    Prefs.setAudioDuckingScope(this@MainActivity, value)
                                }
                                .padding(horizontal = 10.dp, vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                color = if (selected) OnOrange else TextMain,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                Text(
                    when (scope) {
                        Prefs.DUCKING_OFF -> "Cabin audio muting is disabled for microphone and presets."
                        Prefs.DUCKING_MICROPHONE_ONLY -> "Cabin audio is ducked during microphone use only."
                        else -> "Cabin audio is ducked during microphone and preset playback."
                    },
                    fontSize = 13.sp,
                    color = TextMuted
                )
            }
        }
    }

    // ---------------------------------------------------------
    // FLOATING BUTTON SETTINGS
    // ---------------------------------------------------------

    @Composable
    private fun FloatingCard(
        modifier: Modifier,
        status: Status
    ) {
        var pending by remember { mutableStateOf<Boolean?>(null) }

        LaunchedEffect(pending) {
            if (pending != null) {
                delay(1500)
                pending = null
            }
        }

        val checked = pending ?: status.floating

        var mode by remember {
            mutableStateOf(Prefs.mode(this@MainActivity))
        }

        var offSeconds by remember {
            mutableStateOf(Prefs.offSeconds(this@MainActivity))
        }

        var floatButtonSize by remember {
            mutableStateOf(Prefs.floatButtonSize(this@MainActivity))
        }

        var locked by remember {
            mutableStateOf(Prefs.floatButtonLocked(this@MainActivity))
        }

        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Top
            ) {
                Text(
                    "Floating Button",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    if (locked) {
                        "The floating button is locked. Turn off the lock to show the grip and move it. The orange arrow opens the presets."
                    } else {
                        "To move it, drag the small grip at its top-right corner. The orange arrow opens the presets."
                    },
                    fontSize = 14.sp,
                    color = TextMuted,
                    lineHeight = 18.sp
                )

                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Show floating button", fontSize = 18.sp, color = TextMain)
                        Text(
                            "A talk button that stays on top of other apps",
                            fontSize = 14.sp,
                            color = TextMuted
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Switch(
                        checked = checked,
                        onCheckedChange = { want ->
                            pending = want
                            setFloatingButton(want)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Orange
                        )
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Lock floating button", fontSize = 18.sp, color = TextMain)
                        Text(
                            if (locked) {
                                "Locked — grip hidden and movement disabled"
                            } else {
                                "Unlocked — drag the grip to move it"
                            },
                            fontSize = 14.sp,
                            color = TextMuted
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Switch(
                        checked = locked,
                        onCheckedChange = { value ->
                            locked = value
                            Prefs.setFloatButtonLocked(this@MainActivity, value)
                            refreshFloatingButtonSize()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Orange
                        )
                    )
                }

                Spacer(Modifier.height(18.dp))

                Text("Button mode", fontSize = 18.sp, color = TextMain)
                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeChip(
                        "Push to Talk",
                        mode == Prefs.MODE_TALK,
                        Modifier.weight(1f)
                    ) {
                        mode = Prefs.MODE_TALK
                        Prefs.setMode(this@MainActivity, mode)
                    }

                    ModeChip(
                        "Push to Activate",
                        mode == Prefs.MODE_ACTIVATE,
                        Modifier.weight(1f)
                    ) {
                        mode = Prefs.MODE_ACTIVATE
                        Prefs.setMode(this@MainActivity, mode)
                    }
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    if (mode == Prefs.MODE_TALK) {
                        "Hold the floating button to talk. Release to stop."
                    } else {
                        "Tap the floating button to start talking. Tap again to stop, or let the timer stop it."
                    },
                    fontSize = 14.sp,
                    color = TextMuted,
                    lineHeight = 18.sp
                )

                Spacer(Modifier.height(12.dp))

                val isActivate = mode == Prefs.MODE_ACTIVATE

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.alpha(if (isActivate) 1f else 0.3f)
                ) {
                    Text(
                        "Off time",
                        fontSize = 18.sp,
                        color = TextMain,
                        modifier = Modifier.weight(1f)
                    )

                    StepButton("-", enabled = isActivate && offSeconds > 3) {
                        if (isActivate) {
                            offSeconds = (offSeconds - 1).coerceAtLeast(3)
                            Prefs.setOffSeconds(this@MainActivity, offSeconds)
                        }
                    }

                    Text(
                        "$offSeconds s",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(80.dp)
                    )

                    StepButton("+", enabled = isActivate && offSeconds < 60) {
                        if (isActivate) {
                            offSeconds = (offSeconds + 1).coerceAtMost(60)
                            Prefs.setOffSeconds(this@MainActivity, offSeconds)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Button size",
                        fontSize = 18.sp,
                        color = TextMain,
                        modifier = Modifier.weight(1f)
                    )

                    StepButton("-", enabled = floatButtonSize > 50) {
                        floatButtonSize = (floatButtonSize - 5).coerceAtLeast(50)
                        Prefs.setFloatButtonSize(this@MainActivity, floatButtonSize)
                        refreshFloatingButtonSize()
                    }

                    Text(
                        "$floatButtonSize%",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(80.dp)
                    )

                    StepButton("+", enabled = floatButtonSize < 300) {
                        floatButtonSize = (floatButtonSize + 5).coerceAtMost(300)
                        Prefs.setFloatButtonSize(this@MainActivity, floatButtonSize)
                        refreshFloatingButtonSize()
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------
    // PRESET CONFIGURATION CARD
    // ---------------------------------------------------------

    @Composable
    private fun PresetConfigurationCard(
        modifier: Modifier = Modifier
    ) {
        var presetCount by remember {
            mutableStateOf(Prefs.floatPresetCount(this@MainActivity))
        }

        var gridLayout by remember {
            mutableStateOf(Prefs.floatGridLayout(this@MainActivity))
        }

        var expandDirection by remember {
            mutableStateOf(
                Prefs.floatExpandDirection(this@MainActivity)
            )
        }

        var presetButtonColour by remember {
            mutableStateOf(Prefs.presetButtonColour(this@MainActivity))
        }

        // Independent preset button size.
        var presetButtonSize by remember {
            mutableStateOf(Prefs.presetButtonSize(this@MainActivity))
        }

        // Independent preset label text size.
        var presetTextSize by remember {
            mutableStateOf(Prefs.presetTextSize(this@MainActivity))
        }

        var showPresetCountOptions by remember {
            mutableStateOf(false)
        }

        var showGridOptions by remember {
            mutableStateOf(false)
        }

        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    "Preset Configuration",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                Spacer(Modifier.height(16.dp))

                // EXPANSION DIRECTION — FIRST

                Text(
                    "Floating Button expansion direction",
                    fontSize = 18.sp,
                    color = TextMain
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ModeChip(
                        text = "Expand right",
                        selected = expandDirection == Prefs.FLOAT_EXPAND_RIGHT,
                        modifier = Modifier.weight(1f)
                    ) {
                        expandDirection = Prefs.FLOAT_EXPAND_RIGHT

                        Prefs.setFloatExpandDirection(
                            this@MainActivity,
                            expandDirection
                        )

                        refreshFloatingButtonSize()
                    }

                    ModeChip(
                        text = "Expand left",
                        selected = expandDirection == Prefs.FLOAT_EXPAND_LEFT,
                        modifier = Modifier.weight(1f)
                    ) {
                        expandDirection = Prefs.FLOAT_EXPAND_LEFT

                        Prefs.setFloatExpandDirection(
                            this@MainActivity,
                            expandDirection
                        )

                        refreshFloatingButtonSize()
                    }
                }

                Spacer(Modifier.height(18.dp))

                Text(
                    "Floating preset button colour",
                    fontSize = 18.sp,
                    color = TextMain
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip(
                        text = "Grey",
                        selected = presetButtonColour == Prefs.PRESET_COLOUR_BLACK,
                        modifier = Modifier.weight(1f)
                    ) {
                        presetButtonColour = Prefs.PRESET_COLOUR_BLACK
                        Prefs.setPresetButtonColour(this@MainActivity, presetButtonColour)
                        refreshFloatingButtonSize()
                    }
                    ModeChip(
                        text = "White",
                        selected = presetButtonColour == Prefs.PRESET_COLOUR_WHITE,
                        modifier = Modifier.weight(1f)
                    ) {
                        presetButtonColour = Prefs.PRESET_COLOUR_WHITE
                        Prefs.setPresetButtonColour(this@MainActivity, presetButtonColour)
                        refreshFloatingButtonSize()
                    }
                    ModeChip(
                        text = "Semi-transparent",
                        selected = presetButtonColour == Prefs.PRESET_COLOUR_SEMI_TRANSPARENT,
                        modifier = Modifier.weight(1f)
                    ) {
                        presetButtonColour = Prefs.PRESET_COLOUR_SEMI_TRANSPARENT
                        Prefs.setPresetButtonColour(this@MainActivity, presetButtonColour)
                        refreshFloatingButtonSize()
                    }
                }

                Spacer(Modifier.height(18.dp))

                // NUMBER OF PRESETS — SECOND

                Text(
                    "Number of presets",
                    fontSize = 18.sp,
                    color = TextMain
                )

                Spacer(Modifier.height(6.dp))

                Box {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(PanelHigh)
                            .clickable {
                                showPresetCountOptions = true
                            }
                            .padding(horizontal = 14.dp, vertical = 13.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                presetCount.toString(),
                                color = Color.White,
                                fontSize = 17.sp,
                                modifier = Modifier.weight(1f)
                            )

                            Text("▼", color = Orange)
                        }
                    }

                    androidx.compose.material3.DropdownMenu(
                        expanded = showPresetCountOptions,
                        onDismissRequest = {
                            showPresetCountOptions = false
                        }
                    ) {
                        (1..10).forEach { count ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Text(count.toString())
                                },
                                onClick = {
                                    presetCount = count

                                    Prefs.setFloatPresetCount(
                                        this@MainActivity,
                                        count
                                    )

                                    gridLayout = Prefs.floatGridLayout(
                                        this@MainActivity
                                    )

                                    showPresetCountOptions = false
                                    showGridOptions = false

                                    refreshFloatingButtonSize()
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                // LAYOUT

                Text(
                    "Layout",
                    fontSize = 18.sp,
                    color = TextMain
                )

                Spacer(Modifier.height(6.dp))

                Box {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(PanelHigh)
                            .clickable {
                                showGridOptions = true
                            }
                            .padding(horizontal = 14.dp, vertical = 13.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                gridLayout,
                                color = Color.White,
                                fontSize = 17.sp,
                                modifier = Modifier.weight(1f)
                            )

                            Text("▼", color = Orange)
                        }
                    }

                    androidx.compose.material3.DropdownMenu(
                        expanded = showGridOptions,
                        onDismissRequest = {
                            showGridOptions = false
                        }
                    ) {
                        Prefs.gridLayoutsForCount(presetCount).forEach { option ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Text(option)
                                },
                                onClick = {
                                    gridLayout = option

                                    Prefs.setFloatGridLayout(
                                        this@MainActivity,
                                        option
                                    )

                                    showGridOptions = false
                                    refreshFloatingButtonSize()
                                }
                            )
                        }
                    }
                }

                // PRESET BUTTON SIZE

                Spacer(Modifier.height(18.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Preset button size",
                        fontSize = 18.sp,
                        color = TextMain,
                        modifier = Modifier.weight(1f)
                    )

                    StepButton("-", enabled = presetButtonSize > 50) {
                        presetButtonSize =
                            (presetButtonSize - 5).coerceAtLeast(50)

                        Prefs.setPresetButtonSize(
                            this@MainActivity,
                            presetButtonSize
                        )

                        refreshFloatingButtonSize()
                    }

                    Text(
                        "$presetButtonSize%",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(80.dp)
                    )

                    StepButton("+", enabled = presetButtonSize < 300) {
                        presetButtonSize =
                            (presetButtonSize + 5).coerceAtMost(300)

                        Prefs.setPresetButtonSize(
                            this@MainActivity,
                            presetButtonSize
                        )

                        refreshFloatingButtonSize()
                    }
                }

                // PRESET TEXT SIZE — INDEPENDENT OF BUTTON SIZE

                Spacer(Modifier.height(18.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Text size",
                        fontSize = 18.sp,
                        color = TextMain,
                        modifier = Modifier.weight(1f)
                    )

                    StepButton("-", enabled = presetTextSize > 50) {
                        presetTextSize =
                            (presetTextSize - 5).coerceAtLeast(50)

                        Prefs.setPresetTextSize(
                            this@MainActivity,
                            presetTextSize
                        )

                        refreshFloatingButtonSize()
                    }

                    Text(
                        "$presetTextSize%",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(80.dp)
                    )

                    StepButton("+", enabled = presetTextSize < 200) {
                        presetTextSize =
                            (presetTextSize + 5).coerceAtMost(200)

                        Prefs.setPresetTextSize(
                            this@MainActivity,
                            presetTextSize
                        )

                        refreshFloatingButtonSize()
                    }
                }
            }
        }
    }

    @Composable
    private fun StepButton(
        label: String,
        enabled: Boolean = true,
        onClick: () -> Unit
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = PanelHigh,
                contentColor = Color.White
            )
        ) {
            Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }

    // ---------------------------------------------------------
    // SOUND PRESETS — FILE MANAGEMENT UNCHANGED
    // ---------------------------------------------------------

    @Composable
    private fun SoundButtonsCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "Sound Presets",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                Text(
                    "These appear when you tap the orange arrow on the floating button. Change the text on each preset and choose the sound it plays through the exterior speaker.",
                    fontSize = 14.sp,
                    color = TextMuted
                )

                for (i in 0 until Prefs.SLOT_COUNT) SlotRow(i)

                Text(
                    "Presets 1–10 can each have their own label and sound. Choose an audio file for any preset. Only the first 60 seconds are used.",
                    fontSize = 13.sp,
                    color = TextMuted
                )
            }
        }
    }

    @Composable
    private fun SlotRow(i: Int) {
        val slot = slots[i]

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(PanelHigh)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = slot.label,
                    onValueChange = { text ->
                        val clean = text.take(26)
                        slots[i] = slots[i].copy(label = clean)
                        Prefs.setLabel(this@MainActivity, i, clean)
                    },
                    label = { Text(Prefs.slotName(i)) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focusState ->
                            if (!focusState.isFocused) {
                                refreshFloatingButtonSize()
                            }
                        }
                )

                Spacer(Modifier.width(12.dp))

                Button(
                    onClick = { chooseFile(i) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Orange,
                        contentColor = Color.Black
                    )
                ) {
                    Text("Choose file", fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.width(8.dp))

                Button(
                    onClick = { SoundPlayer.toggle(applicationContext, i) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Panel,
                        contentColor = Color.White
                    )
                ) {
                    Text("Play", fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.width(4.dp))

                TextButton(
                    onClick = { resetSlot(i) },
                    colors = ButtonDefaults.textButtonColors(contentColor = Orange)
                ) {
                    Text("Reset")
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                "Sound: ${slot.sourceName}",
                fontSize = 14.sp,
                color = TextMuted
            )
        }
    }

    // ---------------------------------------------------------
    // AUDIO BROWSER
    // ---------------------------------------------------------

    @Composable
    private fun AudioBrowserDialog() {
        AlertDialog(
            onDismissRequest = { showAudioBrowser = false },
            containerColor = Panel,
            title = {
                Text(
                    "Choose audio file",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Available audio files",
                            color = TextMuted,
                            fontSize = 13.sp
                        )

                        TextButton(onClick = { loadAudioFiles() }) {
                            Text("Refresh", color = Orange)
                        }
                    }

                    when {
                        audioLoading -> {
                            Text(
                                "Loading audio files...",
                                color = TextMain,
                                modifier = Modifier.padding(12.dp)
                            )
                        }

                        audioFiles.isEmpty() -> {
                            Text(
                                "No indexed audio files found.\n\nConnect your USB drive and check that Android has scanned its audio files. Then press Refresh.",
                                color = TextMuted,
                                modifier = Modifier.padding(12.dp)
                            )
                        }

                        else -> {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                audioFiles.forEach { file ->
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { selectAudioFile(file) }
                                            .padding(vertical = 12.dp, horizontal = 8.dp)
                                    ) {
                                        Text(file.name, color = TextMain, fontSize = 15.sp)
                                        Text("Tap to select", color = TextMuted, fontSize = 12.sp)
                                    }

                                    Spacer(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(PanelHigh)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAudioBrowser = false }) {
                    Text("Close", color = Orange)
                }
            }
        )
    }

    // ---------------------------------------------------------
    // USB WAV RECORDER
    // ---------------------------------------------------------

    @Composable
    private fun UsbWavRecorderCard(status: Status) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "USB Recorder",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    "Select a connected USB drive as the destination for microphone recordings. Files are saved in ZEEKR-Speaker/Recordings.",
                    fontSize = 14.sp,
                    color = TextMuted
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${usbVolumes.size} drive(s) found",
                        color = if (usbVolumes.isEmpty()) Bad else Good,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { if (!usbRecording) refreshUsbVolumes() }) {
                        Text("Refresh", color = Orange)
                    }
                }
                if (usbVolumes.isEmpty()) {
                    Text("No USB drives detected. The app checks /proc/mounts, using standard Android/Linux interfaces.", color = TextMuted, fontSize = 14.sp)
                } else {
                    usbVolumes.forEach { volume ->
                        val selected = volume.root.absolutePath == selectedUsbPath
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = !usbRecording) { selectUsbVolume(volume.root.absolutePath) },
                            color = if (selected) Color(0xFF3A2A1C) else PanelHigh,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    (if (selected) "✓  " else "○  ") + volume.label,
                                    color = if (selected) Orange else TextMain,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "${formatUsbBytes(volume.freeBytes)} free of ${formatUsbBytes(volume.totalBytes)}",
                                    color = TextMuted,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = usbRecordingFileName,
                    onValueChange = { usbRecordingFileName = it.take(80) },
                    enabled = !usbRecording,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Recording file name") },
                    placeholder = { Text("Enter a file name") }
                )
                Button(
                    onClick = { if (usbRecording) stopUsbRecording() else startUsbRecording() },
                    enabled = status.mic && (usbRecording || (selectedUsbPath.isNotBlank() && usbRecordingFileName.isNotBlank())),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (usbRecording) Pressed else Orange,
                        contentColor = Color.Black
                    )
                ) {
                    Text(if (usbRecording) "Stop recording" else "Start WAV recording", fontWeight = FontWeight.Bold)
                }
                if (!status.mic) {
                    Text("Grant microphone permission before recording.", color = Bad, fontSize = 13.sp)
                }
                // Status / completion feedback is deliberately at the bottom of the card.
                Text(usbRecorderMessage, color = TextMuted, fontSize = 13.sp)
            }
        }
    }

    @Composable
    private fun CarMusicCard(modifier: Modifier = Modifier) {
        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Play Car Music",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    "Send eligible head-unit audio to the ZEEKR exterior speaker. Android will show its system confirmation only when you tap Start Car Music; it is not requested when the app opens. Sound presets temporarily take over the speaker, then car music resumes without ending the capture session. Some protected or system audio may not be capturable.",
                    fontSize = 14.sp,
                    color = TextMuted
                )
                Button(
                    onClick = {
                        if (carMusicActive) {
                            CarMusicRouter.stop()
                        } else if (!hasMic()) {
                            toast("Microphone permission is required for Android playback capture")
                            requestPermissions()
                        } else {
                            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            carMusicPermission.launch(manager.createScreenCaptureIntent())
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (carMusicActive) Pressed else Orange,
                        contentColor = Color.Black
                    )
                ) {
                    Text(if (carMusicActive) "Stop Car Music" else "Start Car Music", fontWeight = FontWeight.Bold)
                }
                Text(carMusicMessage, color = TextMuted, fontSize = 13.sp)
            }
        }
    }

    private fun formatUsbBytes(bytes: Long): String {
        if (bytes <= 0L) return "Unknown"
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) String.format(Locale.US, "%.1f GB", gb)
        else String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
    }

    @Composable
    private fun DiagnosticsCard(status: Status) {
        var selectedGain by remember {
            mutableStateOf(LiveVoiceService.getMicrophoneGain().coerceIn(0.5f, 5f))
        }
        var appliedGain by remember {
            mutableStateOf(LiveVoiceService.getMicrophoneGain().coerceIn(0.5f, 5f))
        }

        fun gainText(value: Float): String =
            if (value % 1f == 0f) value.toInt().toString()
            else String.format(Locale.ROOT, "%.1f", value)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Diagnostics", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                DiagnosticRow("Microphone permission", status.mic, "Granted", "Missing")
                DiagnosticRow("Display over other apps", status.overlay, "Granted", "Missing")
                DiagnosticRow("Exterior speaker", status.speakerReady, "Ready", "Not ready")

                Spacer(Modifier.height(2.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Microphone gain test", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text(
                        "Adjust the microphone gain in 0.5x steps. Higher gain levels may cause audio feedback (a loud squealing or howling sound). Start low and increase carefully.",
                        fontSize = 14.sp,
                        color = TextMuted
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        StepButton("−", enabled = selectedGain > 0.5f) {
                            selectedGain = (selectedGain - 0.5f).coerceAtLeast(0.5f)
                        }
                        Text(
                            "x${gainText(selectedGain)}",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = Orange,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(100.dp)
                        )
                        StepButton("+", enabled = selectedGain < 5f) {
                            selectedGain = (selectedGain + 0.5f).coerceAtMost(5f)
                        }
                    }
                    Text(
                        "Currently applied: x${gainText(appliedGain)}",
                        fontSize = 14.sp,
                        color = TextMuted,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Button(
                        onClick = {
                            Prefs.setMicrophoneGain(this@MainActivity, selectedGain)
                            LiveVoiceService.setMicrophoneGain(selectedGain)
                            appliedGain = selectedGain
                            toast("Microphone gain set to x${gainText(selectedGain)}")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.Black)
                    ) {
                        Text("Apply gain", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(2.dp))
                Text("Last Live Voice Run", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                val lastRunWithoutOutput = status.lastRun
                    .replace(Regex("\\s*Output:.*?(?=\\.\\s*Hold\\b|$)"), "")
                    .trim()
                    .ifBlank { "none yet" }
                Text(lastRunWithoutOutput, fontSize = 13.sp, color = TextMuted)
            }
        }
    }

    @Composable
    private fun DiagnosticRow(label: String, ready: Boolean, readyText: String, missingText: String) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Canvas(Modifier.size(9.dp)) {
                drawCircle(if (ready) Good else Bad)
            }
            Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp, color = TextMain)
            Text(
                if (ready) readyText else missingText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (ready) Good else Bad
            )
        }
    }

    @Composable
    private fun SingleLineUpdateProgress() {
        val transition = rememberInfiniteTransition(label = "updateProgress")
        val position by transition.animateFloat(
            initialValue = -0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1250),
                repeatMode = RepeatMode.Restart
            ),
            label = "updateProgressPosition"
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
        ) {
            drawRoundRect(color = PanelHigh, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
            val segmentWidth = size.width * 0.35f
            val left = position * size.width
            clipRect {
                drawRoundRect(
                    color = Orange,
                    topLeft = Offset(left, 0f),
                    size = Size(segmentWidth, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                )
            }
        }
    }

    // ---------------------------------------------------------
    // GITHUB UPDATES
    // ---------------------------------------------------------

    override fun onResume() {
        super.onResume()
        pendingUpdate = GitHubUpdater.pendingUpdate(this)
        val savedStatus = getSharedPreferences("update_state", MODE_PRIVATE).getString("last_status", null)
        if (!savedStatus.isNullOrBlank()) updateStatus = savedStatus
        if (pendingUpdate != null && updateStatus == "Ready to check for updates.") {
            updateStatus = "Update ${pendingUpdate!!.version} is downloaded and ready to install."
        }
    }

    private fun checkForUpdates() {
        if (updateBusy) return
        updateBusy = true
        updateStatus = "Checking GitHub for updates…"
        getSharedPreferences("update_state", MODE_PRIVATE).edit().remove("last_status").apply()
        GitHubUpdater.check(this) { message, release ->
            if (release == null) {
                updateBusy = false
                updateStatus = message
            } else {
                updateStatus = "Update ${release.version} found. Downloading…"
                downloadUpdate(release)
            }
        }
    }

    private fun downloadUpdate(release: GitHubUpdater.Release) {
        GitHubUpdater.download(this, release) { message, downloaded ->
            updateStatus = message
            pendingUpdate = downloaded
            if (downloaded == null) {
                updateBusy = false
            } else {
                updateStatus = "Update ${downloaded.version} downloaded and verified. Starting installation…"
                GitHubUpdater.install(this, downloaded) { installMessage ->
                    updateBusy = false
                    updateStatus = installMessage
                    getSharedPreferences("update_state", MODE_PRIVATE).edit().putString("last_status", installMessage).apply()
                }
            }
        }
    }

    private fun retryUpdateInstallation() {
        val update = pendingUpdate ?: GitHubUpdater.pendingUpdate(this).also { pendingUpdate = it } ?: return
        updateBusy = true
        updateStatus = "Retrying installation of ${update.version}…"
        GitHubUpdater.install(this, update) { message ->
            updateBusy = false
            updateStatus = message
            getSharedPreferences("update_state", MODE_PRIVATE).edit().putString("last_status", message).apply()
        }
    }

    @Composable
    private fun UpdateCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("App Updates", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(
                    "Check for and install ZEEKR Speaker updates from GitHub. During self-update, Android may close the app while replacing it. When installation finishes, reopen ZEEKR Speaker from App Lab / Z-emulator.",
                    fontSize = 14.sp,
                    color = TextMuted
                )
                Text(
                    "Current version: ${BuildConfig.VERSION_NAME}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
                Text(
                    updateStatus,
                    fontSize = 14.sp,
                    color = when {
                        updateStatus.contains("success", true) || updateStatus.contains("up to date", true) -> Good
                        updateStatus.contains("failed", true) || updateStatus.contains("could not", true) || updateStatus.contains("error", true) -> Bad
                        else -> TextMuted
                    }
                )
                if (updateBusy) {
                    SingleLineUpdateProgress()
                }
                if (pendingUpdate != null) {
                    Button(
                        onClick = { retryUpdateInstallation() },
                        enabled = !updateBusy,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.Black)
                    ) {
                        Text(if (updateBusy) "Please wait…" else "Install Update", fontWeight = FontWeight.Bold)
                    }
                }
                Button(
                    onClick = { checkForUpdates() },
                    enabled = !updateBusy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.Black)
                ) {
                    Text("Check for Updates", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

}
