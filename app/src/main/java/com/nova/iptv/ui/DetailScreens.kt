package com.nova.iptv.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.nova.iptv.data.DownloadStatus
import com.nova.iptv.data.MediaDetails

private val StarYellow = Color(0xFFFFD233)

// =============================================================================== shared pieces

/** Blurred-looking film still behind the page, fading into the background. */
@Composable
private fun Backdrop(url: String?, modifier: Modifier) {
    val bg = MaterialTheme.colorScheme.background
    Box(modifier) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().alpha(0.30f),
            )
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(listOf(bg.copy(alpha = 0.55f), bg.copy(alpha = 0.85f), bg))
            )
        )
    }
}

/** Five stars for a rating out of 10, with half stars, and the number. */
@Composable
fun RatingStars(rating: Double?, modifier: Modifier = Modifier, starSize: Int = 20) {
    if (rating == null || rating <= 0) return
    val outOfFive = (rating / 2).coerceIn(0.0, 5.0)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 5) {
            val fill = (outOfFive - i).coerceIn(0.0, 1.0).toFloat()
            Box {
                Text("★", fontSize = starSize.sp, color = Color(0xFF5A606A))
                if (fill > 0f) {
                    Text(
                        "★", fontSize = starSize.sp, color = StarYellow,
                        modifier = Modifier.drawWithContent {
                            clipRect(right = size.width * fill) { this@drawWithContent.drawContent() }
                        },
                    )
                }
            }
        }
        Text(
            "  " + "%.1f".format(rating).removeSuffix(".0").removeSuffix(",0") + "/10",
            fontSize = (starSize - 6).sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String?, labelWidth: Dp, maxLines: Int = 2, color: Color? = null) {
    if (value.isNullOrBlank()) return
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.width(labelWidth))
        Text(
            value.trim(), fontSize = 15.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            color = color ?: MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
        )
    }
}

/** A large button for the info pages (works with touch and with the remote). */
@Composable
fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, main: Boolean = false, warn: Boolean = false, enabled: Boolean = true) {
    FocusBox(onClick = { if (enabled) onClick() }, modifier = modifier, shape = RoundedCornerShape(12.dp)) { focused ->
        Text(
            label,
            modifier = Modifier
                .background(
                    when {
                        main -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = if (main) 28.dp else 16.dp, vertical = 11.dp)
                .alpha(if (enabled) 1f else 0.5f),
            color = when {
                main -> MaterialTheme.colorScheme.onPrimary
                warn -> MaterialTheme.colorScheme.error
                focused -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
            fontWeight = if (main) FontWeight.Bold else FontWeight.Medium,
            fontSize = if (main) 17.sp else 15.sp,
            maxLines = 1,
        )
    }
}

/** Opens the trailer in YouTube (or the browser). */
fun openTrailer(context: Context, trailer: String) {
    val t = trailer.trim()
    val url = if (t.startsWith("http", ignoreCase = true)) t else "https://www.youtube.com/watch?v=$t"
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app on this device can open the trailer (YouTube or a browser).", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun PageHeader(title: String, isFav: Boolean, onBack: () -> Unit, onFav: () -> Unit, backFocus: FocusRequester? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FocusBox(
            onClick = onBack, shape = RoundedCornerShape(24.dp),
            modifier = if (backFocus != null) Modifier.focusRequester(backFocus) else Modifier,
        ) {
            Icon(Icons.Filled.ArrowBack, "Back", Modifier.padding(10.dp).size(26.dp))
        }
        Text(
            title, fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp).weight(1f),
        )
        FavButton(isFav, onFav)
    }
}

// =============================================================================== FILM PAGE

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MovieDetailScreen(vm: MainViewModel, d: MovieDetailState, onPlay: PlayFn) {
    val movie = d.movie
    val det: MediaDetails = d.details
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val saved = vm.saved(movie)
    val isFav = favs.any { it.sameAs(saved) }
    val canResume = remember(movie, recent) { vm.hasResume(saved) }
    val url = remember(movie) { vm.movieItem(movie)?.url }
    val dl = downloads.firstOrNull { it.url == url }
    val context = LocalContext.current
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(movie.key) { runCatching { playFocus.requestFocus() } }

    val poster = det.poster ?: movie.icon
    val rating = det.rating ?: movie.rating?.toDoubleOrNull()
    val release = det.releaseDate ?: movie.year?.toString()

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Backdrop(det.backdrop ?: poster, Modifier.fillMaxSize())
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val narrow = maxWidth < 560.dp
            val posterWidth = if (narrow) 120.dp else (maxWidth * 0.2f).coerceIn(150.dp, 240.dp)
            val labelWidth = if (narrow) 104.dp else 150.dp
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp)) {
                PageHeader(movie.name, isFav, vm::closeMovie, { vm.toggleFavorite(saved) })
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Logo(poster, movie.name, Modifier.width(posterWidth).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)), ContentScale.Crop)
                        Spacer(Modifier.height(8.dp))
                        RatingStars(rating, starSize = if (narrow) 16 else 20)
                    }
                    Column(Modifier.padding(start = 18.dp).weight(1f)) {
                        InfoLine("Directed by:", det.director, labelWidth)
                        InfoLine("Release date:", release, labelWidth)
                        InfoLine("Duration:", det.duration, labelWidth)
                        InfoLine("Genre:", det.genre, labelWidth)
                        InfoLine("Cast:", det.cast, labelWidth, maxLines = 3)
                        InfoLine("Country:", det.country, labelWidth)
                        if (vm.multiAccount) InfoLine("Account:", vm.accountName(movie.accountId), labelWidth, color = MaterialTheme.colorScheme.primary)
                        if (movie.extension.isNotBlank()) InfoLine("Format:", movie.extension.uppercase(), labelWidth)
                        if (d.loading) {
                            Text("Loading details…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        }
                        if (!narrow) {
                            Spacer(Modifier.height(16.dp))
                            MovieButtons(vm, d, onPlay, canResume, dl, playFocus)
                        }
                    }
                }
                if (narrow) {
                    Spacer(Modifier.height(14.dp))
                    MovieButtons(vm, d, onPlay, canResume, dl, playFocus)
                }
                val plot = det.plot
                if (!plot.isNullOrBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text(plot.trim(), fontSize = 16.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.height(30.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MovieButtons(
    vm: MainViewModel,
    d: MovieDetailState,
    onPlay: PlayFn,
    canResume: Boolean,
    dl: com.nova.iptv.data.DownloadItem?,
    playFocus: FocusRequester,
) {
    val movie = d.movie
    val saved = vm.saved(movie)
    val context = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton(
            if (canResume) "Resume" else "Play",
            { vm.movieItem(movie)?.let { onPlay(listOf(it), 0) } },
            Modifier.focusRequester(playFocus), main = true,
        )
        if (canResume) {
            ActionButton("Play from start", {
                vm.clearResume(saved)
                vm.movieItem(movie)?.let { onPlay(listOf(it), 0) }
            })
        }
        if (dl?.status != DownloadStatus.DONE) {
            ActionButton("⬇▶ Download & watch", {
                vm.downloadAndWatchMovie(movie)?.let { onPlay(listOf(it), 0) }
            })
        }
        d.details.trailer?.let { t -> ActionButton("▶ Trailer", { openTrailer(context, t) }) }
        ActionButton(
            when (dl?.status) {
                null, DownloadStatus.FAILED -> "⬇ Download to device"
                DownloadStatus.DONE -> "✓ Downloaded"
                DownloadStatus.PAUSED -> "⬇ Resume download"
                else -> "⬇ Downloading…"
            },
            {
                vm.downloadMovie(movie)
                Toast.makeText(context, "Download added — see the Downloads tab", Toast.LENGTH_SHORT).show()
            },
            enabled = dl == null || dl.status == DownloadStatus.PAUSED || dl.status == DownloadStatus.FAILED,
        )
        ActionButton("↗ External player", {
            vm.movieItem(movie)?.let {
                vm.addRecent(it.saved)
                com.nova.iptv.player.ExternalPlayer.open(context, it, vm.externalPlayerPackage())
            }
        })
    }
}

// =============================================================================== SERIES PAGE

@Composable
fun SeriesDetailScreen(vm: MainViewModel, d: SeriesDetailState, onPlay: PlayFn) {
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val saved = vm.saved(d.series)
    val isFav = favs.any { it.sameAs(saved) }
    val backFocus = remember { FocusRequester() }
    val context = LocalContext.current
    LaunchedEffect(d.series.key) { runCatching { backFocus.requestFocus() } }

    val info = d.info
    val det = info?.details ?: MediaDetails(plot = d.series.plot)
    val poster = d.series.cover ?: det.poster
    val rating = det.rating ?: d.series.rating?.toDoubleOrNull()
    val resume = remember(info, recent) { info?.let { vm.resumeEpisode(it) } }
    var plotOpen by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val narrow = maxWidth < 560.dp
        val posterWidth = if (narrow) 110.dp else 150.dp
        val labelWidth = if (narrow) 96.dp else 140.dp
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") {
                Box(Modifier.fillMaxWidth()) {
                    Backdrop(det.backdrop ?: poster, Modifier.matchParentSize())
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        PageHeader(d.series.name, isFav, vm::closeSeries, { vm.toggleFavorite(saved) }, backFocus)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Logo(poster, d.series.name, Modifier.width(posterWidth).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)), ContentScale.Crop)
                                Spacer(Modifier.height(6.dp))
                                RatingStars(rating, starSize = 15)
                            }
                            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                                InfoLine("Genre:", det.genre ?: info?.genre, labelWidth)
                                InfoLine("Release date:", det.releaseDate ?: d.series.year?.toString(), labelWidth)
                                InfoLine("Duration:", det.duration, labelWidth)
                                InfoLine("Directed by:", det.director, labelWidth)
                                InfoLine("Cast:", det.cast ?: info?.cast, labelWidth, maxLines = 2)
                                if (vm.multiAccount) InfoLine("Account:", vm.accountName(d.series.accountId), labelWidth, color = MaterialTheme.colorScheme.primary)
                                info?.let { InfoLine("Seasons:", "${it.seasons.size}  ·  ${it.episodes.values.sumOf { e -> e.size }} episodes", labelWidth) }
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (info != null && resume != null) {
                                        val (season, index) = resume
                                        val ep = info.episodes[season]?.getOrNull(index)
                                        if (ep != null) {
                                            ActionButton(
                                                "▶ Resume S$season E${ep.episodeNum}",
                                                { vm.allEpisodeItems(info, season, index).let { (l, i) -> onPlay(l, i) } }, main = true,
                                            )
                                        }
                                    }
                                    det.trailer?.let { t -> ActionButton("▶ Trailer", { openTrailer(context, t) }) }
                                }
                                if (info != null) {
                                    var autoNext by remember(info.series.key) { mutableStateOf(vm.autoNext(info)) }
                                    Spacer(Modifier.height(8.dp))
                                    FocusBox(onClick = {
                                        autoNext = !autoNext
                                        vm.setAutoNext(info, autoNext)
                                        android.util.Log.i("NovaTest", "AUTO NEXT ${if (autoNext) "on" else "off"} ${info.series.name}")
                                    }, shape = RoundedCornerShape(20.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                            Text("⏭ Auto-play next episode", fontSize = 14.sp)
                                            Spacer(Modifier.width(10.dp))
                                            androidx.compose.material3.Switch(checked = autoNext, onCheckedChange = null)
                                        }
                                    }
                                }
                            }
                        }
                        val plot = det.plot ?: info?.plot ?: d.series.plot
                        if (!plot.isNullOrBlank()) {
                            FocusBox(onClick = { plotOpen = !plotOpen }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                                Text(
                                    plot.trim(), fontSize = 15.sp, lineHeight = 21.sp,
                                    maxLines = if (plotOpen) 30 else 3, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(6.dp),
                                )
                            }
                        }
                    }
                }
            }
            when {
                d.loading -> item(key = "loading") { Loading() }
                d.error != null -> item(key = "error") { ErrorBox(d.error) { vm.openSeries(d.series) } }
                info == null || info.episodes.isEmpty() -> item(key = "empty") { EmptyText("No episodes found.") }
                else -> {
                    val season = d.season ?: info.seasons.first()
                    item(key = "seasons") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(info.seasons) { _, s -> Chip("Season $s", s == season, { vm.selectSeason(s) }) }
                        }
                    }
                    val episodes = info.episodes[season].orEmpty()
                    val lastId = resume?.let { (s, i) -> info.episodes[s]?.getOrNull(i)?.id }
                    itemsIndexed(episodes, key = { i, e -> "ep-$season-$i-${e.id}" }) { index, e ->
                        RowItem(
                            title = "E${e.episodeNum}  ${e.title}",
                            subtitle = e.plot,
                            image = e.image ?: d.series.cover,
                            onClick = { vm.allEpisodeItems(info, season, index).let { (l, i) -> onPlay(l, i) } },
                            active = e.id == lastId,
                            trailing = {
                                FocusBox(onClick = {
                                    vm.downloadAndWatchEpisode(info, e)?.let { onPlay(listOf(it), 0) }
                                }, shape = RoundedCornerShape(20.dp)) {
                                    Text("⬇▶", fontSize = 18.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                                }
                                FocusBox(onClick = {
                                    vm.downloadEpisode(info, e)
                                    Toast.makeText(context, "Download added — see the Downloads tab", Toast.LENGTH_SHORT).show()
                                }, shape = RoundedCornerShape(20.dp)) {
                                    Text("⬇", fontSize = 20.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

// =============================================================================== SORT

/** "⇅ Sort: …" button above a list; the choice is saved per zone (also in Settings → Display). */
@Composable
fun SortButton(vm: MainViewModel, zone: String, modifier: Modifier = Modifier) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val current = s.sorts[zone] ?: "custom"
    var open by remember { mutableStateOf(false) }
    FocusBox(onClick = { open = true }, modifier = modifier, shape = RoundedCornerShape(20.dp), selected = current != "custom") {
        Text(
            "⇅ " + Sorts.label(zone, current),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            fontSize = 14.sp, maxLines = 1,
            color = if (current != "custom") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Sort by") },
            text = {
                Column(Modifier.heightIn(max = 420.dp)) {
                    Sorts.of(zone).forEach { (value, label) ->
                        FocusBox(
                            onClick = { vm.setSort(zone, value); open = false },
                            modifier = Modifier.fillMaxWidth(),
                            selected = value == current,
                        ) {
                            Text(
                                (if (value == current) "◉  " else "○  ") + label,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                color = if (value == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    Text(
                        "The first choice keeps the order you set with ✎ Edit (▲▼). Edit mode always shows that order.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}
