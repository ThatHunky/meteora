package dev.thathunky.meteora.ui.filter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thathunky.meteora.R
import dev.thathunky.meteora.filter.ArtistRule
import dev.thathunky.meteora.filter.ContentFilter
import dev.thathunky.meteora.filter.CustomRule
import dev.thathunky.meteora.filter.Decision
import dev.thathunky.meteora.filter.FilterSettings
import dev.thathunky.meteora.filter.FilterStore
import dev.thathunky.meteora.filter.LanguageGuess
import dev.thathunky.meteora.filter.Place
import dev.thathunky.meteora.filter.Script
import dev.thathunky.meteora.filter.Subject
import dev.thathunky.meteora.filter.Verdict
import dev.thathunky.meteora.filter.subtitleParts
import dev.thathunky.meteora.ui.components.ButtonKind
import dev.thathunky.meteora.ui.components.Choice
import dev.thathunky.meteora.ui.components.MButton
import dev.thathunky.meteora.ui.components.MIconButton
import dev.thathunky.meteora.ui.components.MRow
import dev.thathunky.meteora.ui.components.SegmentedChoice
import dev.thathunky.meteora.ui.components.screenTitleStyle
import dev.thathunky.meteora.ui.settings.SettingsGroupTitle
import dev.thathunky.meteora.ui.settings.SettingsNote
import dev.thathunky.meteora.ui.settings.SettingsSwitchRow
import dev.thathunky.meteora.ui.theme.LocalLook
import dev.thathunky.meteora.ytm.ArtistRef
import javax.inject.Inject

@HiltViewModel
class FiltersViewModel @Inject constructor(private val store: FilterStore, private val filter: ContentFilter) : ViewModel() {
    val settings = store.settings

    fun setLanguage(id: String, v: Verdict?) = store.update { it.copy(languages = it.languages.with(id, v)) }
    fun setScript(s: Script, v: Verdict?) = store.update { it.copy(scripts = it.scripts.with(s, v)) }
    fun setCountry(code: String, v: Verdict?) = store.update { it.copy(countries = it.countries.with(code, v)) }
    fun setPlace(p: Place, on: Boolean) = store.update { it.copy(placesOff = if (on) it.placesOff - p else it.placesOff + p) }
    fun setMusicBrainz(on: Boolean) = store.update { it.copy(musicBrainz = on) }

    fun setArtist(rule: ArtistRule, v: Verdict?) = store.update { s ->
        val rest = s.artists.filterNot { it == rule }
        s.copy(artists = if (v == null) rest else s.artists.map { if (it == rule) it.copy(verdict = v) else it })
    }

    fun addArtist(name: String) {
        if (name.isNotBlank()) store.setArtist(ArtistRef(name.trim(), null), Verdict.Hide)
    }

    fun setCustom(rule: CustomRule, v: Verdict?) = store.update { s ->
        s.copy(custom = if (v == null) s.custom.filterNot { it == rule } else s.custom.map { if (it == rule) it.copy(verdict = v) else it })
    }

    fun addCustom(text: String) = store.update { s ->
        val t = text.trim()
        if (t.isEmpty() || s.custom.any { it.text.equals(t, ignoreCase = true) }) s else s.copy(custom = s.custom + CustomRule(t, Verdict.Hide))
    }

    /** What the filter would do with this title and artist, wherever it applies. */
    fun test(title: String, artist: String): Decision =
        filter.decide(Subject(title, subtitleParts(artist).map { ArtistRef(it, null) }))

    private fun <K> Map<K, Verdict>.with(key: K, v: Verdict?): Map<K, Verdict> = if (v == null) this - key else this + (key to v)
}

/** Settings → Filters: every rule list, where the filter applies, and a Test field. */
@Composable
fun FiltersSettingsScreen(onBack: () -> Unit, bottomPadding: Dp, vm: FiltersViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            MIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back), onBack, text = stringResource(R.string.action_back))
            Text(stringResource(R.string.filters_title), style = screenTitleStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = LocalLook.current.screenPadding).padding(bottom = 16.dp + bottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsNote(stringResource(R.string.filters_intro))
            TestSection(vm, s)
            PlacesSection(vm, s)
            ArtistsSection(vm, s)
            CustomSection(vm, s)
            CountriesSection(vm, s)
            LanguagesSection(vm, s)
            ScriptsSection(vm, s)
            SettingsNote(stringResource(R.string.filters_limit))
        }
    }
}

/** The line under Settings → Filters in the top-level list. */
@Composable
fun filtersSummary(vm: FiltersViewModel = hiltViewModel()): String {
    val s by vm.settings.collectAsStateWithLifecycle()
    val n = s.artists.size + s.custom.size + s.countries.size + s.languages.size + s.scripts.size
    return if (n == 0) stringResource(R.string.filters_summary_none) else pluralStringResource(R.plurals.filters_summary_rules, n, n)
}

@Composable
private fun TestSection(vm: FiltersViewModel, s: FilterSettings) {
    SettingsGroupTitle(stringResource(R.string.filters_test), first = true)
    val title = rememberTextFieldState()
    val artist = rememberTextFieldState()
    OutlinedTextField(state = title, label = { Text(stringResource(R.string.filters_test_title)) }, lineLimits = TextFieldLineLimits.SingleLine, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(state = artist, label = { Text(stringResource(R.string.filters_test_artist)) }, lineLimits = TextFieldLineLimits.SingleLine, modifier = Modifier.fillMaxWidth())
    val t = title.text.toString()
    val a = artist.text.toString()
    if (t.isBlank() && a.isBlank()) {
        SettingsNote(stringResource(R.string.filters_test_empty))
        return
    }
    val decision = remember(t, a, s) { vm.test(t, a) }
    Text(describe(decision), style = MaterialTheme.typography.bodyLarge)
    val pieces = listOf(t) + subtitleParts(a)
    val langs = pieces.flatMap { LanguageGuess.languages(it) }.distinct().map { languageName(it) }
    val scripts = pieces.flatMap { LanguageGuess.scripts(it) }.distinct().sorted().map { stringResource(scriptName(it)) }
    val looks = (langs + scripts)
    SettingsNote(if (looks.isEmpty()) stringResource(R.string.filters_test_looks_nothing) else stringResource(R.string.filters_test_looks, looks.joinToString(" · ")))
}

@Composable
private fun PlacesSection(vm: FiltersViewModel, s: FilterSettings) {
    SettingsGroupTitle(stringResource(R.string.filters_where))
    SettingsSwitchRow(stringResource(R.string.filters_place_home), s.appliesTo(Place.Home), { vm.setPlace(Place.Home, it) })
    SettingsSwitchRow(stringResource(R.string.filters_place_radio), s.appliesTo(Place.Radio), { vm.setPlace(Place.Radio, it) }, supporting = stringResource(R.string.filters_place_radio_note))
    SettingsSwitchRow(stringResource(R.string.filters_place_artist), s.appliesTo(Place.ArtistShelves), { vm.setPlace(Place.ArtistShelves, it) }, supporting = stringResource(R.string.filters_place_artist_note))
    SettingsSwitchRow(stringResource(R.string.filters_place_downloads), s.appliesTo(Place.SmartDownloads), { vm.setPlace(Place.SmartDownloads, it) })
}

/** Hide / Always show, for a rule that exists; removing it is the row's close button. */
@Composable
private fun HideOrAlways(v: Verdict, onChange: (Verdict) -> Unit) = SegmentedChoice(
    options = listOf(Choice(Verdict.Hide, stringResource(R.string.filters_hide)), Choice(Verdict.AlwaysShow, stringResource(R.string.filters_always))),
    selected = v,
    onSelect = onChange,
)

/** One existing rule: its name, a remove button, and Hide / Always show under it. */
@Composable
private fun RuleRow(name: String, verdict: Verdict, onChange: (Verdict?) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            MIconButton(Icons.Outlined.Close, stringResource(R.string.filters_remove), { onChange(null) }, text = stringResource(R.string.filters_remove))
        }
        HideOrAlways(verdict) { onChange(it) }
    }
}

/** A text field and an Add button; the new rule starts as Hide. */
@Composable
private fun AddRow(label: String, onAdd: (String) -> Unit) {
    val state = rememberTextFieldState()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(state = state, label = { Text(label) }, lineLimits = TextFieldLineLimits.SingleLine, modifier = Modifier.weight(1f))
        MButton(stringResource(R.string.filters_add), {
            onAdd(state.text.toString())
            state.clearText()
        }, kind = ButtonKind.Tonal, enabled = state.text.isNotBlank())
    }
}

@Composable
private fun ArtistsSection(vm: FiltersViewModel, s: FilterSettings) {
    SettingsGroupTitle(stringResource(R.string.filters_artists))
    if (s.artists.isEmpty()) SettingsNote(stringResource(R.string.filters_artists_none))
    s.artists.forEach { r -> RuleRow(r.name, r.verdict) { vm.setArtist(r, it) } }
    AddRow(stringResource(R.string.filters_artist_name), vm::addArtist)
}

@Composable
private fun CustomSection(vm: FiltersViewModel, s: FilterSettings) {
    SettingsGroupTitle(stringResource(R.string.filters_custom))
    SettingsNote(stringResource(R.string.filters_custom_note))
    s.custom.forEach { r -> RuleRow("“${r.text}”", r.verdict) { vm.setCustom(r, it) } }
    AddRow(stringResource(R.string.filters_custom_text), vm::addCustom)
}

@Composable
private fun CountriesSection(vm: FiltersViewModel, s: FilterSettings) {
    SettingsGroupTitle(stringResource(R.string.filters_countries))
    var picking by rememberSaveable { mutableStateOf(false) }
    if (s.countries.isEmpty()) SettingsNote(stringResource(R.string.filters_countries_none))
    s.countries.entries.sortedBy { it.key }.forEach { (code, v) -> RuleRow(countryName(code), v) { vm.setCountry(code, it) } }
    MButton(stringResource(R.string.filters_add_country), { picking = true }, kind = ButtonKind.Tonal)
    SettingsSwitchRow(
        stringResource(R.string.filters_musicbrainz), s.musicBrainz, vm::setMusicBrainz,
        supporting = stringResource(R.string.filters_musicbrainz_note),
    )
    if (picking) {
        CountryPicker(exclude = s.countries.keys, onPick = { vm.setCountry(it, Verdict.Hide); picking = false }, onDismiss = { picking = false })
    }
}

@Composable
private fun CountryPicker(exclude: Set<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val all = remember(locale) { allCountries(locale) }
    val query = rememberTextFieldState()
    val q = query.text.toString().trim()
    val shown = all.filter { (code, name) -> code !in exclude && (q.isEmpty() || name.contains(q, ignoreCase = true) || code.equals(q, ignoreCase = true)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.filters_add_country)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(state = query, label = { Text(stringResource(R.string.filters_search_country)) }, lineLimits = TextFieldLineLimits.SingleLine, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.first }) { (code, name) ->
                        // Plain text rows: a ListItem would paint its own surface over the dialog's.
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(code) }.heightIn(min = 48.dp).padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { MButton(stringResource(R.string.filters_cancel), onDismiss, kind = ButtonKind.Text) },
    )
}

/** A section that opens on a tap, for the long lists; the header says how many rules are set. */
@Composable
private fun Collapsible(title: String, setCount: Int, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(setCount > 0) }
    // A plain row, aligned with the page's content: MRow adds its own inset on top of the page's.
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { open = !open },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            SettingsGroupTitle(title)
            if (setCount > 0) SettingsNote(stringResource(R.string.filters_set_count, setCount))
        }
        Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
    }
    if (open) Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** Show / Hide / Always show: the three states of a language row. Show removes the rule. */
@Composable
private fun ThreeWay(name: String, v: Verdict?, onChange: (Verdict?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = MaterialTheme.typography.bodyLarge)
        SegmentedChoice(
            options = listOf(
                Choice<Verdict?>(null, stringResource(R.string.filters_show)),
                Choice(Verdict.Hide, stringResource(R.string.filters_hide)),
                Choice(Verdict.AlwaysShow, stringResource(R.string.filters_always)),
            ),
            selected = v,
            onSelect = onChange,
        )
    }
}

@Composable
private fun LanguagesSection(vm: FiltersViewModel, s: FilterSettings) {
    Collapsible(stringResource(R.string.filters_languages), s.languages.size) {
        LanguageGuess.LANGUAGE_IDS.forEach { id -> ThreeWay(languageName(id), s.languages[id]) { vm.setLanguage(id, it) } }
    }
}

@Composable
private fun ScriptsSection(vm: FiltersViewModel, s: FilterSettings) {
    Collapsible(stringResource(R.string.filters_scripts), s.scripts.size) {
        SettingsNote(stringResource(R.string.filters_scripts_note))
        Script.entries.forEach { sc ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(scriptName(sc)), style = MaterialTheme.typography.bodyLarge)
                SegmentedChoice(
                    options = listOf(Choice<Verdict?>(null, stringResource(R.string.filters_show)), Choice(Verdict.Hide, stringResource(R.string.filters_hide))),
                    selected = s.scripts[sc],
                    onSelect = { vm.setScript(sc, it) },
                )
            }
        }
    }
}
