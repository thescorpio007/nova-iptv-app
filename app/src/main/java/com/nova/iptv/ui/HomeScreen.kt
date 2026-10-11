package com.nova.iptv.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.iptv.data.DownloadStatus
import com.nova.iptv.data.EpgEntry
import com.nova.iptv.data.ItemType
import com.nova.iptv.data.LiveChannel
import com.nova.iptv.data.Movie
import com.nova.iptv.data.SavedItem
import com.nova.iptv.data.Series
import com.nova.iptv.data.XtreamApi
import com.nova.iptv.player.PlayItem
import kotlinx.coroutines.launch

typealias PlayFn = (items: List<PlayItem>, index: Int) -> Unit

@Composable
fun HomeScreen(vm: MainViewModel, isTv: Boolean, onPlay: PlayFn) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val detail by vm.seriesDetail.collectAsStateWithLifecycle()
    val movieDetail by vm.movieDetail.collectAsStateWithLifecycle()
    val edit by vm.editMode.collectAsStateWithLifecycle()
    val homeSettings by vm.settings.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? android.app.Activity
    val exitContext = LocalContext.current
    var lastBackAt by remember { mutableStateOf(0L) }

    // Main screen: Back twice closes Nova (declared first, so every other Back handler comes before it).
    BackHandler(enabled = homeSettings.doubleBackExit && tab == Tab.LIVE && detail == null && movieDetail == null && !edit) {
        val now = System.currentTimeMillis()
        if (now - lastBackAt < 2000) activity?.finish()
        else {
            lastBackAt = now
            Toast.makeText(exitContext, "Press Back again to close Nova", Toast.LENGTH_SHORT).show()
        }
    }
    BackHandler(enabled = detail != null && movieDetail == null) { vm.closeSeries() }
    BackHandler(enabled = movieDetail != null) { vm.closeMovie() }
    BackHandler(enabled = detail == null && edit) { vm.setEditMode(false) }
    // Films & series handle Back themselves (posters → groups → tabs) and never jump to Live TV.
    BackHandler(enabled = detail == null && !edit && tab != Tab.LIVE && tab != Tab.FILMS && tab != Tab.SERIES) { vm.setTab(Tab.LIVE) }
    val tabFocus = remember { FocusRequester() }
    var tabsHaveFocus by remember { mutableStateOf(false) }
    val tabs = TabFocus(
        focus = { runCatching { tabFocus.requestFocus() } },
        hasFocus = { tabsHaveFocus },
    )

    movieDetail?.let { md ->
        // The film page covers the whole screen, like the series page.
        MovieDetailScreen(vm, md, onPlay)
        return
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.onFocusChanged { tabsHaveFocus = it.hasFocus }) { TopBar(vm, tab, edit, isTv, tabFocus) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val d = detail
            if (d != null) {
                SeriesDetailScreen(vm, d, onPlay)
            } else {
                when (tab) {
                    Tab.LIVE -> ZoneLayout(vm, vm.live, showEpg = true) { wide -> LiveList(vm, onPlay, wide) }
                    Tab.FILMS -> ZoneLayout(vm, vm.films, showEpg = false, posters = true, tabs = tabs) { wide -> FilmsContent(vm, wide) }
                    Tab.SERIES -> ZoneLayout(vm, vm.series, showEpg = false, posters = true, tabs = tabs) { wide -> SeriesContent(vm, wide) }
                    Tab.SEARCH -> SearchScreen(vm, onPlay)
                    Tab.RECENT -> RecentScreen(vm, onPlay)
                    Tab.DOWNLOADS -> DownloadsScreen(vm, onPlay)
                    Tab.SETTINGS -> SettingsScreen(vm)
                }
            }
        }
    }
}

@Composable
private fun TopBar(vm: MainViewModel, tab: Tab, edit: Boolean, isTv: Boolean, current: FocusRequester) {
    val firstTab = remember { FocusRequester() }
    if (isTv && !vm.initialTabFocusDone) {
        LaunchedEffect(Unit) { vm.initialTabFocusDone = true; runCatching { firstTab.requestFocus() } }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        if (maxWidth < 600.dp) {
            // Phones: the three zones share the first row, everything else on the second row.
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tab.entries.filter { it.zone }.forEachIndexed { i, t ->
                        val ownTab = remember(t) { FocusRequester() }
                        ZoneButton(
                            t, t == tab, { vm.setTab(t) },
                            Modifier.weight(1f).then(if (i == 0) Modifier.focusRequester(firstTab) else Modifier)
                                .focusRequester(if (t == tab) current else ownTab),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!tab.zone) SmallTab("←", false) { vm.setTab(Tab.LIVE) }
                    Tab.entries.filter { !it.zone }.forEach { t -> SmallTab(t.label, t == tab) { vm.setTab(t) } }
                    Spacer(Modifier.weight(1f))
                    if (tab.zone) {
                        EditButton(edit, big = false) { if (edit) vm.setEditMode(false) else PinGate.require(vm.appStorage.hasPin) { vm.setEditMode(true) } }
                        FocusBox(onClick = vm::reloadCurrent, shape = RoundedCornerShape(20.dp)) {
                            Icon(Icons.Filled.Refresh, "Reload", Modifier.padding(6.dp).size(20.dp))
                        }
                    }
                }
            }
        } else {
            // TV and tablets: one row.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!tab.zone) {
                    FocusBox(onClick = { vm.setTab(Tab.LIVE) }, shape = RoundedCornerShape(20.dp)) {
                        Icon(Icons.Filled.ArrowBack, "Back", Modifier.padding(8.dp).size(24.dp))
                    }
                }
                Text(
                    "Nova", fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp),
                )
                LazyRow(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    itemsIndexed(Tab.entries.toList()) { i, t ->
                        val ownTab = remember(t) { FocusRequester() }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (i == 3) Spacer(Modifier.width(16.dp)) // gap between the 3 zones and the rest
                            Chip(
                                t.label, t == tab, { vm.setTab(t) },
                                modifier = (if (i == 0) Modifier.focusRequester(firstTab) else Modifier)
                                    .focusRequester(if (t == tab) current else ownTab),
                                big = t.zone,
                            )
                        }
                    }
                }
                if (tab.zone) {
                    EditButton(edit, big = true) { if (edit) vm.setEditMode(false) else PinGate.require(vm.appStorage.hasPin) { vm.setEditMode(true) } }
                    FocusBox(onClick = vm::reloadCurrent, shape = RoundedCornerShape(20.dp)) {
                        Icon(Icons.Filled.Refresh, "Reload", Modifier.padding(8.dp).size(22.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoneButton(t: Tab, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    FocusBox(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(12.dp), selected = selected) {
        Text(
            t.label.replace("  ", " "),
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SmallTab(label: String, selected: Boolean, onClick: () -> Unit) {
    FocusBox(onClick = onClick, shape = RoundedCornerShape(16.dp), selected = selected) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            fontSize = 13.sp,
            maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// =============================================================================== ZONE LAYOUT

/**
 * Groups on the left (or behind a button on phones), the selected group's content on the right,
 * and on TV for live channels the programme guide panel.
 */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun <T> ZoneLayout(
    vm: MainViewModel,
    z: Zone<T>,
    showEpg: Boolean,
    posters: Boolean = false,
    tabs: TabFocus? = null,
    content: @Composable (wide: Boolean) -> Unit,
) {
    val edit by vm.editMode.collectAsStateWithLifecycle()
    val selected by z.selected.collectAsStateWithLifecycle()
    val cats by z.cats.collectAsStateWithLifecycle()
    var showGroups by remember { mutableStateOf(false) }
    val settings by vm.settings.collectAsStateWithLifecycle()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val big = maxWidth >= 720.dp
        val wide = big || settings.groupsAlwaysLeft
        // Wider while editing, so the group names stay readable.
        val panelWidth = if (big) (if (edit) (maxWidth * 0.36f).coerceIn(300.dp, 460.dp) else (maxWidth * 0.26f).coerceIn(200.dp, 300.dp))
        else maxWidth * 0.42f
        val roomForGuide = maxWidth >= 900.dp
        val guideWidth = (maxWidth * 0.26f).coerceIn(240.dp, 320.dp)
        if (wide) {
            // Films & series with the remote: the arrows stay inside the posters, Back goes to the groups.
            val trap = posters && !edit && settings.stayInPosters
            val gridFocus = remember { FocusRequester() }
            var gridHasFocus by remember { mutableStateOf(false) }
            var focusGroupsAsk by remember { mutableIntStateOf(0) }
            var focusGridAsk by remember { mutableIntStateOf(0) }
            var groupsHaveFocus by remember { mutableStateOf(false) }
            // The arrows can't leave the posters, but Nova itself may move the remote out (Back → groups):
            // without this, Compose also cancels that move and Back seems to do nothing.
            var allowExit by remember { mutableStateOf(false) }
            val context = LocalContext.current
            // Back, one level at a time, never leaving Films / Series: posters → groups → top tabs.
            BackHandler(enabled = posters && !edit) {
                when {
                    gridHasFocus -> {
                        android.util.Log.i("NovaTest", "POSTERS back to groups")
                        allowExit = true
                        focusGroupsAsk++
                    }
                    groupsHaveFocus -> {
                        android.util.Log.i("NovaTest", "POSTERS back to tabs")
                        tabs?.focus?.invoke()
                    }
                    tabs?.hasFocus?.invoke() == true -> {
                        android.util.Log.i("NovaTest", "POSTERS back on tabs: stay")
                        Toast.makeText(context, "Use ◀ ▶ to change section", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        // Nothing has the remote (mouse, back from the player): put it on the posters.
                        android.util.Log.i("NovaTest", "POSTERS back: remote to the posters")
                        focusGridAsk++
                    }
                }
            }
            LaunchedEffect(focusGridAsk) {
                if (focusGridAsk == 0) return@LaunchedEffect
                // The group's posters may still be loading: try for a moment.
                repeat(30) {
                    kotlinx.coroutines.delay(100)
                    if (gridHasFocus) return@LaunchedEffect
                    runCatching { gridFocus.requestFocus() }
                }
                // No poster to stand on (empty group): the groups then.
                if (!gridHasFocus) focusGroupsAsk++
            }
            Row(Modifier.fillMaxSize()) {
                // Full group list on the left: all servers, first added first.
                GroupPanel(
                    vm, z,
                    Modifier.width(panelWidth).fillMaxHeight().onFocusChanged {
                        if (it.hasFocus != groupsHaveFocus) android.util.Log.i("NovaTest", "GROUPS focus ${if (it.hasFocus) "in" else "out"}")
                        groupsHaveFocus = it.hasFocus
                    },
                    focusOpens = true,
                    focusSelectedAsk = focusGroupsAsk,
                ) { if (trap) focusGridAsk++ }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    ContentHeader(vm.groupTitle(z, selected), edit) {
                        if (!edit && selected != MainViewModel.NEW) SortButton(vm, z.code)
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .then(
                                if (posters && !edit) Modifier
                                    .focusRequester(gridFocus)
                                    .onFocusChanged {
                                        if (it.hasFocus != gridHasFocus) android.util.Log.i("NovaTest", "POSTERS focus ${if (it.hasFocus) "in" else "out"}")
                                        gridHasFocus = it.hasFocus
                                        if (!it.hasFocus) allowExit = false
                                    }
                                    .focusProperties { if (trap) exit = { if (allowExit) FocusRequester.Default else FocusRequester.Cancel } }
                                    .focusGroup()
                                else Modifier
                            )
                    ) { content(big) }
                }
                if (showEpg && !edit && roomForGuide) {
                    val epg by vm.focusedEpg.collectAsStateWithLifecycle()
                    EpgPanel(epg, Modifier.width(guideWidth).fillMaxHeight())
                }
            }
        } else {
            val phoneContext = LocalContext.current
            if (posters && !edit) {
                // Films & series: posters → group list → stay (a hint), never Live TV.
                val onGroups = showGroups || (selected == null && !cats.loading)
                BackHandler {
                    if (onGroups) Toast.makeText(phoneContext, "Use the tabs at the top to change section", Toast.LENGTH_SHORT).show()
                    else showGroups = true
                }
            } else {
                BackHandler(enabled = showGroups) { showGroups = false }
            }
            if (showGroups || (selected == null && !cats.loading)) {
                GroupPanel(vm, z, Modifier.fillMaxSize(), focusOpens = false) { showGroups = false }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FocusBox(
                            onClick = { showGroups = true },
                            modifier = Modifier.weight(1f),
                            selected = true,
                        ) {
                            Text(
                                "☰  ${vm.groupTitle(z, selected)}   ▾",
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!edit && selected != MainViewModel.NEW) SortButton(vm, z.code, Modifier.padding(start = 6.dp))
                    }
                    if (edit) EditHint()
                    Box(Modifier.weight(1f)) { content(false) }
                }
            }
        }
    }
}

@Composable
private fun ContentHeader(title: String, edit: Boolean, actions: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp, end = 12.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
        if (edit) EditHint()
    }
}

@Composable
private fun EditHint() {
    Text(
        "Edit mode (My order): ▲ ▼ move (hold to keep moving), ⤒ ⤓ to the top / bottom. Long-press a name (or hold OK) to pick it up, then ▲ ▼ on the remote and OK to drop. Hide / Show to hide. ✓ Done or Back to finish.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun <T> GroupPanel(
    vm: MainViewModel,
    z: Zone<T>,
    modifier: Modifier,
    focusOpens: Boolean,
    focusSelectedAsk: Int = 0,
    onPicked: () -> Unit,
) {
    val o by vm.org.collectAsStateWithLifecycle()
    val cats by z.cats.collectAsStateWithLifecycle()
    val errors by z.catErrors.collectAsStateWithLifecycle()
    val selected by z.selected.collectAsStateWithLifecycle()
    val edit by vm.editMode.collectAsStateWithLifecycle()
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val multi = accounts.size > 1
    val sections = remember(cats, errors, o, edit, accounts) { vm.sections(z, o, edit) }
    val moving by vm.movingKey.collectAsStateWithLifecycle()
    val picked by vm.pickedKey.collectAsStateWithLifecycle()
    val groupListState = rememberLazyListState()
    val keepInPlace = rememberKeepInPlace(groupListState)
    val favCount = favs.count { it.type == z.savedType }
    val showHidden = vm.showsHidden(edit)

    // Back from the posters: scroll to the open group and put the remote on it.
    val selectedFocus = remember { FocusRequester() }
    LaunchedEffect(focusSelectedAsk) {
        if (focusSelectedAsk == 0) return@LaunchedEffect
        val keys = buildList {
            if (!focusOpens && selected != null) add("back")
            add(MainViewModel.FAV)
            if (z.hasNew) add(MainViewModel.NEW)
            if (cats.loading) add("loading")
            if (cats.error != null) add("error")
            sections.forEach { sec ->
                if (multi) add("hdr")
                if (sec.error != null) add("err")
                if (!sec.closed || !multi) sec.groups.forEach { add(it.key) }
            }
        }
        val index = keys.indexOf(selected).coerceAtLeast(0)
        val visible = groupListState.layoutInfo.visibleItemsInfo.any { it.index == index }
        if (!visible) groupListState.scrollToItem((index - 3).coerceAtLeast(0))
        repeat(10) {
            kotlinx.coroutines.delay(50)
            if (runCatching { selectedFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    LazyColumn(modifier.background(MaterialTheme.colorScheme.surface).scrollBar(groupListState), state = groupListState, contentPadding = PaddingValues(8.dp)) {
        if (!focusOpens && selected != null) {
            item(key = "back") {
                FocusBox(onClick = onPicked, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.ArrowBack, "Back", Modifier.size(22.dp))
                        Text("  Back to the list", fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        item(key = "fav") {
            GroupRow(
                title = "★ Favorites ($favCount)",
                selected = selected == MainViewModel.FAV,
                focusRequester = if (selected == MainViewModel.FAV) selectedFocus else null,
                hidden = false,
                onClick = { vm.selectGroup(z, MainViewModel.FAV); onPicked() },
                onFocused = if (focusOpens && !edit) ({ vm.focusGroup(z, MainViewModel.FAV) }) else null,
            )
        }
        if (z.hasNew) {
            item(key = "new") {
                GroupRow(
                    title = "🆕 Recently added",
                    selected = selected == MainViewModel.NEW,
                    focusRequester = if (selected == MainViewModel.NEW) selectedFocus else null,
                    hidden = false,
                    onClick = { vm.selectGroup(z, MainViewModel.NEW); onPicked() },
                    onFocused = if (focusOpens && !edit) ({ vm.focusGroup(z, MainViewModel.NEW) }) else null,
                )
            }
        }
        if (cats.loading) item { Loading() }
        val err = cats.error
        if (err != null) item { ErrorBox(err) { vm.loadCategories(z) } }

        sections.forEach { sec ->
            if (multi) {
                item(key = "hdr-${sec.account.id}") {
                    FocusBox(
                        onClick = { vm.toggleSection(z, sec.account.id) },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                    ) {
                        Text(
                            (if (sec.closed) "▸  " else "▾  ") + sec.account.name + "  (${sec.groups.size})",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
            val secError = sec.error
            if (secError != null) {
                item(key = "err-${sec.account.id}") {
                    Text(
                        "${sec.account.name}: $secError",
                        color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            if (!sec.closed || !multi) {
                itemsIndexed(sec.groups, key = { _, c -> c.key }) { _, c ->
                    val hidden = showHidden && vm.isGroupHidden(z, c, o)
                    val move: (Int) -> Unit = { d -> keepInPlace(c.key) { vm.moveGroup(z, c, d) } }
                    val (lineMod, lineControls) = editLine(vm, "g:${c.key}", edit)
                    GroupRow(
                        title = c.name,
                        selected = selected == c.key,
                        focusRequester = if (selected == c.key) selectedFocus else null,
                        hidden = hidden,
                        onClick = { if (edit) vm.editActiveKey.value = "g:${c.key}"; vm.selectGroup(z, c.key); onPicked() },
                        onFocused = if (focusOpens && !edit) ({ vm.focusGroup(z, c.key) }) else null,
                        moving = edit && moving == c.key,
                        picked = edit && picked == c.key,
                        onLongClick = if (edit) ({ vm.togglePicked(c.key) }) else null,
                        rowModifier = lineMod.then(if (edit && picked == c.key) pickedKeys(move) { vm.togglePicked(c.key) } else Modifier),
                        controls = if (edit && lineControls) {
                            {
                                // The list scrolls with the moved group, so the arrows stay on it.
                                EditControls(
                                    hidden = hidden,
                                    onUp = { move(-1) },
                                    onDown = { move(1) },
                                    onToggleHidden = {
                                        vm.landAfterHide(sec.groups, c, "g") { it.key }
                                        vm.toggleGroupHidden(z, c)
                                    },
                                    onTop = { vm.moveGroupTo(z, c, top = true) },
                                    onBottom = { vm.moveGroupTo(z, c, top = false) },
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupRow(
    title: String,
    selected: Boolean,
    hidden: Boolean,
    onClick: () -> Unit,
    onFocused: (() -> Unit)? = null,
    controls: (@Composable () -> Unit)? = null,
    moving: Boolean = false,
    picked: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    rowModifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val ownFocus = remember { FocusRequester() }
    Row(
        rowModifier.fillMaxWidth().padding(vertical = 1.dp).alpha(if (hidden) 0.45f else 1f)
            .then(if (moving || picked) Modifier.border(if (picked) 3.dp else 2.dp, MovingYellow, RoundedCornerShape(12.dp)) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The open group: a green bar and green text (not a full frame, so it never looks like a second highlight).
        FocusBox(
            onClick = onClick, onLongClick = onLongClick, selected = false, onFocused = onFocused,
            // Always the same kind of modifier (only the requester object changes): adding / removing it on the
            // focused row when its group opened made the row lose the remote's highlight.
            modifier = Modifier.weight(1f).focusRequester(focusRequester ?: ownFocus),
        ) { focused ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .padding(start = 4.dp)
                        .width(4.dp)
                        .height(22.dp)
                        .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(2.dp))
                )
                Text(
                    (if (picked) "⇅ ▲▼ " else if (moving) "⇅ " else "") + title,
                    modifier = Modifier.padding(start = 10.dp, end = 14.dp, top = 11.dp, bottom = 11.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected || focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        if (controls != null) controls()
    }
}

// =============================================================================== LIVE

/** Unique keys for a lazy list (providers sometimes repeat the same id). */
private fun uniqueKeys(keys: List<String>): List<String> {
    val seen = HashMap<String, Int>()
    return keys.map { k ->
        val n = seen.getOrDefault(k, 0)
        seen[k] = n + 1
        if (n == 0) k else "$k#$n"
    }
}

/**
 * Keeps a moved item at the same place on screen: the list scrolls with it, so the finger / remote
 * stays on the same item and pressing ▲ ▼ again keeps moving it.
 */
@Composable
private fun rememberKeepInPlace(state: LazyListState): (Any, () -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    return { key, change ->
        val before = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset
        change()
        if (before != null) {
            scope.launch {
                androidx.compose.runtime.withFrameNanos { }
                androidx.compose.runtime.withFrameNanos { }
                val after = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset
                if (after != null && after != before) state.scrollBy((after - before).toFloat())
            }
        }
    }
}

/** Remote keys while an item is picked up in edit mode: ▲ ▼ move it, OK / Back drops it. */
private fun pickedKeys(onMove: (Int) -> Unit, onDrop: () -> Unit) = Modifier.onPreviewKeyEvent { e ->
    val code = e.nativeKeyEvent.keyCode
    val down = e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown
    when (code) {
        android.view.KeyEvent.KEYCODE_DPAD_UP -> { if (down) onMove(-1); true }
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) onMove(1); true }
        // Picked up: the remote stays on it until OK drops it.
        android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> true
        android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_BACK -> {
            if (!down) onDrop(); true
        }
        else -> false
    }
}

/** Keeps a moved item on screen, so the arrow buttons (and the remote's focus) stay with it. */
@Composable
private fun rememberFollow(listState: LazyListState): (Int) -> Unit {
    val scope = rememberCoroutineScope()
    return { i ->
        scope.launch {
            val info = listState.layoutInfo.visibleItemsInfo
            val first = info.firstOrNull()?.index ?: 0
            val last = info.lastOrNull()?.index ?: 0
            if (i <= first) listState.scrollToItem(maxOf(0, i - 1))
            else if (i >= last) listState.scrollToItem(maxOf(0, i - (last - first) + 2))
        }
    }
}

@Composable
private fun LiveList(vm: MainViewModel, onPlay: PlayFn, wide: Boolean) {
    val z = vm.live
    val state by z.items.collectAsStateWithLifecycle()
    val o by vm.org.collectAsStateWithLifecycle()
    val edit by vm.editMode.collectAsStateWithLifecycle()
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val selected by z.selected.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sort = settings.sorts["live"] ?: "custom"
    val shown = remember(state.items, o, edit, selected, sort) { vm.visibleItems(z, state.items, o, edit, sort) }
    val keys = remember(shown) { uniqueKeys(shown.map { it.key }) }
    val lastKey = recent.firstOrNull { it.type == ItemType.LIVE }?.key
    val listState = rememberLazyListState()
    val liveGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val follow = rememberFollow(listState)
    val keepInPlace = rememberKeepInPlace(listState)
    val moving by vm.movingKey.collectAsStateWithLifecycle()
    val picked by vm.pickedKey.collectAsStateWithLifecycle()
    var catchupFor by remember { mutableStateOf<LiveChannel?>(null) }
    catchupFor?.let { ch -> CatchupDialog(vm, ch, onPlay) { catchupFor = null } }

    val row: @Composable (Int, LiveChannel) -> Unit = { index, ch ->
        val saved = vm.saved(ch)
        val isFav = favs.any { it.sameAs(saved) }
        val hidden = vm.showsHidden(edit) && vm.isItemHidden(z, ch, o)
        var onAir by remember(ch.key) { mutableStateOf<EpgEntry?>(null) }
        val (lineMod, lineControls) = editLine(vm, "i:${ch.key}", edit)
        if (settings.liveNowLine && !edit) {
            LaunchedEffect(ch.key) { onAir = vm.nowPlaying(ch) }
        }
        RowItem(
            title = ch.name,
            subtitle = listOfNotNull(
                if (accounts.size > 1) vm.accountName(ch.accountId) else null,
                if (ch.hasArchive) "Catch-up" else null,
            ).joinToString("  ·  ").ifBlank { null },
            image = ch.icon,
            leading = (if (sort == "number" && ch.num > 0) ch.num else index + 1).toString(),
            onClick = { if (!edit) onPlay(vm.liveItems(shown), index) else vm.editActiveKey.value = "i:${ch.key}" },
            onFocused = if (wide && !edit) ({ vm.focusChannel(ch) }) else null,
            onLongClick = { if (edit) vm.togglePicked(ch.key) else vm.toggleFavorite(saved) },
            modifier = lineMod.alpha(if (hidden) 0.45f else 1f)
                .then(if (edit && (moving == ch.key || picked == ch.key)) Modifier.border(if (picked == ch.key) 3.dp else 2.dp, MovingYellow, RoundedCornerShape(12.dp)) else Modifier)
                .then(if (edit && picked == ch.key) pickedKeys({ d -> keepInPlace(keys[index]) { vm.moveItem(z, shown, ch, d) } }) { vm.togglePicked(ch.key) } else Modifier),
            active = !edit && ch.key == lastKey,
            size = settings.liveRowSize,
            showImage = settings.liveLogos,
            extra = if (settings.liveNowLine && !edit) onAir?.let { "▶ " + XtreamApi.formatTime(it.start) + "  " + it.title } else null,
            trailing = {
                if (edit) {
                    if (lineControls) EditControls(
                        hidden = hidden,
                        onUp = { keepInPlace(keys[index]) { vm.moveItem(z, shown, ch, -1) } },
                        onDown = { keepInPlace(keys[index]) { vm.moveItem(z, shown, ch, 1) } },
                        onTop = { vm.moveItemTo(z, shown, ch, top = true) },
                        onBottom = { vm.moveItemTo(z, shown, ch, top = false) },
                        onToggleHidden = if (selected == MainViewModel.FAV) null else ({
                            vm.landAfterHide(shown, ch, "i") { it.key }
                            vm.toggleItemHidden(z, ch)
                        }),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (ch.hasArchive) {
                            FocusBox(onClick = { catchupFor = ch }, shape = RoundedCornerShape(20.dp)) {
                                Text("⟲", fontSize = 20.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                            }
                        }
                        FavButton(isFav) { vm.toggleFavorite(saved) }
                    }
                }
            },
        )
    }

    when {
        state.loading -> Loading()
        state.error != null -> ErrorBox(state.error ?: "", vm::reloadCurrent)
        shown.isEmpty() -> EmptyText(
            if (selected == MainViewModel.FAV) "No favorite channels yet.\nUse ♡ next to a channel, or long-press it."
            else "No channels in this group."
        )
        wide && !edit && settings.liveColumns == 2 -> LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = liveGridState,
            modifier = Modifier.fillMaxSize().scrollBar(liveGridState),
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            itemsIndexed(shown, key = { i, _ -> keys[i] }) { index, ch -> row(index, ch) }
        }
        else -> LazyColumn(Modifier.fillMaxSize().scrollBar(listState), state = listState, contentPadding = PaddingValues(vertical = 6.dp)) {
            itemsIndexed(shown, key = { i, _ -> keys[i] }) { index, ch -> row(index, ch) }
        }
    }
}

/** Past programmes of a channel (catch-up), grouped by day. */
@Composable
private fun CatchupDialog(vm: MainViewModel, ch: LiveChannel, onPlay: PlayFn, onDismiss: () -> Unit) {
    var entries by remember(ch.key) { mutableStateOf<List<com.nova.iptv.data.CatchupEntry>?>(null) }
    var error by remember(ch.key) { mutableStateOf<String?>(null) }
    LaunchedEffect(ch.key) {
        try {
            entries = vm.catchupFor(ch)
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load catch-up"
            entries = emptyList()
        }
    }
    val dayFormat = remember { java.text.SimpleDateFormat("EEEE d MMM", java.util.Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Catch-up · ${ch.name}") },
        text = {
            val list = entries
            when {
                list == null -> Loading()
                error != null -> Text(error ?: "", color = MaterialTheme.colorScheme.error)
                list.isEmpty() -> Text("No past programmes are available for this channel.")
                else -> LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    itemsIndexed(list) { i, e ->
                        val day = dayFormat.format(java.util.Date(e.start))
                        if (i == 0 || dayFormat.format(java.util.Date(list[i - 1].start)) != day) {
                            Text(day, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                        }
                        FocusBox(onClick = {
                            vm.catchupItem(ch, e)?.let { onPlay(listOf(it), 0) }
                            onDismiss()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text(
                                    "${XtreamApi.formatTime(e.start)}–${XtreamApi.formatTime(e.end)}",
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(100.dp),
                                )
                                Text(e.title.ifBlank { "Programme" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun EpgPanel(epg: Pair<LiveChannel, List<EpgEntry>?>?, modifier: Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
        if (epg == null) {
            Text("Move over a channel to see what's on.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val (ch, list) = epg
        Logo(ch.icon, ch.name, Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(10.dp)))
        Spacer(Modifier.height(10.dp))
        Text(ch.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        when {
            list == null -> Text("Loading guide…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            list.isEmpty() -> Text("No programme guide for this channel.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                val now = System.currentTimeMillis()
                LazyColumn {
                    itemsIndexed(list.filter { it.end > now }) { _, e ->
                        val live = now >= e.start && now < e.end
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(
                                (if (live) "NOW  " else "") + "${XtreamApi.formatTime(e.start)} – ${XtreamApi.formatTime(e.end)}",
                                fontSize = 12.sp,
                                color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (live) FontWeight.Bold else FontWeight.Normal,
                            )
                            Text(e.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (live && e.description.isNotBlank()) {
                                Text(
                                    e.description, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================== FILMS & SERIES GRID

/** Poster grid columns from Settings → Display (fixed number, or automatic by poster size). */
private fun posterCells(s: SettingsState, big: Boolean): GridCells {
    val fixed = if (big) s.posterColumnsTv else s.posterColumnsPhone
    if (fixed > 0) return GridCells.Fixed(fixed)
    return GridCells.Adaptive(
        when (s.posterSize) {
            "small" -> 100.dp
            "large" -> 170.dp
            else -> 128.dp
        }
    )
}

/** A poster's corner badge: the account (several accounts), otherwise the year or rating per the sort. */
private fun posterBadge(vm: MainViewModel, accountId: String, sort: String, year: Int?, rating: String?): String? = when {
    vm.multiAccount -> vm.accountName(accountId).take(10)
    sort == "year" || sort == "added" -> year?.toString() ?: rating?.takeIf { it != "0" }
    else -> rating?.takeIf { it != "0" }
}

@Composable
private fun FilmsContent(vm: MainViewModel, big: Boolean) {
    val z = vm.films
    val state by z.items.collectAsStateWithLifecycle()
    val o by vm.org.collectAsStateWithLifecycle()
    val edit by vm.editMode.collectAsStateWithLifecycle()
    val selected by z.selected.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sort = settings.sorts["movie"] ?: "custom"
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val favKeys = remember(favs) { favs.map { "${it.type}|${it.accountId}|${it.id}" }.toSet() }
    val shown = remember(state.items, o, edit, selected, sort) { vm.visibleItems(z, state.items, o, edit, sort) }
    val keys = remember(shown) { uniqueKeys(shown.map { it.key }) }
    val posterBack = rememberPosterReturn(vm, remember(shown) { shown.map { it.key } })
    // Double click / double OK on a poster: "Hide …?" (Yes / No).
    var hideAsk by remember { mutableStateOf<Movie?>(null) }
    hideAsk?.let { item ->
        HideAskDialog(item.name, item.icon, onYes = {
            val i = shown.indexOf(item)
            (shown.getOrNull(i + 1) ?: shown.getOrNull(i - 1))?.let { vm.restoreFocusKey.value = it.key }
            vm.toggleItemHidden(z, item)
        }, onDismiss = { hideAsk = null })
    }
    val recent by vm.recent.collectAsStateWithLifecycle()
    val lastKey = recent.firstOrNull { it.type == ItemType.MOVIE }?.key
    val listState = rememberLazyListState()
    val follow = rememberFollow(listState)
    val keepInPlace = rememberKeepInPlace(listState)
    val moving by vm.movingKey.collectAsStateWithLifecycle()
    val picked by vm.pickedKey.collectAsStateWithLifecycle()
    val canMove = selected != MainViewModel.NEW

    when {
        state.loading -> Loading()
        state.error != null -> ErrorBox(state.error ?: "", vm::reloadCurrent)
        shown.isEmpty() -> EmptyText(
            when (selected) {
                MainViewModel.FAV -> "No favorite films yet.\nLong-press a film, or use ♡ on its page."
                MainViewModel.NEW -> "No films found."
                else -> "No films in this group."
            }
        )
        edit -> LazyColumn(Modifier.fillMaxSize().scrollBar(listState), state = listState, contentPadding = PaddingValues(vertical = 6.dp)) {
            itemsIndexed(shown, key = { i, _ -> keys[i] }) { index, m ->
                val hidden = vm.showsHidden(edit) && vm.isItemHidden(z, m, o)
                val (lineMod, lineControls) = editLine(vm, "i:${m.key}", edit)
                RowItem(
                    title = m.name,
                    subtitle = if (accounts.size > 1) vm.accountName(m.accountId) else null,
                    image = m.icon,
                    onClick = { vm.editActiveKey.value = "i:${m.key}" },
                    onLongClick = if (canMove) ({ vm.togglePicked(m.key) }) else null,
                    modifier = lineMod.alpha(if (hidden) 0.45f else 1f)
                        .then(if (moving == m.key || picked == m.key) Modifier.border(if (picked == m.key) 3.dp else 2.dp, MovingYellow, RoundedCornerShape(12.dp)) else Modifier)
                        .then(if (picked == m.key) pickedKeys({ d -> keepInPlace(keys[index]) { vm.moveItem(z, shown, m, d) } }) { vm.togglePicked(m.key) } else Modifier),
                    trailing = {
                        if (lineControls) EditControls(
                            hidden = hidden,
                            onUp = if (canMove) ({ keepInPlace(keys[index]) { vm.moveItem(z, shown, m, -1) } }) else null,
                            onDown = if (canMove) ({ keepInPlace(keys[index]) { vm.moveItem(z, shown, m, 1) } }) else null,
                            onTop = if (canMove) ({ vm.moveItemTo(z, shown, m, top = true) }) else null,
                            onBottom = if (canMove) ({ vm.moveItemTo(z, shown, m, top = false) }) else null,
                            onToggleHidden = if (selected == MainViewModel.FAV) null else ({
                                vm.landAfterHide(shown, m, "i") { it.key }
                                vm.toggleItemHidden(z, m)
                            }),
                        )
                    },
                )
            }
        }
        else -> LazyVerticalGrid(
            columns = posterCells(settings, big),
            state = posterBack.first,
            modifier = Modifier.fillMaxSize().scrollBar(posterBack.first),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(shown, key = { i, _ -> keys[i] }) { _, m ->
                val ownPoster = remember { FocusRequester() }
                PosterCard(
                    m.name, m.icon,
                    modifier = posterBack.second(m.key, ownPoster),
                    onClick = { vm.openMovie(m) },
                    onLongClick = { vm.toggleFavorite(vm.saved(m)) },
                    onDoubleClick = if (selected == MainViewModel.FAV) null else ({ hideAsk = m }),
                    favorite = favKeys.contains("${ItemType.MOVIE}|${m.accountId}|${m.streamId}"),
                    active = m.key == lastKey,
                    badge = posterBadge(vm, m.accountId, sort, m.year, m.rating),
                    showTitle = settings.posterTitles,
                )
            }
        }
    }
}

@Composable
private fun SeriesContent(vm: MainViewModel, big: Boolean) {
    val z = vm.series
    val state by z.items.collectAsStateWithLifecycle()
    val o by vm.org.collectAsStateWithLifecycle()
    val edit by vm.editMode.collectAsStateWithLifecycle()
    val selected by z.selected.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sort = settings.sorts["series"] ?: "custom"
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val favKeys = remember(favs) { favs.map { "${it.type}|${it.accountId}|${it.id}" }.toSet() }
    val shown = remember(state.items, o, edit, selected, sort) { vm.visibleItems(z, state.items, o, edit, sort) }
    val keys = remember(shown) { uniqueKeys(shown.map { it.key }) }
    val posterBack = rememberPosterReturn(vm, remember(shown) { shown.map { it.key } })
    // Double click / double OK on a poster: "Hide …?" (Yes / No).
    var hideAsk by remember { mutableStateOf<Series?>(null) }
    hideAsk?.let { item ->
        HideAskDialog(item.name, item.cover, onYes = {
            val i = shown.indexOf(item)
            (shown.getOrNull(i + 1) ?: shown.getOrNull(i - 1))?.let { vm.restoreFocusKey.value = it.key }
            vm.toggleItemHidden(z, item)
        }, onDismiss = { hideAsk = null })
    }
    val listState = rememberLazyListState()
    val follow = rememberFollow(listState)
    val keepInPlace = rememberKeepInPlace(listState)
    val moving by vm.movingKey.collectAsStateWithLifecycle()
    val picked by vm.pickedKey.collectAsStateWithLifecycle()
    val canMove = selected != MainViewModel.NEW

    when {
        state.loading -> Loading()
        state.error != null -> ErrorBox(state.error ?: "", vm::reloadCurrent)
        shown.isEmpty() -> EmptyText(
            when (selected) {
                MainViewModel.FAV -> "No favorite series yet.\nLong-press a series, or use ♡ on its page."
                MainViewModel.NEW -> "No series found."
                else -> "No series in this group."
            }
        )
        edit -> LazyColumn(Modifier.fillMaxSize().scrollBar(listState), state = listState, contentPadding = PaddingValues(vertical = 6.dp)) {
            itemsIndexed(shown, key = { i, _ -> keys[i] }) { index, s ->
                val hidden = vm.showsHidden(edit) && vm.isItemHidden(z, s, o)
                val (lineMod, lineControls) = editLine(vm, "i:${s.key}", edit)
                RowItem(
                    title = s.name,
                    subtitle = if (accounts.size > 1) vm.accountName(s.accountId) else null,
                    image = s.cover,
                    onClick = { vm.editActiveKey.value = "i:${s.key}" },
                    onLongClick = if (canMove) ({ vm.togglePicked(s.key) }) else null,
                    modifier = lineMod.alpha(if (hidden) 0.45f else 1f)
                        .then(if (moving == s.key || picked == s.key) Modifier.border(if (picked == s.key) 3.dp else 2.dp, MovingYellow, RoundedCornerShape(12.dp)) else Modifier)
                        .then(if (picked == s.key) pickedKeys({ d -> keepInPlace(keys[index]) { vm.moveItem(z, shown, s, d) } }) { vm.togglePicked(s.key) } else Modifier),
                    trailing = {
                        if (lineControls) EditControls(
                            hidden = hidden,
                            onUp = if (canMove) ({ keepInPlace(keys[index]) { vm.moveItem(z, shown, s, -1) } }) else null,
                            onDown = if (canMove) ({ keepInPlace(keys[index]) { vm.moveItem(z, shown, s, 1) } }) else null,
                            onTop = if (canMove) ({ vm.moveItemTo(z, shown, s, top = true) }) else null,
                            onBottom = if (canMove) ({ vm.moveItemTo(z, shown, s, top = false) }) else null,
                            onToggleHidden = if (selected == MainViewModel.FAV) null else ({
                                vm.landAfterHide(shown, s, "i") { it.key }
                                vm.toggleItemHidden(z, s)
                            }),
                        )
                    },
                )
            }
        }
        else -> LazyVerticalGrid(
            columns = posterCells(settings, big),
            state = posterBack.first,
            modifier = Modifier.fillMaxSize().scrollBar(posterBack.first),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(shown, key = { i, _ -> keys[i] }) { _, s ->
                val ownPoster = remember { FocusRequester() }
                PosterCard(
                    s.name, s.cover,
                    modifier = posterBack.second(s.key, ownPoster),
                    onClick = { vm.openSeries(s) },
                    onLongClick = { vm.toggleFavorite(vm.saved(s)) },
                    onDoubleClick = if (selected == MainViewModel.FAV) null else ({ hideAsk = s }),
                    favorite = favKeys.contains("${ItemType.SERIES}|${s.accountId}|${s.seriesId}"),
                    badge = posterBadge(vm, s.accountId, sort, s.year, s.rating),
                    showTitle = settings.posterTitles,
                )
            }
        }
    }
}

// =============================================================================== RECENT

@Composable
private fun RecentScreen(vm: MainViewModel, onPlay: PlayFn) {
    val recent by vm.recent.collectAsStateWithLifecycle()
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val list = recent.filter { r -> accounts.any { it.id == r.accountId } }

    if (list.isEmpty()) {
        EmptyText("Nothing watched yet.")
        return
    }
    val channels = list.filter { it.type == ItemType.LIVE }
    val others = list.filter { it.type != ItemType.LIVE }

    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = vm::clearRecent) { Text("Clear history") }
            }
        }
        if (channels.isNotEmpty()) {
            item { SectionTitle("Channels") }
            itemsIndexed(channels) { index, s ->
                SavedRow(vm, s, favs, accounts.size > 1) { onPlay(vm.savedItemsToPlay(channels), index) }
            }
        }
        if (others.isNotEmpty()) {
            item { SectionTitle("Films & series") }
            itemsIndexed(others) { _, s ->
                SavedRow(vm, s, favs, accounts.size > 1) {
                    if (s.type == ItemType.SERIES) vm.openSeries(vm.seriesFromSaved(s))
                    else vm.savedItemsToPlay(listOf(s)).firstOrNull()?.let { onPlay(listOf(it), 0) }
                }
            }
        }
    }
}

@Composable
private fun SavedRow(vm: MainViewModel, s: SavedItem, favs: List<SavedItem>, multi: Boolean, onClick: () -> Unit) {
    val isFav = favs.any { it.sameAs(s) }
    val label = when (s.type) {
        ItemType.LIVE -> "Live channel"
        ItemType.MOVIE -> "Film"
        ItemType.SERIES -> "Series"
        else -> "Episode"
    }
    RowItem(
        title = s.name,
        subtitle = if (multi) "$label  ·  ${vm.accountName(s.accountId)}" else label,
        image = s.icon,
        onClick = onClick,
        onLongClick = { vm.toggleFavorite(s) },
        trailing = { FavButton(isFav) { vm.toggleFavorite(s) } },
    )
}

// =============================================================================== SEARCH (all accounts)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SearchScreen(vm: MainViewModel, onPlay: PlayFn) {
    val q by vm.query.collectAsStateWithLifecycle()
    val res by vm.search.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val kind by vm.searchKind.collectAsStateWithLifecycle()
    val onlyId by vm.searchAccount.collectAsStateWithLifecycle()
    val only = accounts.firstOrNull { it.id == onlyId }
    val multi = accounts.size > 1
    val acc: (String) -> String? = { id -> if (multi) vm.accountName(id) else null }
    val kinds = listOf("all" to "All", "live" to "📺 Channels", "movie" to "🎬 Films", "series" to "🎞 Series")
    val what = when (kind) { "live" -> "channels"; "movie" -> "films"; "series" -> "series"; else -> "channels, films and series" }
    val where = when { only != null -> "in ${only.name}"; multi -> "in all ${accounts.size} servers"; else -> "" }
    val foundIn = only?.let { " in ${it.name}" } ?: ""

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = q,
                onValueChange = vm::setQuery,
                label = { Text("Search $what $where".trim()) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            )
            val lookFor: @Composable () -> Unit = {
                FilterLabel("LOOK FOR")
                kinds.forEach { (k, label) -> Chip(label, kind == k, { vm.setSearchKind(k) }) }
            }
            val inServers: @Composable () -> Unit = {
                FilterLabel("IN")
                Chip("All servers", only == null, { vm.setSearchAccount(null) })
                accounts.forEach { a -> Chip(a.name, only?.id == a.id, { vm.setSearchAccount(a.id) }) }
            }
            if (wide) {
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    lookFor()
                    if (multi) { Spacer(Modifier.width(18.dp)); inServers() }
                }
            } else {
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) { lookFor() }
                if (multi) androidx.compose.foundation.layout.FlowRow(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) { inServers() }
            }
            val err = res.error
            if (err != null && !res.loading) {
                Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
            }
            val channelRows: LazyListScope.() -> Unit = {
                item { SectionTitle("📺 Channels · ${res.channels.size} found$foundIn") }
                if (res.channels.isEmpty()) item { Text("Nothing found", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
                itemsIndexed(res.channels) { index, ch ->
                    RowItem(
                        ch.name, acc(ch.accountId), ch.icon,
                        onClick = { onPlay(vm.liveItems(res.channels), index) },
                        onLongClick = { vm.toggleFavorite(vm.saved(ch)) },
                    )
                }
            }
            val movieRows: LazyListScope.() -> Unit = {
                item { SectionTitle("🎬 Films · ${res.movies.size} found$foundIn") }
                if (res.movies.isEmpty()) item { Text("Nothing found", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
                itemsIndexed(res.movies) { _, m ->
                    RowItem(
                        m.name,
                        listOfNotNull(acc(m.accountId), m.rating?.takeIf { it != "0" }?.let { "Rating $it" })
                            .joinToString("  ·  ").ifBlank { null },
                        m.icon, onClick = { vm.openMovie(m) },
                    )
                }
            }
            val seriesRows: LazyListScope.() -> Unit = {
                item { SectionTitle("🎞 Series · ${res.series.size} found$foundIn") }
                if (res.series.isEmpty()) item { Text("Nothing found", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
                itemsIndexed(res.series) { _, s ->
                    RowItem(s.name, acc(s.accountId), s.cover, onClick = { vm.openSeries(s) })
                }
            }
            when {
                res.loading -> Loading()
                q.trim().length < 2 -> EmptyText("Type at least 2 letters.\nChoose above what to look for" + (if (multi) " and in which server." else "."))
                res.channels.isEmpty() && res.movies.isEmpty() && res.series.isEmpty() -> EmptyText("No results.")
                // TV / wide screen, everything: three columns side by side.
                wide && kind == "all" -> Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(channelRows, movieRows, seriesRows).forEach { rows ->
                        LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(bottom = 16.dp)) { rows() }
                    }
                }
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (kind == "all" || kind == "live") { if (kind == "live" || res.channels.isNotEmpty()) channelRows() }
                    if (kind == "all" || kind == "movie") { if (kind == "movie" || res.movies.isNotEmpty()) movieRows() }
                    if (kind == "all" || kind == "series") { if (kind == "series" || res.series.isNotEmpty()) seriesRows() }
                }
            }
        }
    }
}

@Composable
private fun FilterLabel(text: String) {
    Text(
        text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.5.sp,
        modifier = Modifier.padding(top = 10.dp, end = 4.dp),
    )
}

/** Lets Films / Series move the remote to the top tabs on Back. */
class TabFocus(val focus: () -> Unit, val hasFocus: () -> Boolean)

/**
 * Back from a film / series page: scroll to the poster you opened and put the remote on it
 * (instead of the remote landing on a tab, where the next Back would leave the page).
 */
@Composable
private fun rememberPosterReturn(vm: MainViewModel, itemKeys: List<String>): Pair<androidx.compose.foundation.lazy.grid.LazyGridState, (String, FocusRequester) -> Modifier> {
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val restoreKey by vm.restoreFocusKey.collectAsStateWithLifecycle()
    val requester = remember { FocusRequester() }
    LaunchedEffect(restoreKey, itemKeys) {
        val k = restoreKey ?: return@LaunchedEffect
        val index = itemKeys.indexOf(k)
        if (index < 0) return@LaunchedEffect
        runCatching { gridState.scrollToItem(index) }
        repeat(15) {
            kotlinx.coroutines.delay(60)
            if (runCatching { requester.requestFocus() }.isSuccess) {
                android.util.Log.i("NovaTest", "POSTERS remote back on the opened poster")
                vm.clearRestoreFocus()
                return@LaunchedEffect
            }
        }
        vm.clearRestoreFocus()
    }
    // Every poster always carries a requester (the chain never changes shape, so the remote's highlight is kept).
    return gridState to { key, own -> Modifier.focusRequester(if (key == restoreKey) requester else own) }
}

/** "✎ Edit / Reorder" — wide and framed; "✓ Done" (green) while editing, same size so nothing moves. */
@Composable
private fun EditButton(edit: Boolean, big: Boolean, onClick: () -> Unit) {
    val green = MaterialTheme.colorScheme.primary
    FocusBox(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .widthIn(min = if (big) 190.dp else 0.dp)
            .border(2.dp, if (edit) green else Color(0xFF9FC3FF), RoundedCornerShape(22.dp))
            .background(if (edit) Color(0xFF174A2C) else Color(0x33FFFFFF), RoundedCornerShape(22.dp)),
    ) {
        Text(
            if (edit) "✓ Done" else "✎ Edit / Reorder",
            modifier = Modifier.align(Alignment.Center).padding(horizontal = if (big) 22.dp else 12.dp, vertical = if (big) 10.dp else 6.dp),
            fontWeight = FontWeight.Bold,
            fontSize = if (big) 16.sp else 13.sp,
            color = if (edit) green else Color.White,
            maxLines = 1,
        )
    }
}

/**
 * Edit mode: arrows and Hide only on the line the remote, mouse or finger is on — the other lines show their full name.
 * Returns the modifier for the line and whether its controls are shown.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun editLine(vm: MainViewModel, key: String, edit: Boolean): Pair<Modifier, Boolean> {
    if (!edit) return Modifier to false
    val active by vm.editActiveKey.collectAsStateWithLifecycle()
    val picked by vm.pickedKey.collectAsStateWithLifecycle()
    val moving by vm.movingKey.collectAsStateWithLifecycle()
    val landOn by vm.focusLineKey.collectAsStateWithLifecycle()
    val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val requester = remember { FocusRequester() }
    val raw = key.substringAfter(':')
    LaunchedEffect(hovered) { if (hovered && NovaInput.mouse) vm.editActiveKey.value = key }
    // After a Hide: the remote lands on this line's name.
    LaunchedEffect(landOn) {
        if (landOn != key) return@LaunchedEffect
        repeat(10) {
            kotlinx.coroutines.delay(60)
            if (runCatching { requester.requestFocus() }.isSuccess) {
                android.util.Log.i("NovaTest", "EDIT remote on the next line: $key")
                vm.focusLineKey.value = null
                return@LaunchedEffect
            }
        }
        vm.focusLineKey.value = null
    }
    // While this line is being moved, ◀ ▶ stay on it (its name and buttons) — the remote never wanders off.
    val beingMoved = picked == raw || moving == raw
    val mod = Modifier
        .focusRequester(requester)
        .onFocusChanged {
            if (it.hasFocus) {
                vm.editActiveKey.value = key
                // Working on another line now: the previous one is no longer "being moved".
                val mk = vm.movingKey.value
                if (mk != null && mk != raw && vm.pickedKey.value == null) vm.movingKey.value = null
            }
        }
        .focusProperties {
            exit = { dir ->
                if (beingMoved && (dir == androidx.compose.ui.focus.FocusDirection.Left || dir == androidx.compose.ui.focus.FocusDirection.Right)) FocusRequester.Cancel
                else FocusRequester.Default
            }
        }
        .focusGroup()
        .hoverable(hover)
    return mod to (active == key || picked == raw)
}
