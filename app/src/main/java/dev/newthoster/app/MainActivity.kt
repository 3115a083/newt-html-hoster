@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.newthoster.app

import android.Manifest
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import android.widget.Toast
import android.widget.EditText
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val localePrefs = getSharedPreferences("ui_locale", Context.MODE_PRIVATE)
        if (!localePrefs.getBoolean("initialized", false)) {
            val language = if (Locale.getDefault().language.equals("de", true)) "de" else "en"
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language))
            localePrefs.edit().putBoolean("initialized", true).apply()
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
        }
        setContent { HosterRoot() }
    }
}

private enum class Palette { DYNAMIC, OCEAN, FOREST, SUNSET, AURORA, LAVENDER, GRAPHITE }
private enum class Appearance { SYSTEM, LIGHT, DARK }
private enum class Page { HOME, SETTINGS }

private data class ThemePack(val scheme: ColorScheme, val gradient: List<Color>)

@Composable
private fun HosterRoot() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var palette by remember { mutableStateOf(runCatching { Palette.valueOf(prefs.getString("palette", "DYNAMIC")!!) }.getOrDefault(Palette.DYNAMIC)) }
    var appearance by remember { mutableStateOf(runCatching { Appearance.valueOf(prefs.getString("appearance", "SYSTEM")!!) }.getOrDefault(Appearance.SYSTEM)) }
    val dark = when (appearance) { Appearance.SYSTEM -> isSystemInDarkTheme(); Appearance.LIGHT -> false; Appearance.DARK -> true }
    val pack = themePack(context, palette, dark)

    MaterialTheme(colorScheme = pack.scheme, typography = Typography()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            MainScreen(
                palette, appearance, pack.gradient,
                onPalette = { palette = it; prefs.edit().putString("palette", it.name).apply() },
                onAppearance = { appearance = it; prefs.edit().putString("appearance", it.name).apply() }
            )
        }
    }
}

@Composable
private fun themePack(context: Context, palette: Palette, dark: Boolean): ThemePack {
    if (palette == Palette.DYNAMIC && Build.VERSION.SDK_INT >= 31) {
        val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        return ThemePack(scheme, listOf(scheme.primary, scheme.tertiary))
    }
    fun scheme(primary: Color, secondary: Color, tertiary: Color): ColorScheme =
        if (dark) darkColorScheme(primary = primary, secondary = secondary, tertiary = tertiary)
        else lightColorScheme(primary = primary, secondary = secondary, tertiary = tertiary)
    return when (palette) {
        Palette.FOREST -> ThemePack(scheme(Color(0xFF61D095), Color(0xFF8ED8B0), Color(0xFFB8E986)), listOf(Color(0xFF0E7A4D), Color(0xFF57B35F)))
        Palette.SUNSET -> ThemePack(scheme(Color(0xFFFF8B68), Color(0xFFFFB36B), Color(0xFFE76BA7)), listOf(Color(0xFFFF7657), Color(0xFFEE5A91)))
        Palette.AURORA -> ThemePack(scheme(Color(0xFF58D6C7), Color(0xFF6D9EFF), Color(0xFF9B7BFF)), listOf(Color(0xFF00AFA0), Color(0xFF6C63E8)))
        Palette.LAVENDER -> ThemePack(scheme(Color(0xFFB19CFF), Color(0xFFD2A7FF), Color(0xFFFFA8D7)), listOf(Color(0xFF7459D9), Color(0xFFC05EAA)))
        Palette.GRAPHITE -> ThemePack(scheme(Color(0xFFAEC6FF), Color(0xFFB7C4D9), Color(0xFF9ED9D0)), listOf(Color(0xFF334155), Color(0xFF0F766E)))
        else -> ThemePack(scheme(Color(0xFF59D9D1), Color(0xFF79AFFF), Color(0xFF7B77FF)), listOf(Color(0xFF007F7B), Color(0xFF4169D8)))
    }
}

@Composable
private fun MainScreen(
    palette: Palette,
    appearance: Appearance,
    gradient: List<Color>,
    onPalette: (Palette) -> Unit,
    onAppearance: (Appearance) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as HosterApp
    val runtime by RuntimeBus.state.collectAsStateWithLifecycle()
    val debugLines by RuntimeDebugBus.lines.collectAsStateWithLifecycle()
    var buckets by remember { mutableStateOf(app.buckets.list()) }
    var selected by remember { mutableStateOf<Bucket?>(null) }
    var page by remember { mutableStateOf(Page.HOME) }
    var showCreate by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    var timer by remember { mutableStateOf("60") }
    var lastBack by remember { mutableLongStateOf(0L) }

    fun refresh() { buckets = app.buckets.list(); selected = selected?.let { s -> buckets.firstOrNull { it.id == s.id } } }
    LaunchedEffect(runtime.running, runtime.connected, runtime.linkMbps, runtime.remainingMinutes) { refresh() }

    BackHandler {
        when {
            selected != null -> { selected = null; refresh() }
            page == Page.SETTINGS -> page = Page.HOME
            else -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBack < 1800) (context as? ComponentActivity)?.finish()
                else {
                    lastBack = now
                    Toast.makeText(context, context.getString(R.string.double_back_exit), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    when {
        selected != null -> BucketDetail(selected!!, gradient, onBack = { selected = null; refresh() }, onChanged = { refresh() })
        page == Page.SETTINGS -> SettingsScreen(app, palette, appearance, gradient, onPalette, onAppearance, onBack = { page = Page.HOME })
        else -> Dashboard(
            runtime = runtime, buckets = buckets, gradient = gradient, timer = timer,
            onSettings = { page = Page.SETTINGS },
            onTimer = { showTimer = true },
            onDebug = { showDebug = true },
            onToggleRuntime = { enabled ->
                if (enabled) {
                    val mins = timer.toLongOrNull()?.coerceIn(1, 10080) ?: 60
                    ContextCompat.startForegroundService(context, Intent(context, NewtHostService::class.java).setAction(NewtHostService.ACTION_START).putExtra(NewtHostService.EXTRA_MINUTES, mins))
                } else context.startService(Intent(context, NewtHostService::class.java).setAction(NewtHostService.ACTION_STOP))
            },
            onToggleBucket = { bucket, enabled -> app.buckets.toggle(bucket.id, enabled); refresh() },
            onBucket = { selected = it },
            onCreate = { showCreate = true },
            hasSecret = app.vault.hasSecret()
        )
    }

    if (showTimer) TimerDialog(timer, { timer = it }, { showTimer = false })
    if (showDebug) DebugDialog(debugLines, onDismiss = { showDebug = false })
    if (showCreate) CreateBucketDialog(onDismiss = { showCreate = false }, onCreate = { app.buckets.create(it); refresh(); showCreate = false })
}

@Composable
private fun Dashboard(
    runtime: RuntimeState, buckets: List<Bucket>, gradient: List<Color>, timer: String,
    onSettings: () -> Unit, onTimer: () -> Unit, onDebug: () -> Unit, onToggleRuntime: (Boolean) -> Unit,
    onToggleBucket: (Bucket, Boolean) -> Unit, onBucket: (Bucket) -> Unit, onCreate: () -> Unit, hasSecret: Boolean
) {
    Scaffold(containerColor = Color.Transparent) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onSettings, modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .65f))) {
                        Icon(Icons.Rounded.Settings, stringResource(R.string.settings))
                    }
                    Spacer(Modifier.width(10.dp))
                    SolidToggle(runtime.running, enabled = runtime.running || hasSecret, onCheckedChange = onToggleRuntime)
                }
            }
            item { ConnectionHero(runtime, gradient, timer, onTimer, onDebug) }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.buckets), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("${buckets.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalIconButton(onClick = onCreate) { Icon(Icons.Rounded.Add, stringResource(R.string.add_bucket)) }
                }
            }
            items(buckets, key = { it.id }) { bucket ->
                BucketCard(bucket, gradient, onClick = { onBucket(bucket) }, onToggle = { onToggleBucket(bucket, it) })
            }
            item { Text(stringResource(R.string.security_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ConnectionHero(runtime: RuntimeState, gradient: List<Color>, timer: String, onTimer: () -> Unit, onDebug: () -> Unit) {
    val status = when {
        runtime.connected -> stringResource(R.string.connected)
        runtime.running -> stringResource(R.string.connecting)
        runtime.status != "Stopped" && runtime.status.isNotBlank() -> runtime.status
        else -> stringResource(R.string.disconnected)
    }
    Card(shape = RoundedCornerShape(30.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent), modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.background(Brush.linearGradient(gradient)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.runtime), color = Color.White.copy(alpha=.78f), style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (runtime.running && !runtime.connected) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.5.dp,
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = .24f)
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            status,
                            color = Color.White,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Surface(shape = CircleShape, color = Color.White.copy(alpha=.18f)) {
                    Icon(if (runtime.connected) Icons.Rounded.Wifi else Icons.Rounded.WifiOff, null, tint = Color.White, modifier = Modifier.padding(14.dp).size(26.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassMetric(Icons.Rounded.Speed, stringResource(R.string.link_strength), if (runtime.linkMbps > 0) "${runtime.linkMbps} Mbps" else "—", Modifier.weight(1f))
                GlassMetric(Icons.Rounded.Timer, stringResource(R.string.timer), runtime.remainingMinutes?.let { "$it min" } ?: "$timer min", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = onTimer,
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha=.18f), contentColor = Color.White),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Timer, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.timer))
                }
                FilledTonalButton(
                    onClick = onDebug,
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha=.18f), contentColor = Color.White),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.BugReport, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.connection_debug))
                }
            }
        }
    }
}

@Composable
private fun GlassMetric(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha=.14f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White); Spacer(Modifier.width(9.dp))
        Column { Text(label, color = Color.White.copy(alpha=.72f), style = MaterialTheme.typography.labelSmall); Text(value, color = Color.White, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun BucketCard(bucket: Bucket, gradient: List<Color>, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as HosterApp
    val files = remember(bucket.id, bucket.bytesServed) { app.buckets.files(bucket.id) }
    val target = "127.0.0.1:${bucket.port}"
    ElevatedCard(onClick = onClick, shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(gradient)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Folder, null, tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Text(bucket.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                SolidToggle(bucket.enabled, true, onToggle)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftMetric(stringResource(R.string.traffic), formatBytes(bucket.bytesServed), Modifier.weight(1f))
                SoftMetric(
                    stringResource(R.string.port),
                    bucket.port.toString(),
                    Modifier.weight(1f),
                    onLongClick = { copyToClipboard(context, "port", bucket.port.toString()) }
                )
            }
            Column {
                Text(stringResource(R.string.local_target), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    target,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = { copyToClipboard(context, "Pangolin target", target) }
                    )
                )
            }
            HorizontalDivider()
            Text(stringResource(R.string.assets), fontWeight = FontWeight.SemiBold)
            if (files.isEmpty()) Text(stringResource(R.string.empty_bucket), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else files.take(4).forEach { file ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Description, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(file.relativeTo(app.buckets.directory(bucket.id)).path, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(formatBytes(file.length()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (files.size > 4) Text("+${files.size - 4} · " + stringResource(R.string.files_count, files.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable private fun SoftMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null
) {
    val interactionModifier = if (onLongClick != null) {
        modifier.combinedClickable(onClick = {}, onLongClick = onLongClick)
    } else modifier
    Column(interactionModifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.55f)).padding(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SolidToggle(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            uncheckedBorderColor = Color.Transparent
        )
    )
}

@Composable
private fun SettingsScreen(
    app: HosterApp, palette: Palette, appearance: Appearance, gradient: List<Color>,
    onPalette: (Palette) -> Unit, onAppearance: (Appearance) -> Unit, onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(app.vault.hasSecret()) }
    var replacing by remember { mutableStateOf(false) }
    var endpoint by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings), fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SettingsSection(stringResource(R.string.connection_setup), Icons.Rounded.VpnKey) {
                    if (!saved || replacing) {
                        OutlinedTextField(endpoint, { endpoint = it }, label = { Text(stringResource(R.string.pangolin_endpoint)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp))
                        OutlinedTextField(id, { id = it }, label = { Text(stringResource(R.string.newt_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp))
                        OutlinedTextField(secret, { secret = it }, label = { Text(stringResource(R.string.newt_secret)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp))
                        Button(enabled = endpoint.startsWith("https://") && id.isNotBlank() && secret.isNotBlank(), onClick = {
                            val chars = secret.toCharArray(); secret = ""
                            scope.launch {
                                status = "TLS…"
                                val result = runCatching {
                                    val pin = withContext(Dispatchers.IO) { TlsPin.fetchSpkiSha256(endpoint) }
                                    app.vault.save(endpoint, id, chars, pin); chars.fill('\u0000')
                                }
                                if (result.isSuccess) { saved = true; replacing = false; endpoint = ""; id = ""; status = null }
                                else { chars.fill('\u0000'); status = result.exceptionOrNull()?.message }
                            }
                        }, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save_credentials)) }
                    } else {
                        Text(stringResource(R.string.credentials_saved), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FilledTonalButton(onClick = { replacing = true }, shape = RoundedCornerShape(16.dp)) { Text(stringResource(R.string.replace_credentials)) }
                    }
                    status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            item {
                SettingsSection(stringResource(R.string.personalization), Icons.Rounded.Palette) {
                    Text(stringResource(R.string.theme), fontWeight = FontWeight.SemiBold)
                    PaletteGrid(palette, onPalette)
                    Text(stringResource(R.string.appearance), fontWeight = FontWeight.SemiBold)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        Appearance.entries.forEachIndexed { index, a ->
                            SegmentedButton(selected = appearance == a, onClick = { onAppearance(a) }, shape = SegmentedButtonDefaults.itemShape(index, Appearance.entries.size)) {
                                Text(when(a) { Appearance.SYSTEM -> stringResource(R.string.system); Appearance.LIGHT -> stringResource(R.string.light); Appearance.DARK -> stringResource(R.string.dark) })
                            }
                        }
                    }
                }
            }
            item {
                SettingsSection(stringResource(R.string.language), Icons.Rounded.Language) {
                    val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("system","en","de").forEachIndexed { index, tag ->
                            val selected = (tag == "system" && tags.isBlank()) || tags.startsWith(tag)
                            SegmentedButton(selected = selected, onClick = {
                                AppCompatDelegate.setApplicationLocales(if (tag == "system") LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
                            }, shape = SegmentedButtonDefaults.itemShape(index, 3)) {
                                Text(when(tag) { "en" -> stringResource(R.string.english); "de" -> stringResource(R.string.german); else -> stringResource(R.string.system) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            content()
        }
    }
}

@Composable
private fun PaletteGrid(selected: Palette, onPalette: (Palette) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Palette.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { p ->
                    FilterChip(
                        selected = selected == p, onClick = { onPalette(p) },
                        label = { Text(when(p) {
                            Palette.DYNAMIC -> stringResource(R.string.dynamic); Palette.OCEAN -> stringResource(R.string.ocean); Palette.FOREST -> stringResource(R.string.forest)
                            Palette.SUNSET -> stringResource(R.string.sunset); Palette.AURORA -> stringResource(R.string.aurora); Palette.LAVENDER -> stringResource(R.string.lavender); Palette.GRAPHITE -> stringResource(R.string.graphite)
                        }) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BucketDetail(bucket: Bucket, gradient: List<Color>, onBack: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as HosterApp
    var files by remember(bucket.id) { mutableStateOf(app.buckets.files(bucket.id)) }
    var editing by remember { mutableStateOf<File?>(null) }
    var editText by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<File?>(null) }
    var deleting by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        files = app.buckets.files(bucket.id)
        onChanged()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            runCatching { app.buckets.import(bucket.id, uri, displayName(context, uri) ?: "asset") }
                .onFailure { error = it.message }
        }
        refresh()
    }

    editing?.let { file ->
        val rel = file.relativeTo(app.buckets.directory(bucket.id)).path
        FileEditorScreen(
            fileName = rel,
            initialValue = editText,
            onBack = { editing = null },
            onSave = { currentText ->
                runCatching { app.buckets.writeText(bucket.id, rel, currentText) }
                    .onSuccess { editing = null; refresh() }
                    .onFailure { error = it.message }
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(bucket.name, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
                    Column(
                        Modifier.background(Brush.linearGradient(gradient)).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                bucket.name,
                                color = Color.White,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            SolidToggle(bucket.enabled, true) {
                                app.buckets.toggle(bucket.id, it)
                                onChanged()
                            }
                        }
                        val target = "127.0.0.1:${bucket.port}"
                        Text(
                            target,
                            color = Color.White.copy(alpha = .85f),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { copyToClipboard(context, "Pangolin target", target) }
                            )
                        )
                        Text(
                            "${stringResource(R.string.port)}: ${bucket.port}",
                            color = Color.White.copy(alpha = .85f),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { copyToClipboard(context, "port", bucket.port.toString()) }
                            )
                        )
                        Text("${stringResource(R.string.traffic)} · ${formatBytes(bucket.bytesServed)}", color = Color.White)
                    }
                }
            }
            item {
                Button(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Icon(Icons.Rounded.UploadFile, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.import_assets))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            items(files, key = { it.absolutePath }) { file ->
                val rel = file.relativeTo(app.buckets.directory(bucket.id)).path
                ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(rel, fontWeight = FontWeight.SemiBold)
                            Text(
                                formatBytes(file.length()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (file.extension.lowercase() in setOf("html", "htm", "css", "js", "mjs", "csv", "txt", "json", "md")) {
                            IconButton(onClick = {
                                runCatching { app.buckets.readText(bucket.id, rel) }
                                    .onSuccess { editText = it; editing = file }
                                    .onFailure { error = it.message }
                            }) {
                                Icon(Icons.Rounded.Edit, stringResource(R.string.edit))
                            }
                        }
                        IconButton(onClick = { renaming = file }) {
                            Icon(Icons.Rounded.DriveFileRenameOutline, stringResource(R.string.rename))
                        }
                        IconButton(onClick = { deleting = file }) {
                            Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.delete_file))
                        }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { app.buckets.delete(bucket.id); onBack() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(stringResource(R.string.delete_bucket))
                }
            }
        }
    }

    renaming?.let { file ->
        val rel = file.relativeTo(app.buckets.directory(bucket.id)).path
        RenameFileDialog(
            currentName = file.name,
            onDismiss = { renaming = null },
            onRename = { newName ->
                runCatching { app.buckets.renameFile(bucket.id, rel, newName) }
                    .onSuccess { renaming = null; refresh() }
                    .onFailure { error = it.message }
            }
        )
    }

    deleting?.let { file ->
        val rel = file.relativeTo(app.buckets.directory(bucket.id)).path
        DeleteFileDialog(
            fileName = rel,
            onDismiss = { deleting = null },
            onDelete = {
                runCatching { app.buckets.deleteFile(bucket.id, rel) }
                    .onSuccess { deleting = null; refresh() }
                    .onFailure { error = it.message }
            }
        )
    }
}

@Composable
private fun FileEditorScreen(
    fileName: String,
    initialValue: String,
    onBack: () -> Unit,
    onSave: (String) -> Unit
) {
    BackHandler(onBack = onBack)
    val editorRef = remember { arrayOfNulls<EditText>(1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.editor), fontWeight = FontWeight.Bold)
                        Text(fileName, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 1)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = { editorRef[0]?.text?.toString()?.let(onSave) },
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.Save, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.save))
                    }
                    Spacer(Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .imePadding(),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .48f)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(6.dp),
                factory = { ctx ->
                    EditText(ctx).apply {
                        editorRef[0] = this
                        setText(initialValue)
                        setSelection(0)
                        typeface = Typeface.MONOSPACE
                        textSize = 14f
                        gravity = Gravity.TOP or Gravity.START
                        setPadding(14, 14, 14, 14)
                        setHorizontallyScrolling(true)
                        isSingleLine = false
                        inputType = InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                        setSelectAllOnFocus(false)
                        isSaveEnabled = false
                    }
                },
                update = { editorRef[0] = it }
            )
        }
    }
}

@Composable
private fun RenameFileDialog(currentName: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        icon = { Icon(Icons.Rounded.DriveFileRenameOutline, null) },
        title = { Text(stringResource(R.string.rename_file)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(120) },
                label = { Text(stringResource(R.string.file_name)) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp)
            )
        },
        confirmButton = {
            Button(onClick = { onRename(name) }, enabled = name.isNotBlank() && name != currentName) {
                Text(stringResource(R.string.rename))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun DeleteFileDialog(fileName: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        icon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.delete_file)) },
        text = { Text(stringResource(R.string.delete_file_confirm, fileName)) },
        confirmButton = {
            Button(
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun DebugDialog(lines: List<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val logText = lines.joinToString("\n")
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        icon = { Icon(Icons.Rounded.BugReport, null) },
        title = { Text(stringResource(R.string.connection_debug)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.connection_debug_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 440.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f)
                ) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            if (logText.isBlank()) stringResource(R.string.connection_debug_empty) else logText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(scrollState)
                                .padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { copyToClipboard(context, "Connection debug log", logText) },
                enabled = logText.isNotBlank()
            ) {
                Icon(Icons.Rounded.ContentCopy, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.copy))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}

@Composable
private fun TimerDialog(timer: String, onTimer: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember(timer) { mutableStateOf(timer) }
    AlertDialog(onDismissRequest = onDismiss, shape = RoundedCornerShape(28.dp), icon = { Icon(Icons.Rounded.Timer, null) }, title = { Text(stringResource(R.string.timer)) },
        text = { OutlinedTextField(value, { if (it.all(Char::isDigit)) value = it.take(6) }, label = { Text(stringResource(R.string.timer_minutes)) }, singleLine = true, shape = RoundedCornerShape(18.dp)) },
        confirmButton = { Button(onClick = { onTimer(value.ifBlank { "60" }); onDismiss() }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun CreateBucketDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, shape = RoundedCornerShape(28.dp), icon = { Icon(Icons.Rounded.CreateNewFolder, null) }, title = { Text(stringResource(R.string.new_bucket)) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.bucket_name)) }, singleLine = true, shape = RoundedCornerShape(18.dp)) },
        confirmButton = { Button(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

private fun displayName(context: Context, uri: android.net.Uri): String? {
    var cursor: Cursor? = null
    return try {
        cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null
    } finally { cursor?.close() }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0; var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}


private fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    if (Build.VERSION.SDK_INT <= 32) {
        Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }
}
