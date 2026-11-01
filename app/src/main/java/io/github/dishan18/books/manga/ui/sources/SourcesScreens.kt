package io.github.dishan18.books.manga.ui.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.dishan18.books.manga.MangaModule
import io.github.dishan18.books.manga.source.KomgaAuth
import io.github.dishan18.books.manga.source.KomgaSettings
import io.github.dishan18.books.manga.source.MangaDexSettings
import io.github.dishan18.books.manga.source.SourceRegistry
import io.github.dishan18.books.manga.source.SourceType
import io.github.dishan18.books.manga.source.api.sourceError
import io.github.dishan18.books.manga.ui.common.mangaViewModel
import io.github.dishan18.books.manga.ui.home.icon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class SourcesViewModel(private val module: MangaModule) : ViewModel() {
    val entries: StateFlow<List<SourceRegistry.Entry>> = module.sources.entries

    fun setEnabled(id: String, enabled: Boolean) = viewModelScope.launch { module.sources.setEnabled(id, enabled) }

    fun mangaDexSettings(entry: SourceRegistry.Entry) = module.sources.mangaDexSettings(entry.config)

    fun saveMangaDex(settings: MangaDexSettings) = viewModelScope.launch { module.sources.updateMangaDex(settings) }
}

@Composable
fun SourcesScreen(onBack: () -> Unit, onAddKomga: () -> Unit, onEditKomga: (String) -> Unit) {
    val viewModel = mangaViewModel { SourcesViewModel(it) }
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    var editingMangaDex by remember { mutableStateOf<SourceRegistry.Entry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sources") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddKomga,
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text("Add Komga server") },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    "Sources only use their official APIs or servers you run. Turning one off hides it from Browse; series already in your library keep working.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(entries, key = { it.id }) { entry ->
                val problem = entry.loadError ?: entry.config.lastError
                ListItem(
                    leadingContent = { Icon(entry.type.icon(), null) },
                    headlineContent = { Text(entry.config.name) },
                    supportingContent = {
                        Text(
                            problem ?: entry.type?.label.orEmpty(),
                            color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Row {
                            when (entry.type) {
                                SourceType.MANGADEX -> IconButton(onClick = { editingMangaDex = entry }) { Icon(Icons.Outlined.Settings, "MangaDex settings") }
                                SourceType.KOMGA -> IconButton(onClick = { onEditKomga(entry.id) }) { Icon(Icons.Outlined.Settings, "Edit server") }
                                else -> Unit
                            }
                            Switch(checked = entry.config.enabled, onCheckedChange = { viewModel.setEnabled(entry.id, it) })
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }

    editingMangaDex?.let { entry ->
        MangaDexSettingsSheet(
            initial = viewModel.mangaDexSettings(entry),
            onDismiss = { editingMangaDex = null },
            onSave = {
                viewModel.saveMangaDex(it)
                editingMangaDex = null
            },
        )
    }
}

private val MANGADEX_LANGUAGES = listOf(
    "en" to "English", "es-la" to "Spanish (Latin America)", "es" to "Spanish", "pt-br" to "Portuguese (Brazil)",
    "fr" to "French", "de" to "German", "it" to "Italian", "ru" to "Russian", "id" to "Indonesian",
    "vi" to "Vietnamese", "tr" to "Turkish", "pl" to "Polish", "th" to "Thai", "ar" to "Arabic",
    "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese (Simplified)", "zh-hk" to "Chinese (Traditional)",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MangaDexSettingsSheet(initial: MangaDexSettings, onDismiss: () -> Unit, onSave: (MangaDexSettings) -> Unit) {
    var languages by remember { mutableStateOf(initial.languages.toSet()) }
    var ratings by remember { mutableStateOf(initial.contentRatings.toSet()) }
    var dataSaver by remember { mutableStateOf(initial.dataSaver) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("MangaDex", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Text("Chapter languages", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (MANGADEX_LANGUAGES.map { it.first } + languages).distinct().forEach { code ->
                    val label = MANGADEX_LANGUAGES.firstOrNull { it.first == code }?.second ?: code
                    FilterChip(
                        selected = code in languages,
                        onClick = { languages = if (code in languages) languages - code else languages + code },
                        label = { Text(label) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Content ratings shown", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MangaDexSettings.ALL_CONTENT_RATINGS.forEach { rating ->
                    FilterChip(
                        selected = rating in ratings,
                        onClick = { ratings = if (rating in ratings) ratings - rating else ratings + rating },
                        label = { Text(rating.replaceFirstChar { it.uppercase() } + if (rating == "erotica" || rating == "pornographic") " (18+)" else "") },
                    )
                }
            }
            ListItem(
                headlineContent = { Text("Data saver") },
                supportingContent = { Text("Smaller, compressed images. Uses less mobile data.") },
                trailingContent = { Switch(checked = dataSaver, onCheckedChange = { dataSaver = it }) },
                modifier = Modifier.clickable { dataSaver = !dataSaver },
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onSave(MangaDexSettings(languages.toList().ifEmpty { listOf("en") }, ratings.toList().ifEmpty { listOf("safe") }, dataSaver)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

data class KomgaForm(
    val name: String = "Komga",
    val url: String = "http://",
    val auth: KomgaAuth = KomgaAuth.BASIC,
    val username: String = "",
    val secret: String = "",
    val hasSavedSecret: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

class KomgaEditViewModel(private val module: MangaModule, private val sourceId: String?) : ViewModel() {
    private val _form = MutableStateFlow(KomgaForm())
    val form: StateFlow<KomgaForm> = _form.asStateFlow()
    val isEditing = sourceId != null

    init {
        if (sourceId != null) {
            viewModelScope.launch {
                val entry = module.sources.entries.value.firstOrNull { it.id == sourceId }
                val settings = entry?.let { module.sources.komgaSettings(it.config) } ?: return@launch
                _form.value = KomgaForm(
                    name = entry.config.name,
                    url = settings.baseUrl,
                    auth = settings.auth,
                    username = settings.username.orEmpty(),
                    hasSavedSecret = module.sources.hasSecret(sourceId),
                )
            }
        }
    }

    fun update(change: (KomgaForm) -> KomgaForm) {
        _form.value = change(_form.value).copy(error = null)
    }

    /** True when the address isn't HTTPS and isn't on a private network, so traffic could be read. */
    fun isInsecure(url: String): Boolean {
        val parsed = url.trim().toHttpUrlOrNull() ?: return false
        if (parsed.isHttps) return false
        val host = parsed.host
        return !(host == "localhost" || host.endsWith(".local") || host.startsWith("10.") || host.startsWith("192.168.") ||
            Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(host))
    }

    fun save(onSaved: () -> Unit) {
        val form = _form.value
        val url = form.url.trim().trimEnd('/')
        if (url.toHttpUrlOrNull() == null) {
            _form.value = form.copy(error = "Enter the server address, e.g. http://192.168.1.20:25600")
            return
        }
        val settings = KomgaSettings(baseUrl = url, auth = form.auth, username = form.username.takeIf { form.auth == KomgaAuth.BASIC })
        // Editing with an empty secret field keeps the stored one.
        val secret = form.secret.takeIf { it.isNotEmpty() && form.auth != KomgaAuth.NONE }
        val secretForTest = secret ?: if (form.hasSavedSecret && sourceId != null) module.credentials.get(sourceId) else null
        viewModelScope.launch {
            _form.value = form.copy(busy = true, error = null)
            val test = module.sources.testKomga(settings, secretForTest)
            if (test.isFailure) {
                _form.value = form.copy(busy = false, error = "Couldn't connect: ${test.exceptionOrNull()!!.sourceError.message}")
                return@launch
            }
            module.sources.saveKomga(sourceId, form.name, settings, if (form.auth == KomgaAuth.NONE) null else secret)
            onSaved()
        }
    }

    fun remove(onRemoved: () -> Unit) {
        val id = sourceId ?: return
        viewModelScope.launch {
            module.sources.remove(id)
            onRemoved()
        }
    }
}

@Composable
fun KomgaEditScreen(sourceId: String?, onDone: () -> Unit) {
    val viewModel = mangaViewModel(key = "komga-${sourceId ?: "new"}") { KomgaEditViewModel(it, sourceId) }
    val form by viewModel.form.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isEditing) "Edit Komga server" else "Add Komga server") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Komga is a free server you run at home for your own comics and manga. Books connects to it directly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(form.name, { v -> viewModel.update { it.copy(name = v) } }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                form.url,
                { v -> viewModel.update { it.copy(url = v) } },
                label = { Text("Server address") },
                placeholder = { Text("http://192.168.1.20:25600") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = if (viewModel.isInsecure(form.url)) {
                    { Text("This address isn't HTTPS and isn't on your home network, so your login could be read by others.", color = MaterialTheme.colorScheme.error) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Sign-in", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val options = listOf(KomgaAuth.NONE to "None", KomgaAuth.BASIC to "Password", KomgaAuth.API_KEY to "API key")
                options.forEachIndexed { index, (auth, label) ->
                    SegmentedButton(
                        selected = form.auth == auth,
                        onClick = { viewModel.update { it.copy(auth = auth) } },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) { Text(label) }
                }
            }
            if (form.auth == KomgaAuth.BASIC) {
                OutlinedTextField(form.username, { v -> viewModel.update { it.copy(username = v) } }, label = { Text("Username or email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            if (form.auth != KomgaAuth.NONE) {
                OutlinedTextField(
                    form.secret,
                    { v -> viewModel.update { it.copy(secret = v) } },
                    label = { Text(if (form.auth == KomgaAuth.BASIC) "Password" else "API key") },
                    placeholder = if (form.hasSavedSecret) ({ Text("Saved — leave empty to keep") }) else null,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = { Text("Stored encrypted on this phone only.") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            form.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = { viewModel.save(onDone) }, enabled = !form.busy, modifier = Modifier.fillMaxWidth()) {
                if (form.busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp) else Text("Test & save")
            }
            if (viewModel.isEditing) {
                OutlinedButton(onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove server", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${form.name}?") },
            text = { Text("Series from this server stay in your library but can't load new pages until you add it again.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; viewModel.remove(onDone) }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}
