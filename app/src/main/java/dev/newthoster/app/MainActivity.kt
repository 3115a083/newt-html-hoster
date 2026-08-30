package dev.newthoster.app

import android.Manifest
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
        }
        setContent { HosterRoot() }
    }
}

private enum class Palette { DYNAMIC, OCEAN, FOREST, SUNSET }
private enum class Appearance { SYSTEM, LIGHT, DARK }

@Composable
private fun HosterRoot() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var palette by remember { mutableStateOf(runCatching { Palette.valueOf(prefs.getString("palette", "DYNAMIC")!!) }.getOrDefault(Palette.DYNAMIC)) }
    var appearance by remember { mutableStateOf(runCatching { Appearance.valueOf(prefs.getString("appearance", "SYSTEM")!!) }.getOrDefault(Appearance.SYSTEM)) }
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val dark = when (appearance) { Appearance.SYSTEM -> systemDark; Appearance.LIGHT -> false; Appearance.DARK -> true }
    val colors = when {
        palette == Palette.DYNAMIC && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        palette == Palette.FOREST -> if (dark) darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF8CD5A4), secondary = androidx.compose.ui.graphics.Color(0xFFB7CCB9)) else lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF176B42), secondary = androidx.compose.ui.graphics.Color(0xFF466452))
        palette == Palette.SUNSET -> if (dark) darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFFFFB59A), secondary = androidx.compose.ui.graphics.Color(0xFFE6BDB0)) else lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF9B452A), secondary = androidx.compose.ui.graphics.Color(0xFF76574D))
        else -> if (dark) darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF72D7D1), secondary = androidx.compose.ui.graphics.Color(0xFFB2CCCA)) else lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF006A67), secondary = androidx.compose.ui.graphics.Color(0xFF4A6361))
    }
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize()) {
            MainScreen(
                palette = palette,
                appearance = appearance,
                onPalette = {
                    palette = it
                    prefs.edit().putString("palette", it.name).apply()
                },
                onAppearance = {
                    appearance = it
                    prefs.edit().putString("appearance", it.name).apply()
                }
            )
        }
    }
}

@Composable
private fun MainScreen(
    palette: Palette,
    appearance: Appearance,
    onPalette: (Palette) -> Unit,
    onAppearance: (Appearance) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as HosterApp
    val runtime by RuntimeBus.state.collectAsStateWithLifecycle()
    var buckets by remember { mutableStateOf(app.buckets.list()) }
    var selected by remember { mutableStateOf<Bucket?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var timer by remember { mutableStateOf("60") }

    fun refresh() { buckets = app.buckets.list(); selected = selected?.let { s -> buckets.firstOrNull { it.id == s.id } } }

    if (selected != null) {
        BucketDetail(selected!!, onBack = { selected = null; refresh() }, onChanged = { refresh() })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.title), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.subtitle), style = MaterialTheme.typography.labelMedium)
                    }
                },
                actions = { TextButton(onClick = { showSettings = true }) { Text(stringResource(R.string.settings)) } }
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { showCreate = true }) { Text("+") } }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item {
                RuntimeCard(runtime, timer, onTimer = { timer = it }, hasSecret = app.vault.hasSecret(),
                    onStart = {
                        val mins = timer.toLongOrNull()?.coerceIn(1, 10080) ?: 60
                        ContextCompat.startForegroundService(context, Intent(context, NewtHostService::class.java).setAction(NewtHostService.ACTION_START).putExtra(NewtHostService.EXTRA_MINUTES, mins))
                    },
                    onStop = { context.startService(Intent(context, NewtHostService::class.java).setAction(NewtHostService.ACTION_STOP)) })
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.buckets), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Text("${buckets.size}", style = MaterialTheme.typography.labelLarge)
                }
            }
            items(buckets, key = { it.id }) { bucket ->
                ElevatedCard(onClick = { selected = bucket }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(bucket.name, style = MaterialTheme.typography.titleMedium)
                                Text("/b/${bucket.id}/", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = bucket.enabled, onCheckedChange = { app.buckets.toggle(bucket.id, it); refresh() })
                        }
                        Text("${stringResource(R.string.traffic)}: ${formatBytes(bucket.bytesServed)}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item { Text(stringResource(R.string.security_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showCreate = false }, title = { Text(stringResource(R.string.new_bucket)) },
            text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.bucket_name)) }, singleLine = true) },
            confirmButton = { Button(onClick = { app.buckets.create(name); refresh(); showCreate = false }) { Text(stringResource(R.string.create)) } },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text(stringResource(R.string.cancel)) } })
    }

    if (showSettings) {
        SettingsDialog(app, palette, appearance, onPalette, onAppearance, onDismiss = { showSettings = false })
    }
}

@Composable
private fun RuntimeCard(
    runtime: RuntimeState,
    timer: String,
    onTimer: (String) -> Unit,
    hasSecret: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.runtime), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                AssistChip(onClick = {}, label = { Text(if (runtime.connected) stringResource(R.string.connected) else runtime.status) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Metric(stringResource(R.string.newt_version), runtime.newtVersion)
                Metric(stringResource(R.string.link_strength), if (runtime.linkMbps > 0) "${runtime.linkMbps} Mbps" else "—")
            }
            OutlinedTextField(value = timer, onValueChange = { if (it.all(Char::isDigit)) onTimer(it.take(6)) }, label = { Text(stringResource(R.string.timer_minutes)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = if (runtime.running) onStop else onStart, enabled = runtime.running || hasSecret, modifier = Modifier.fillMaxWidth()) {
                Text(if (runtime.running) stringResource(R.string.stop_hosting) else stringResource(R.string.start_hosting))
            }
        }
    }
}

@Composable private fun Metric(label: String, value: String) {
    Column { Text(label, style = MaterialTheme.typography.labelMedium); Text(value, fontWeight = FontWeight.Medium) }
}

@Composable
private fun SettingsDialog(
    app: HosterApp,
    palette: Palette,
    appearance: Appearance,
    onPalette: (Palette) -> Unit,
    onAppearance: (Appearance) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(app.vault.hasSecret()) }
    var replacing by remember { mutableStateOf(false) }
    var endpoint by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var updateStatus by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!saved || replacing) {
                    OutlinedTextField(endpoint, { endpoint = it }, label = { Text(stringResource(R.string.pangolin_endpoint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(id, { id = it }, label = { Text(stringResource(R.string.newt_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(secret, { secret = it }, label = { Text(stringResource(R.string.newt_secret)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(enabled = endpoint.startsWith("https://") && id.isNotBlank() && secret.isNotBlank(), onClick = {
                        val secretChars = secret.toCharArray()
                        secret = ""
                        scope.launch {
                            status = "Checking TLS…"
                            val result = runCatching {
                                val pin = withContext(Dispatchers.IO) { TlsPin.fetchSpkiSha256(endpoint) }
                                app.vault.save(endpoint, id, secretChars, pin)
                                secretChars.fill('\u0000')
                            }
                            if (result.isSuccess) {
                                saved = true; replacing = false; endpoint = ""; id = ""
                                status = null
                            } else {
                                secretChars.fill('\u0000')
                                status = result.exceptionOrNull()?.message
                            }
                        }
                    }) { Text(stringResource(R.string.save_credentials)) }
                } else {
                    Text(stringResource(R.string.credentials_saved))
                    OutlinedButton(onClick = { replacing = true }) { Text(stringResource(R.string.replace_credentials)) }
                }
                status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider()
                Text(stringResource(R.string.theme), fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Palette.entries.forEach { p ->
                        FilterChip(selected = palette == p, onClick = { onPalette(p) }, label = { Text(when(p) {
                            Palette.DYNAMIC -> stringResource(R.string.dynamic)
                            Palette.OCEAN -> stringResource(R.string.ocean)
                            Palette.FOREST -> stringResource(R.string.forest)
                            Palette.SUNSET -> stringResource(R.string.sunset)
                        }) })
                    }
                }
                Text(stringResource(R.string.appearance), fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Appearance.entries.forEach { a ->
                        FilterChip(selected = appearance == a, onClick = { onAppearance(a) }, label = { Text(when(a) {
                            Appearance.SYSTEM -> stringResource(R.string.system)
                            Appearance.LIGHT -> stringResource(R.string.light)
                            Appearance.DARK -> stringResource(R.string.dark)
                        }) })
                    }
                }
                Text(stringResource(R.string.language), fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) }, label = { Text(stringResource(R.string.english)) })
                    AssistChip(onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("de")) }, label = { Text(stringResource(R.string.german)) })
                    AssistChip(onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList()) }, label = { Text(stringResource(R.string.system)) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}

@Composable
private fun BucketDetail(bucket: Bucket, onBack: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as HosterApp
    var files by remember(bucket.id) { mutableStateOf(app.buckets.files(bucket.id)) }
    var editing by remember { mutableStateOf<File?>(null) }
    var editText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() { files = app.buckets.files(bucket.id); onChanged() }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            val name = displayName(context, uri) ?: "asset"
            runCatching { app.buckets.import(bucket.id, uri, name) }.onFailure { error = it.message }
        }
        refresh()
    }

    Scaffold(topBar = {
        TopAppBar(title = { Column { Text(bucket.name); Text("/b/${bucket.id}/", style = MaterialTheme.typography.labelSmall) } },
            navigationIcon = { TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.back)) } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.enabled), modifier = Modifier.weight(1f))
                    Switch(bucket.enabled, onCheckedChange = { app.buckets.toggle(bucket.id, it); onChanged() })
                }
                Text("${stringResource(R.string.traffic)}: ${formatBytes(bucket.bytesServed)}")
                Button(onClick = { importLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.import_assets)) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            item { Text(stringResource(R.string.assets), style = MaterialTheme.typography.titleLarge) }
            items(files, key = { it.absolutePath }) { file ->
                val rel = file.relativeTo(app.buckets.directory(bucket.id)).path
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(rel, fontWeight = FontWeight.Medium)
                            Text(formatBytes(file.length()), style = MaterialTheme.typography.bodySmall)
                        }
                        if (file.extension.lowercase() in setOf("html","htm","css","js","mjs","csv","txt","json","md")) {
                            TextButton(onClick = {
                                runCatching { app.buckets.readText(bucket.id, rel) }
                                    .onSuccess { editText = it; editing = file }
                                    .onFailure { error = it.message }
                            }) { Text(stringResource(R.string.edit)) }
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = {
                    app.buckets.delete(bucket.id)
                    onBack()
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.delete)) }
            }
        }
    }

    if (editing != null) {
        val rel = editing!!.relativeTo(app.buckets.directory(bucket.id)).path
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(rel) },
            text = { OutlinedTextField(editText, { editText = it }, modifier = Modifier.fillMaxWidth().height(360.dp), textStyle = LocalTextStyle.current.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) },
            confirmButton = { Button(onClick = {
                runCatching { app.buckets.writeText(bucket.id, rel, editText) }.onFailure { error = it.message }
                editing = null; refresh()
            }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } })
    }
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
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}
