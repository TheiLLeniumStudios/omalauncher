package dev.omalauncher

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omalauncher.theme.ThemeStore
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.TextSize
import dev.omalauncher.ui.ThemePalette
import dev.omalauncher.ui.applySystemBars
import dev.omalauncher.ui.decodeForScreen
import dev.omalauncher.ui.frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Omarchy lock cover: hyprlock's look over Android's lock screen, put up
 * as the screen goes off (see [Runtime]). It is only a skin: unlocking hands
 * off to the system's PIN or fingerprint prompt, so the keyguard stays in charge.
 */
class LockCoverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars(this, Palette.isLight)
        // The cover stays until the phone is unlocked.
        onBackPressedDispatcher.addCallback(this) {}
        setContent { LockCover(::unlock) }
    }

    override fun onResume() {
        super.onResume()
        // Not locked (yet, with a lock delay; or Smart Lock kept it open): nothing to cover.
        if (!getSystemService(KeyguardManager::class.java).isKeyguardLocked) finish()
    }

    private fun unlock() {
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = finish()
            },
        )
    }

    companion object {
        private const val TAG = "lock-cover"

        /** Puts the cover up; Android allows that from the background for the default home app, and may refuse it otherwise. */
        fun show(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(context, LockCoverActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                )
            }.onFailure { Log.w(TAG, "cannot show the lock cover", it) }
        }
    }
}

/** The cover's clock and date lines. */
class LockClock(
    val time: String,
    val date: String,
)

/** [at] as the cover shows it: hours and minutes in the system's 12- or 24-hour style, and the day spelled out. */
fun lockClock(
    at: LocalDateTime,
    is24Hour: Boolean,
    locale: Locale,
) = LockClock(
    at.format(DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm", locale)),
    at.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", locale)),
)

/** Like hyprlock: the theme's background, softened, with the time, the date and a password box. */
@Composable
private fun LockCover(onUnlock: () -> Unit) {
    val context = LocalContext.current
    val theme by produceState<Pair<ThemePalette, ImageBitmap?>>(Palette.current to null) {
        value =
            withContext(Dispatchers.IO) {
                val (colors, file) = ThemeStore.savedSelection(context) ?: return@withContext Palette.current to null
                ThemePalette(colors) to file?.let { decodeForScreen(it, context)?.asImageBitmap() }
            }
    }
    val (palette, background) = theme
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var battery by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            battery = batteryPercent(context)
            delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }
    val clock = lockClock(now, DateFormat.is24HourFormat(context), Locale.getDefault())
    Box(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged < -48.dp.toPx()) onUnlock() },
                ) { _, dy -> dragged += dy }
            },
    ) {
        background?.let {
            Image(
                it,
                null,
                Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(16.dp) else Modifier),
                contentScale = ContentScale.Crop,
            )
        }
        // A blur needs Android 12; below it the dim alone keeps the text readable.
        Box(Modifier.fillMaxSize().background(palette.background.copy(alpha = if (Build.VERSION.SDK_INT >= 31) 0.45f else 0.6f)))
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BasicText(clock.time, style = palette.mono.copy(fontSize = 88.sp))
            BasicText(clock.date, style = palette.mono.copy(fontSize = TextSize.Title))
            Spacer(Modifier.height(48.dp))
            Box(
                Modifier
                    .widthIn(max = 320.dp)
                    .fillMaxWidth()
                    .frame(2.dp, palette.accent, palette.background.copy(alpha = 0.6f), radius = 12.dp)
                    .clickable(onClick = onUnlock)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("enter password", style = palette.mono.copy(color = palette.muted, fontSize = TextSize.Large))
            }
        }
        battery?.let {
            BasicText(
                "battery $it%",
                Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(bottom = 24.dp),
                style = palette.mono.copy(color = palette.muted, fontSize = TextSize.Small),
            )
        }
    }
}

/** The battery's charge in percent, from the sticky battery broadcast; null if the phone doesn't say. */
private fun batteryPercent(context: Context): Int? {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1).takeIf { it >= 0 } ?: return null
    // Some builds report no scale (0).
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).takeIf { it > 0 } ?: 100
    return level * 100 / scale
}
