package com.nova.iptv.ui

import org.json.JSONObject as BackupJson
import android.app.Application
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.nova.iptv.NovaApp
import com.nova.iptv.data.Account
import com.nova.iptv.data.Category
import com.nova.iptv.data.ContentType
import com.nova.iptv.data.DownloadItem
import com.nova.iptv.data.EpgEntry
import com.nova.iptv.data.Episode
import com.nova.iptv.data.Folders
import com.nova.iptv.data.ItemType
import com.nova.iptv.data.LiveChannel
import com.nova.iptv.data.MediaDetails
import com.nova.iptv.data.Movie
import com.nova.iptv.data.Org
import com.nova.iptv.data.OrgData
import com.nova.iptv.data.SavedItem
import com.nova.iptv.data.Series
import com.nova.iptv.data.SeriesInfo
import com.nova.iptv.data.UserInfo
import com.nova.iptv.data.XtreamApi
import com.nova.iptv.download.DownloadCenter
import com.nova.iptv.player.PlayItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Top-level areas. The first three are the main zones. */
enum class Tab(val label: String, val zone: Boolean) {
    LIVE("📺  Live TV", true),
    FILMS("🎬  Films", true),
    SERIES("🎞  Series", true),
    SEARCH("Search", false),
    RECENT("Recent", false),
    DOWNLOADS("Downloads", false),
    LINK("🔗 Open link", false),
    SETTINGS("Settings", false),
}

data class ListState<T>(
    val loading: Boolean = false,
    val items: List<T> = emptyList(),
    val error: String? = null,
)

data class MovieDetailState(
    val movie: Movie,
    val loading: Boolean = true,
    val details: MediaDetails = MediaDetails(),
)

/** Values used to sort a zone's items. */
data class SortInfo(val added: Long, val year: Int?, val rating: Double?, val number: Int)

/** Sort choices per zone (value, label). "custom" = my order (manual ▲▼). */
object Sorts {
    val live = listOf("custom" to "My order", "added" to "Recently added", "number" to "Channel number", "name" to "Name A → Z")
    val vod = listOf("custom" to "My order", "added" to "Recently added", "year" to "Year (newest)", "name" to "Name A → Z", "rating" to "Rating (highest)")
    fun of(zone: String) = if (zone == "live") live else vod
    fun label(zone: String, value: String) = of(zone).firstOrNull { it.first == value }?.second ?: "My order"
}

data class SeriesDetailState(
    val series: Series,
    val loading: Boolean = true,
    val info: SeriesInfo? = null,
    val error: String? = null,
    val season: Int? = null,
)

/** Search results: each list mixes all accounts. */
data class SearchResults(
    val loading: Boolean = false,
    val channels: List<LiveChannel> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
    val error: String? = null,
)

data class SettingsState(
    val liveFormat: String,
    val cushionSeconds: Int,
    val maxBufferSeconds: Int,
    val vodCacheMb: Int,
    val userAgent: String,
    val usePublicFolder: Boolean,
    val liveDiskBuffer: Boolean,
    val liveDiskMb: Int,
    val autoSkip: Boolean,
    val skipTimeoutSec: Int,
    val storageRoot: String?,
    val tunneling: Boolean,
    val clockPosition: String,
    val groupsAlwaysLeft: Boolean,
    val openGroupOnFocus: Boolean,
    val autoPlayLast: Boolean,
    val hasPin: Boolean,
    val playerMode: String,
    val externalPlayer: String?,
    val audioDecoder: String,
    val externalOnError: Boolean,
    val videoDecoder: String,
    val bufferMb: Int,
    val audioOffload: Boolean,
    val videoSurface: String,
    val requireVpn: Boolean,
    val players: Map<String, String>,
    val sorts: Map<String, String>,
    val posterColumnsPhone: Int,
    val posterColumnsTv: Int,
    val posterSize: String,
    val posterTitles: Boolean,
    val liveRowSize: String,
    val liveLogos: Boolean,
    val liveNowLine: Boolean,
    val liveColumns: Int,
    val doubleBackLive: Boolean,
    val doubleBackExit: Boolean,
    val numberKeys: Boolean,
    val stayInPosters: Boolean,
    val liveStart: String,
    val readAheadMb: Int,
    val overlayTransparency: Int,
)

/** One hidden group or item, for the parental-control list in Settings. */
data class HiddenEntry(
    val orgKey: String,
    val isGroup: Boolean,
    val zoneLabel: String,
    val name: String,
)

/** One account's groups inside a zone, shown as a section that can be closed. */
data class GroupSection(
    val account: Account,
    val groups: List<Category>,
    val closed: Boolean,
    val error: String?,
)

/**
 * One of the three zones (Live TV, Films, Series). Groups and items from every account
 * are shown together; each item remembers which account it comes from.
 */
class Zone<T>(
    val type: ContentType,
    /** Name used in the organisation settings: "live", "movie", "series". */
    val code: String,
    val savedType: String,
    val keyOf: (T) -> String,
    val nameOf: (T) -> String,
    val fetch: suspend (Account, String?) -> List<T>,
    val fromSaved: (SavedItem) -> T,
    val sortInfo: (T) -> SortInfo,
    val categoryOf: (T) -> String,
    /** Shows the "Recently added" group (films and series). */
    val hasNew: Boolean = false,
) {
    val cats = MutableStateFlow(ListState<Category>())
    val catErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val selected = MutableStateFlow<String?>(null)
    val items = MutableStateFlow(ListState<T>())
    internal val cache = HashMap<String, List<T>>()
    internal var job: Job? = null
    internal var catJob: Job? = null

    fun clear() {
        job?.cancel(); catJob?.cancel()
        cache.clear()
        cats.value = ListState(); catErrors.value = emptyMap()
        selected.value = null; items.value = ListState()
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val nova = application as NovaApp
    private val storage get() = nova.storage
    private val api get() = nova.api

    // ---------- accounts ----------
    val accounts = MutableStateFlow(storage.accounts)
    val userInfos = MutableStateFlow<Map<String, UserInfo?>>(emptyMap())
    val showLogin = MutableStateFlow(storage.accounts.isEmpty())
    val loginBusy = MutableStateFlow(false)
    val loginError = MutableStateFlow<String?>(null)

    // ---------- navigation & organisation ----------
    val tab = MutableStateFlow(Tab.LIVE)
    val editMode = MutableStateFlow(false)
    val org = MutableStateFlow(storage.org)

    // ---------- zones ----------
    val live = Zone<LiveChannel>(
        ContentType.LIVE, "live", ItemType.LIVE, { it.key }, { it.name },
        { a, c -> api.liveStreams(a, c) },
        { s -> LiveChannel(s.accountId, s.id, 0, s.name, s.icon, "", false) },
        { SortInfo(it.added, null, null, it.num) }, { it.categoryId },
    )
    val films = Zone<Movie>(
        ContentType.MOVIE, "movie", ItemType.MOVIE, { it.key }, { it.name },
        { a, c -> api.movies(a, c) },
        { s -> Movie(s.accountId, s.id, s.name, s.icon, "", null, s.extension ?: "mp4", year = XtreamApi.yearFromName(s.name)) },
        { SortInfo(it.added, it.year, it.rating?.toDoubleOrNull(), 0) }, { it.categoryId },
        hasNew = true,
    )
    val series = Zone<Series>(
        ContentType.SERIES, "series", ItemType.SERIES, { it.key }, { it.name },
        { a, c -> api.series(a, c) },
        { s -> Series(s.accountId, s.id, s.name, s.icon, "", null, null, year = XtreamApi.yearFromName(s.name)) },
        { SortInfo(it.added, it.year, it.rating?.toDoubleOrNull(), 0) }, { it.categoryId },
        hasNew = true,
    )

    val seriesDetail = MutableStateFlow<SeriesDetailState?>(null)
    val movieDetail = MutableStateFlow<MovieDetailState?>(null)
    val focusedEpg = MutableStateFlow<Pair<LiveChannel, List<EpgEntry>?>?>(null)

    val favorites = MutableStateFlow(storage.favorites)
    val recent = MutableStateFlow(storage.recent)

    val query = MutableStateFlow("")
    val search = MutableStateFlow(SearchResults())

    val settings = MutableStateFlow(readSettings())
    val downloads = DownloadCenter.items
    val needStoragePermission = MutableStateFlow(false)

    private var epgJob: Job? = null
    private var searchJob: Job? = null
    private var seriesJob: Job? = null
    private var movieJob: Job? = null

    // ================================================================ start-up

    sealed class Startup {
        object Loading : Startup()
        object Ready : Startup()
        data class AllFailed(val problems: List<Pair<Account, String>>) : Startup()
    }

    val startup = MutableStateFlow<Startup>(Startup.Loading)

    /** Accounts that didn't answer properly at start-up (id → reason), and expiry notes (id → note). */
    val accountProblems = MutableStateFlow<Map<String, String>>(emptyMap())
    val expiryNotes = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Welcome screen: "Add my IPTV account" shows the sign-in form. */
    val welcomeForm = MutableStateFlow(false)

    /**
     * While the start-up cover shows: re-read the accounts link, then check every account (at most ~8 s).
     * All fine → Live TV. All failing → the "Accounts check" screen. Some failing → Live TV with a warning.
     */
    fun runStartup() {
        startup.value = Startup.Loading
        viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            if (storage.accountsLink != null) {
                runCatching { kotlinx.coroutines.withTimeout(8000) { syncAccountsLink() } }
            }
            val accs = accounts.value
            val results = accs.map { acc ->
                async {
                    acc to try {
                        kotlinx.coroutines.withTimeout(8000) { checkAccount(acc) }
                    } catch (e: CancellationException) {
                        if (e is kotlinx.coroutines.TimeoutCancellationException) Check(null, "Server not reachable (no answer in time — no internet, or blocked here: try your VPN)") else throw e
                    }
                }
            }.awaitAll()
            val problems = results.mapNotNull { (a, c) -> c.problem?.let { a to it } }
            accountProblems.value = problems.associate { it.first.id to it.second }
            expiryNotes.value = results.mapNotNull { (a, c) -> c.note?.let { a.id to it } }.toMap()
            results.forEach { (a, c) -> if (c.info != null) userInfos.value = userInfos.value + (a.id to c.info) }
            // Downloads never use more connections than the provider allows.
            results.forEach { (a, c) -> c.info?.maxConnections?.trim()?.toIntOrNull()?.let { storage.setMaxConnections(a.id, it) } }
            val wait = 1200 - (System.currentTimeMillis() - t0)
            if (wait > 0) delay(wait)
            android.util.Log.i("NovaTest", "STARTUP ${accs.size} accounts, ${problems.size} with problems")
            startup.value = if (accs.isNotEmpty() && problems.size == accs.size) Startup.AllFailed(problems) else Startup.Ready
        }
    }

    /** "Continue anyway" on the Accounts check screen. */
    fun continueAnyway() { startup.value = Startup.Ready }

    private class Check(val info: UserInfo?, val problem: String?, val note: String? = null)

    private suspend fun checkAccount(acc: Account): Check = try {
        val info = api.authenticate(acc)
        val now = System.currentTimeMillis()
        val exp = info.expDate?.let { it * 1000 }
        val date = { t: Long -> java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date(t)) }
        val status = info.status.lowercase()
        when {
            exp != null && exp < now -> Check(info, "⛔ Expired on ${date(exp)}")
            status == "expired" -> Check(info, "⛔ Expired")
            status == "banned" || status == "disabled" -> Check(info, "🚫 Blocked by the provider (${info.status})")
            exp != null && exp - now < 7 * 24 * 3600_000L -> {
                val days = ((exp - now) / (24 * 3600_000L)).toInt()
                Check(info, null, "${acc.name} expires ${if (days == 0) "today" else "in $days day${if (days == 1) "" else "s"}"} (${date(exp)})")
            }
            else -> Check(info, null)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val m = e.message.orEmpty()
        Check(
            null,
            when {
                "Wrong username" in m -> "🔑 Wrong username or password"
                "HTTP 401" in m || "HTTP 403" in m -> "🚫 Blocked by the provider / too many connections (${m.substringAfter("HTTP ").take(3).let { "HTTP $it" }})"
                "HTTP 5" in m -> "⚠ The provider's server has a problem ($m)"
                else -> "🌐 Server not reachable (no internet, or blocked here — try your VPN)"
            },
        )
    }

    // ================================================================ accounts link

    /** Downloads and reads an accounts list (asks for nothing: [password] is used if the file is protected). */
    suspend fun fetchAccountsList(link: String, password: String?): List<com.nova.iptv.data.ListedAccount> = withContext(Dispatchers.IO) {
        var text = com.nova.iptv.data.LinkFetcher.fetchText(nova.http, link, storage.userAgent)
        if (com.nova.iptv.data.NovaCrypto.isProtected(text)) {
            if (password.isNullOrEmpty()) throw IllegalArgumentException("This list is protected: enter its password")
            text = com.nova.iptv.data.NovaCrypto.decrypt(text, password)
        }
        val list = com.nova.iptv.data.AccountsList.parse(text)
        if (list.isEmpty()) throw IllegalArgumentException("No account found in this file (one per line: Name | server | username | password)")
        list
    }

    /** Checks one listed account with its server (for the "Found in your list" preview). */
    suspend fun testListed(a: com.nova.iptv.data.ListedAccount): String? =
        checkAccount(Account("preview", a.name, a.server, a.username, a.password)).problem

    /** Connects the link: its accounts are added / updated now, then kept up to date. */
    fun connectAccountsLink(link: String, password: String?, list: List<com.nova.iptv.data.ListedAccount>) {
        storage.accountsLink = link.trim()
        storage.accountsLinkPassword = password?.ifBlank { null }
        val note = applyAccountsList(list)
        storage.accountsLinkCheckedAt = System.currentTimeMillis()
        storage.accountsLinkNote = note
        welcomeForm.value = false
        showLogin.value = false
        startup.value = Startup.Ready
        runStartup()
    }

    fun disconnectAccountsLink() {
        storage.accountsLink = null
        storage.accountsLinkPassword = null
        storage.accountsLinkNote = null
        // The accounts stay, as normal accounts.
        storage.accounts = storage.accounts.map { it.copy(fromList = false) }
        accounts.value = storage.accounts
    }

    /** Re-reads the link and applies the changes. Returns what changed. Never changes anything if the file is unreadable or empty. */
    suspend fun syncAccountsLink(): String {
        val link = storage.accountsLink ?: return "No accounts link"
        val list = try {
            fetchAccountsList(link, storage.accountsLinkPassword)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "Could not read your accounts link — nothing changed (${e.message})"
            android.util.Log.i("NovaTest", "ACCOUNTS LINK $msg")
            storage.accountsLinkNote = msg
            return msg
        }
        val note = withContext(Dispatchers.Main) { applyAccountsList(list) }
        storage.accountsLinkCheckedAt = System.currentTimeMillis()
        storage.accountsLinkNote = note
        return note
    }

    /** Same name = same account (updated in place: favourites, order and hidden items stay). */
    private fun applyAccountsList(list: List<com.nova.iptv.data.ListedAccount>): String {
        val before = storage.accounts
        val changes = mutableListOf<String>()
        var added = 0; var updated = 0; var removed = 0
        val result = mutableListOf<Account>()
        list.forEach { l ->
            val old = before.firstOrNull { it.name.equals(l.name, ignoreCase = true) }
            if (old == null) {
                result += Account(UUID.randomUUID().toString(), l.name, l.server, l.username, l.password, fromList = true)
                added++; changes += "${l.name}: added"
            } else {
                val n = old.copy(server = l.server, username = l.username, password = l.password, fromList = true)
                if (n.server != old.server || n.username != old.username || n.password != old.password) {
                    updated++
                    changes += "${l.name}: " + when {
                        n.server != old.server -> "server address updated"
                        n.username != old.username -> "username updated"
                        else -> "password updated"
                    }
                }
                result += n
            }
        }
        // Accounts typed by hand stay; accounts from the list that left the list are removed.
        val listed = list.map { it.name.lowercase() }.toSet()
        before.forEach { a ->
            if (a.name.lowercase() in listed) return@forEach
            if (a.fromList) { removed++; changes += "${a.name}: removed (no longer in your list)"; storage.removeAccountData(a.id) }
            else result += a
        }
        // Keep the original order, new ones at the end.
        val ordered = before.mapNotNull { b -> result.firstOrNull { it.id == b.id } } + result.filter { r -> before.none { it.id == r.id } }
        val summary = "updated $updated, added $added, removed $removed"
        android.util.Log.i("NovaTest", "ACCOUNTS LINK $summary")
        if (added + updated + removed > 0 || before.any { b -> ordered.firstOrNull { it.id == b.id }?.fromList != b.fromList }) {
            storage.accounts = ordered
            accounts.value = storage.accounts
            if (added + updated + removed > 0) {
                userInfos.value = emptyMap()
                if (accounts.value.isEmpty()) listOf<Zone<*>>(live, films, series).forEach { it.clear() } else reloadEverything()
            }
        }
        return if (changes.isEmpty()) "Up to date" else changes.joinToString(" · ")
    }

    /** Your accounts as a list file (optionally protected) in the Backups folder. */
    fun saveAccountsListFile(password: String?): File {
        var text = com.nova.iptv.data.AccountsList.write(storage.accounts)
        if (!password.isNullOrEmpty()) text = com.nova.iptv.data.NovaCrypto.encrypt(text, password)
        val f = File(backupDir(), if (password.isNullOrEmpty()) "NovaIPTV-accounts.txt" else "NovaIPTV-accounts-protected.txt")
        f.writeText(text)
        return f
    }

    // ================================================================ accounts

    fun account(id: String): Account? = accounts.value.firstOrNull { it.id == id }

    fun accountName(id: String): String = account(id)?.name ?: "?"

    val multiAccount: Boolean get() = accounts.value.size > 1

    private fun reloadEverything() {
        listOf<Zone<*>>(live, films, series).forEach { it.clear() }
        search.value = SearchResults(); query.value = ""
        seriesDetail.value = null
        movieDetail.value = null
        refreshLocal()
        loadUserInfo()
        loadCategories(zoneFor(tab.value) ?: live)
    }

    fun loadUserInfo() {
        accounts.value.forEach { acc ->
            viewModelScope.launch {
                val info = try { api.authenticate(acc) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                userInfos.value = userInfos.value + (acc.id to info)
            }
        }
    }

    fun login(name: String, server: String, username: String, password: String) {
        if (server.isBlank() || username.isBlank() || password.isBlank()) {
            loginError.value = "Please fill in server, username and password."
            return
        }
        val candidate = Account(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { XtreamApi.normalizeServer(server).substringAfter("://") },
            server = XtreamApi.normalizeServer(server),
            username = username.trim(),
            password = password.trim(),
        )
        loginBusy.value = true
        loginError.value = null
        viewModelScope.launch {
            try {
                val info = api.authenticate(candidate)
                val existing = storage.accounts.firstOrNull {
                    it.server == candidate.server && it.username == candidate.username
                }
                val saved = existing?.copy(name = candidate.name, password = candidate.password) ?: candidate
                storage.accounts = storage.accounts.filterNot { it.id == saved.id } + saved
                accounts.value = storage.accounts
                userInfos.value = userInfos.value + (saved.id to info)
                showLogin.value = false
                reloadEverything()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loginError.value = e.message ?: "Could not connect"
            } finally {
                loginBusy.value = false
            }
        }
    }

    fun renameAccount(acc: Account, name: String) {
        if (name.isBlank()) return
        storage.accounts = storage.accounts.map { if (it.id == acc.id) it.copy(name = name.trim()) else it }
        accounts.value = storage.accounts
    }

    /** Checks edited account details with the server; null = OK, otherwise the error message. */
    suspend fun testAccount(a: Account): String? = try {
        api.authenticate(a)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.message ?: "Could not connect"
    }

    /** Builds the edited account (same id, so favorites, order and hidden items stay). */
    fun editedAccount(acc: Account, name: String, server: String, username: String, password: String): Account {
        val parsed = XtreamApi.parseLink(server)
        val srv = XtreamApi.normalizeServer(parsed?.first ?: server)
        return acc.copy(
            name = name.trim().ifBlank { srv.substringAfter("://") },
            server = srv,
            username = (parsed?.second ?: username).trim(),
            password = (parsed?.third ?: password).trim(),
        )
    }

    fun saveAccount(updated: Account) {
        storage.accounts = storage.accounts.map { if (it.id == updated.id) updated else it }
        accounts.value = storage.accounts
        userInfos.value = userInfos.value - updated.id
        reloadEverything()
    }

    fun removeAccount(acc: Account) {
        storage.accounts = storage.accounts.filterNot { it.id == acc.id }
        storage.removeAccountData(acc.id)
        accounts.value = storage.accounts
        userInfos.value = userInfos.value - acc.id
        if (accounts.value.isEmpty()) {
            listOf<Zone<*>>(live, films, series).forEach { it.clear() }
            showLogin.value = true
        } else {
            reloadEverything()
        }
    }

    fun openLogin(show: Boolean) {
        loginError.value = null
        showLogin.value = show || accounts.value.isEmpty()
    }

    // ================================================================ navigation

    fun zoneFor(t: Tab): Zone<*>? = when (t) {
        Tab.LIVE -> live
        Tab.FILMS -> films
        Tab.SERIES -> series
        else -> null
    }

    fun setTab(t: Tab) {
        // Leaving Settings locks the parental PIN again.
        if (tab.value == Tab.SETTINGS && t != Tab.SETTINGS) PinGate.lock()
        tab.value = t
        seriesDetail.value = null
        movieDetail.value = null
        if (!t.zone) editMode.value = false
        val z = zoneFor(t)
        if (z != null && z.cats.value.items.isEmpty() && !z.cats.value.loading) loadCategories(z)
        if (t == Tab.RECENT) refreshLocal()
    }

    fun setEditMode(on: Boolean) {
        editMode.value = on
        if (!on) { movingKey.value = null; pickedKey.value = null; editActiveKey.value = null; PinGate.lock() }
    }

    /** Edit mode: the line the remote should land on next (after a Hide), as "g:<group key>" / "i:<item key>". */
    val focusLineKey = MutableStateFlow<String?>(null)

    /** Before hiding a line: the remote goes to the next line's name (or the one above, for the last line). */
    fun <T> landAfterHide(list: List<T>, item: T, prefix: String, keyOf: (T) -> String) {
        val i = list.indexOf(item)
        val next = list.getOrNull(i + 1) ?: list.getOrNull(i - 1)
        val k = next?.let { "$prefix:${keyOf(it)}" }
        android.util.Log.i("NovaTest", "EDIT next line after hide: ${k ?: "none"}")
        editActiveKey.value = k
        focusLineKey.value = k
    }

    /** The group / item moved last (shown with its own highlight while editing). */
    val movingKey = MutableStateFlow<String?>(null)

    /** Picked up with OK in edit mode: ▲ ▼ then move it, OK drops it. */
    val pickedKey = MutableStateFlow<String?>(null)

    fun togglePicked(key: String) {
        pickedKey.value = if (pickedKey.value == key) null else key
        movingKey.value = key
    }

    // ================================================================ groups

    /** Loads the groups of every account for this zone (in parallel). */
    fun loadCategories(z: Zone<*>) {
        val accs = accounts.value
        z.catJob?.cancel()
        z.cats.value = ListState(loading = true)
        z.catJob = viewModelScope.launch {
            val results = accs.map { a ->
                async {
                    a to try {
                        Result.success(api.categories(a, z.type))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure<List<Category>>(e)
                    }
                }
            }.awaitAll()
            val cats = results.flatMap { it.second.getOrNull().orEmpty() }
            val errors = results.filter { it.second.isFailure }
                .associate { it.first.id to (it.second.exceptionOrNull()?.message ?: "Couldn't load") }
            z.catErrors.value = errors
            z.cats.value = ListState(
                items = cats,
                error = if (cats.isEmpty() && errors.isNotEmpty()) errors.values.joinToString("\n") else null,
            )
            if (z.selected.value == null) {
                val favs = favorites.value.any { it.type == z.savedType }
                val first = if (favs) FAV
                else sections(z, org.value, false).filter { !it.closed }.flatMap { it.groups }.firstOrNull()?.key
                if (first != null) selectGroup(z, first)
            }
        }
    }

    /** Groups split per account, in the chosen order; hidden groups only in edit mode. */
    fun sections(z: Zone<*>, o: OrgData, edit: Boolean): List<GroupSection> {
        val all = Org.applyOrder(z.cats.value.items, o.groupOrder[z.code]) { it.key }
        return accounts.value.map { acc ->
            GroupSection(
                account = acc,
                groups = all.filter {
                    it.accountId == acc.id && (showsHidden(edit) || "${z.code}|${it.key}" !in o.hiddenGroups) &&
                        !com.nova.iptv.data.AgeFilter.groupBlocked(z.code, it.key, it.name)
                },
                closed = "${z.code}|${acc.id}" in o.closedSections,
                error = z.catErrors.value[acc.id],
            )
        }
    }

    fun isGroupHidden(z: Zone<*>, c: Category, o: OrgData) = "${z.code}|${c.key}" in o.hiddenGroups

    /** Hidden things appear (faded) in edit mode only when no parental PIN is set. */
    /** Hidden groups / items never appear in the lists (not even in Edit): only in Settings → Hidden items. */
    @Suppress("UNUSED_PARAMETER")
    fun showsHidden(edit: Boolean) = false

    /** Edit mode: the line whose arrows and Hide are shown (the one the remote, mouse or finger is on). */
    val editActiveKey = MutableStateFlow<String?>(null)

    private fun hiddenToast(name: String) {
        android.util.Log.i("NovaTest", "HIDDEN $name")
        android.widget.Toast.makeText(
            getApplication(), "“$name” hidden — Settings → Hidden items to bring it back", android.widget.Toast.LENGTH_LONG,
        ).show()
    }

    fun toggleSection(z: Zone<*>, accountId: String) =
        updateOrg { it.copy(closedSections = Org.toggle(it.closedSections, "${z.code}|$accountId")) }

    fun toggleGroupHidden(z: Zone<*>, c: Category) {
        val k = "${z.code}|${c.key}"
        val label = if (multiAccount) "${c.name}  ·  ${accountName(c.accountId)}" else c.name
        if (k !in org.value.hiddenGroups) hiddenToast(c.name)
        updateOrg {
            val hiding = k !in it.hiddenGroups
            it.copy(
                hiddenGroups = Org.toggle(it.hiddenGroups, k),
                names = if (hiding) it.names + (k to label) else it.names - k,
            )
        }
    }

    /** Sends a group to the top / bottom of its account section. */
    fun moveGroupTo(z: Zone<*>, c: Category, top: Boolean) {
        val o = org.value
        val full = Org.applyOrder(z.cats.value.items, o.groupOrder[z.code]) { it.key }.map { it.key }
        val section = full.filter { it.startsWith("${c.accountId}|") }
        if (c.key !in section) return
        val reordered = section - c.key
        val newSection = if (top) listOf(c.key) + reordered else reordered + c.key
        var i = 0
        val merged = full.map { k -> if (k.startsWith("${c.accountId}|")) newSection[i++] else k }
        movingKey.value = c.key
        updateOrg { it.copy(groupOrder = it.groupOrder + (z.code to merged)) }
    }

    /** Moves a group up (-1) or down (+1) within its account section. */
    fun moveGroup(z: Zone<*>, c: Category, delta: Int) {
        movingKey.value = c.key
        val o = org.value
        val full = Org.applyOrder(z.cats.value.items, o.groupOrder[z.code]) { it.key }.map { it.key }
        val section = full.filter { it.startsWith("${c.accountId}|") }
        val i = section.indexOf(c.key)
        val j = i + delta
        if (i < 0 || j < 0 || j >= section.size) return
        val a = full.indexOf(section[i])
        val b = full.indexOf(section[j])
        val swapped = full.toMutableList().apply { val t = this[a]; this[a] = this[b]; this[b] = t }
        updateOrg { it.copy(groupOrder = it.groupOrder + (z.code to swapped)) }
    }

    private fun updateOrg(change: (OrgData) -> OrgData) {
        val updated = change(org.value)
        org.value = updated
        storage.org = updated
    }

    private var focusSelectJob: Job? = null

    /** Called when a group is highlighted with the remote: opens it after a short pause. */
    fun <T> focusGroup(z: Zone<T>, key: String) {
        android.util.Log.i("NovaTest", "GROUP focus ${groupTitle(z, key)}")
        if (!storage.openGroupOnFocus || z.selected.value == key) return
        focusSelectJob?.cancel()
        focusSelectJob = viewModelScope.launch {
            delay(350)
            selectGroup(z, key)
        }
    }

    fun <T> selectGroup(z: Zone<T>, key: String) {
        z.selected.value = key
        z.job?.cancel()
        if (key == FAV) {
            z.items.value = ListState(items = favoritesOf(z))
            return
        }
        if (key == NEW) {
            loadRecentlyAdded(z)
            return
        }
        val cat = z.cats.value.items.firstOrNull { it.key == key } ?: return
        val acc = account(cat.accountId) ?: return
        val cached = z.cache[key]
        if (cached != null) {
            z.items.value = ListState(items = cached)
            return
        }
        z.items.value = ListState(loading = true)
        z.job = viewModelScope.launch {
            z.items.value = try {
                val result = z.fetch(acc, cat.id)
                z.cache[key] = result
                ListState(items = result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ListState(error = e.message ?: "Couldn't load")
            }
        }
    }

    /** Group title shown above the list. */
    fun groupTitle(z: Zone<*>, key: String?): String {
        if (key == null) return "Choose a group"
        if (key == FAV) return "★ Favorites"
        if (key == NEW) return "🆕 Recently added"
        val c = z.cats.value.items.firstOrNull { it.key == key } ?: return ""
        return if (multiAccount) "${c.name}  ·  ${accountName(c.accountId)}" else c.name
    }

    fun reloadCurrent() {
        val z = zoneFor(tab.value) ?: return
        val key = z.selected.value
        if (key == NEW) {
            accounts.value.forEach { z.cache.remove("${it.id}|$ALL") }
            selectGroup(z, key)
        } else if (key != null && key != FAV) {
            z.cache.remove(key)
            selectGroup(z, key)
        }
        loadCategories(z)
    }

    // ================================================================ items

    private fun <T> favoritesOf(z: Zone<T>): List<T> =
        favorites.value.filter { it.type == z.savedType && account(it.accountId) != null }.map(z.fromSaved)

    /**
     * Items of the selected group: my order (or the chosen sort), hidden ones only in edit mode.
     * Edit mode always shows my order, so ▲▼ move items in the order they are saved in.
     */
    fun <T> visibleItems(z: Zone<T>, list: List<T>, o: OrgData, edit: Boolean, sort: String = "custom"): List<T> {
        val key = z.selected.value ?: return list
        val shown = when (key) {
            FAV -> list
            NEW -> list.filter { "${z.code}|${z.keyOf(it)}" !in o.hiddenItems }
            else -> {
                val ordered = Org.applyOrder(list, o.itemOrder["${z.code}|$key"], z.keyOf)
                if (showsHidden(edit)) ordered else ordered.filter { "${z.code}|${z.keyOf(it)}" !in o.hiddenItems }
            }
        }
        val names = if (com.nova.iptv.data.AgeFilter.level > 0) groupNamesOf(z) else emptyMap()
        val allowed = if (com.nova.iptv.data.AgeFilter.level == 0) shown else shown.filter { !ageBlocked(z, it, names) }
        if (edit || key == NEW || sort == "custom") return allowed
        return sortItems(z, allowed, sort)
    }

    // ---------- parental age filter (USA ratings) ----------

    private fun groupNamesOf(z: Zone<*>): Map<String, String> = z.cats.value.items.associate { it.key to it.name }

    /** True when the age filter hides this item. */
    fun <T> ageBlocked(z: Zone<T>, item: T, groupNames: Map<String, String>? = null): Boolean {
        if (com.nova.iptv.data.AgeFilter.level == 0) return false
        val key = z.keyOf(item)
        val cat = z.categoryOf(item)
        val gk = if (cat.isBlank()) null else key.substringBefore('|') + "|" + cat
        val gname = gk?.let { (groupNames ?: groupNamesOf(z))[it] }
        return if (z.code == "live") com.nova.iptv.data.AgeFilter.liveBlocked(key, z.nameOf(item), gname, gk)
        else com.nova.iptv.data.AgeFilter.blocked(z.code, key, z.nameOf(item), gname)
    }

    /** Number of items of the open group hidden by age (for the small line above the list). */
    fun <T> ageHiddenCount(z: Zone<T>, list: List<T>): Int {
        if (com.nova.iptv.data.AgeFilter.level == 0) return 0
        val names = groupNamesOf(z)
        return list.count { ageBlocked(z, it, names) }
    }

    fun savedBlocked(s: SavedItem): Boolean = when (s.type) {
        ItemType.LIVE -> com.nova.iptv.data.AgeFilter.liveBlocked(s.key, s.name, null, null)
        ItemType.MOVIE -> com.nova.iptv.data.AgeFilter.blocked("movie", s.key, s.name, null)
        ItemType.SERIES -> com.nova.iptv.data.AgeFilter.blocked("series", s.key, s.name, null)
        else -> false
    }

    val ageStatus = com.nova.iptv.data.AgeFilter.status
    private var ageJob: Job? = null

    /** Saves the age settings and (re)starts the age check in the background. */
    fun setAge(change: (com.nova.iptv.data.Storage) -> Unit) {
        change(storage)
        com.nova.iptv.data.AgeFilter.configure(storage)
        saveFilterFiles()
        checkAges()
    }

    private var filterFilesJob: Job? = null

    /** Download/NovaIPTV/Filter: settings (sealed) and hidden-list.txt, written a moment after the last change. */
    fun saveFilterFiles() {
        com.nova.iptv.data.AgeFilter.saveSettings(nova, storage)
        filterFilesJob?.cancel()
        val names = ageGroupNames()
        filterFilesJob = viewModelScope.launch(Dispatchers.Default) {
            delay(3000)
            com.nova.iptv.data.AgeFilter.writeHiddenList(nova, accounts.value, names)
        }
    }

    fun checkAges() {
        ageJob?.cancel()
        val names = ageGroupNames()
        ageJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                com.nova.iptv.data.AgeFilter.check(nova, nova.http, accounts.value)
                com.nova.iptv.data.AgeFilter.writeHiddenList(nova, accounts.value, names)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.i("NovaTest", "AGES check error: $e")
            }
        }
    }

    /** "movie|acc|catId" → group name, for the three zones (loads the groups when needed). */
    fun ageGroupNames(): Map<String, String> {
        val out = HashMap<String, String>()
        listOf(live, films, series).forEach { z ->
            if (z.cats.value.items.isEmpty() && !z.cats.value.loading && accounts.value.isNotEmpty()) loadCategories(z)
            z.cats.value.items.forEach { out["${z.code}|${it.key}"] = it.name }
        }
        return out
    }

    /** Allows a hidden title anyway (or hides it again). */
    fun toggleAgeException(exKey: String) {
        val cur = storage.ageAllowed
        storage.ageAllowed = if (exKey in cur) cur - exKey else cur + exKey
        com.nova.iptv.data.AgeFilter.configure(storage)
        saveFilterFiles()
    }

    /** Live groups of all accounts (for "Only groups I allow"). */
    fun liveGroupsForAge(): List<Category> {
        if (live.cats.value.items.isEmpty() && !live.cats.value.loading) loadCategories(live)
        return live.cats.value.items
    }

    private fun <T> sortItems(z: Zone<T>, list: List<T>, sort: String): List<T> = when (sort) {
        "added" -> list.sortedByDescending { z.sortInfo(it).added }
        "year" -> list.sortedWith(
            compareByDescending<T> { z.sortInfo(it).year ?: 0 }.thenByDescending { z.sortInfo(it).added }
        )
        "rating" -> list.sortedByDescending { z.sortInfo(it).rating ?: -1.0 }
        "number" -> list.sortedBy { z.sortInfo(it).number.takeIf { n -> n > 0 } ?: Int.MAX_VALUE }
        "name" -> list.sortedBy { sortName(z.nameOf(it)) }
        else -> list
    }

    /** Name without the provider's language tags ("|FR| ", "[MULTI] ", "EN - "), for A → Z. */
    private fun sortName(name: String): String =
        name.replace(tagPrefix, "").trim().lowercase()

    private val tagPrefix = Regex("""^(\s*([|\[(][^|\])]{1,14}[|\])]|[A-Z]{2,5}\s*[-:|])\s*)+""")

    /** Newest films / series of all accounts (hidden groups and items left out). */
    private fun <T> loadRecentlyAdded(z: Zone<T>) {
        val accs = accounts.value
        z.items.value = ListState(loading = true)
        z.job = viewModelScope.launch {
            val results = accs.map { a ->
                async {
                    try { Result.success(allOf(z, a)) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
                }
            }.awaitAll()
            val o = org.value
            val all = results.flatMap { it.getOrNull().orEmpty() }
            if (all.isEmpty() && results.any { it.isFailure }) {
                z.items.value = ListState(error = results.firstNotNullOfOrNull { it.exceptionOrNull()?.message } ?: "Couldn't load")
                return@launch
            }
            val newest = withContext(Dispatchers.Default) {
                all.filter { item ->
                    val acc = z.keyOf(item).substringBefore('|')
                    "${z.code}|$acc|${z.categoryOf(item)}" !in o.hiddenGroups
                }.sortedWith(
                    compareByDescending<T> { z.sortInfo(it).added }.thenByDescending { z.sortInfo(it).year ?: 0 }
                ).take(RECENTLY_ADDED_COUNT)
            }
            z.items.value = ListState(items = newest)
        }
    }

    fun <T> isItemHidden(z: Zone<T>, item: T, o: OrgData) = "${z.code}|${z.keyOf(item)}" in o.hiddenItems

    fun <T> toggleItemHidden(z: Zone<T>, item: T) {
        val k = "${z.code}|${z.keyOf(item)}"
        val acc = z.keyOf(item).substringBefore('|')
        val group = z.selected.value?.let { groupTitle(z, it) }
        val label = (if (multiAccount) "${z.nameOf(item)}  ·  ${accountName(acc)}" else z.nameOf(item)) +
            (group?.let { "  ($it)" } ?: "")
        if (k !in org.value.hiddenItems) hiddenToast(z.nameOf(item))
        updateOrg {
            val hiding = k !in it.hiddenItems
            it.copy(
                hiddenItems = Org.toggle(it.hiddenItems, k),
                names = if (hiding) it.names + (k to label) else it.names - k,
            )
        }
    }

    /** All hidden groups and items, sorted by zone, then groups before items, then name. */
    fun hiddenEntries(o: OrgData): List<HiddenEntry> {
        val zoneLabel = mapOf("live" to "Live TV", "movie" to "Films", "series" to "Series")
        val order = listOf("live", "movie", "series")
        fun entry(k: String, group: Boolean): HiddenEntry {
            val code = k.substringBefore('|')
            return HiddenEntry(k, group, zoneLabel[code] ?: code, o.names[k] ?: k.substringAfterLast('|'))
        }
        return (o.hiddenGroups.map { entry(it, true) } + o.hiddenItems.map { entry(it, false) })
            .sortedWith(
                compareBy<HiddenEntry>({ order.indexOf(it.orgKey.substringBefore('|')) }, { !it.isGroup }, { it.name.lowercase() })
            )
    }

    fun unhide(e: HiddenEntry) = updateOrg {
        it.copy(
            hiddenGroups = it.hiddenGroups - e.orgKey,
            hiddenItems = it.hiddenItems - e.orgKey,
            names = it.names - e.orgKey,
        )
    }

    /** Moves an item up / down. [shown] is the list as currently displayed (edit mode shows all). */
    /** Sends an item to the top / bottom of its group. */
    fun <T> moveItemTo(z: Zone<T>, shown: List<T>, item: T, top: Boolean) {
        val keys = shown.map(z.keyOf)
        val k = z.keyOf(item)
        val i = keys.indexOf(k)
        if (i < 0) return
        val steps = if (top) i else keys.lastIndex - i
        if (steps == 0) return
        val group = z.selected.value ?: return
        if (group != FAV) {
            val reordered = keys - k
            val moved = if (top) listOf(k) + reordered else reordered + k
            movingKey.value = k
            updateOrg { it.copy(itemOrder = it.itemOrder + ("${z.code}|$group" to moved)) }
            return
        }
        // Favorites: step by step (their order is kept with the favorites themselves).
        var current = shown
        repeat(steps) {
            moveItem(z, current, item, if (top) -1 else 1)
            val ks = current.map(z.keyOf).toMutableList()
            val a = ks.indexOf(k)
            val b = a + if (top) -1 else 1
            current = current.toMutableList().also { l -> val t = l[a]; l[a] = l[b]; l[b] = t }
        }
    }

    fun <T> moveItem(z: Zone<T>, shown: List<T>, item: T, delta: Int) {
        movingKey.value = z.keyOf(item)
        val key = z.selected.value ?: return
        val keys = shown.map(z.keyOf)
        val moved = Org.move(keys, z.keyOf(item), delta)
        if (key == FAV) {
            // Reorder the favorites of this zone, keeping the other zones' favorites where they are.
            val all = favorites.value
            val mine = all.filter { it.type == z.savedType }
            val byKey = mine.associateBy { it.key }
            val reordered = moved.mapNotNull { byKey[it] } + mine.filter { it.key !in moved }
            var idx = 0
            val merged = all.map { if (it.type == z.savedType) reordered[idx++] else it }
            storage.favorites = merged
            favorites.value = merged
            z.items.value = ListState(items = favoritesOf(z))
        } else {
            updateOrg { it.copy(itemOrder = it.itemOrder + ("${z.code}|$key" to moved)) }
        }
    }

    fun showAllHidden(z: Zone<*>) = updateOrg { o ->
        o.copy(
            hiddenGroups = o.hiddenGroups.filterNot { it.startsWith("${z.code}|") }.toSet(),
            hiddenItems = o.hiddenItems.filterNot { it.startsWith("${z.code}|") }.toSet(),
        )
    }

    fun hiddenCount(z: Zone<*>, o: OrgData): Int =
        o.hiddenGroups.count { it.startsWith("${z.code}|") } + o.hiddenItems.count { it.startsWith("${z.code}|") }

    // ================================================================ EPG preview

    fun focusChannel(ch: LiveChannel) {
        if (focusedEpg.value?.first?.key == ch.key) return
        val acc = account(ch.accountId) ?: return
        focusedEpg.value = ch to null
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            delay(450)
            val list = try { api.shortEpg(acc, ch.streamId) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            if (focusedEpg.value?.first?.key == ch.key) focusedEpg.value = ch to list
        }
    }

    // ================================================================ series

    fun openSeries(s: Series) {
        restoreFocusKey.value = s.key
        val acc = account(s.accountId) ?: return
        seriesDetail.value = SeriesDetailState(s)
        seriesJob?.cancel()
        seriesJob = viewModelScope.launch {
            try {
                val info = api.seriesInfo(acc, s)
                seriesDetail.value = SeriesDetailState(s, loading = false, info = info, season = info.seasons.firstOrNull())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                seriesDetail.value = SeriesDetailState(s, loading = false, error = e.message ?: "Couldn't load episodes")
            }
        }
    }

    // ================================================================ films

    /** Poster to put the remote back on when a film / series page closes. */
    val restoreFocusKey = MutableStateFlow<String?>(null)
    fun clearRestoreFocus() { restoreFocusKey.value = null }

    /** The TV opens with the remote on the first tab — only once, not every time a page closes. */
    var initialTabFocusDone = false

    fun openMovie(m: Movie) {
        restoreFocusKey.value = m.key
        val acc = account(m.accountId) ?: return
        movieDetail.value = MovieDetailState(m)
        movieJob?.cancel()
        movieJob = viewModelScope.launch {
            val details = try {
                api.movieInfo(acc, m)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MediaDetails()
            }
            if (movieDetail.value?.movie?.key == m.key) movieDetail.value = MovieDetailState(m, loading = false, details = details)
        }
    }

    // ---------- coming back after a video: same tab, same search, same film / series page ----------

    fun saveReturnPoint() {
        storage.returnPoint = listOf(
            System.currentTimeMillis().toString(), tab.value.name, query.value,
            movieDetail.value?.movie?.key.orEmpty(), seriesDetail.value?.series?.key.orEmpty(),
        ).joinToString("\u0001")
    }

    /** Puts back the place saved before the video. False when there is none (or it is too old). */
    fun restoreReturnPoint(): Boolean {
        val raw = storage.returnPoint ?: return false
        storage.returnPoint = null
        val p = raw.split('\u0001')
        if (p.size < 5) return false
        val at = p[0].toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - at > 6 * 3600_000L) return false
        val t = runCatching { Tab.valueOf(p[1]) }.getOrNull() ?: return false
        val movieKey = p[3]
        val seriesKey = p[4]
        val sameDetail = (movieKey.isEmpty() || movieDetail.value?.movie?.key == movieKey) &&
            (seriesKey.isEmpty() || seriesDetail.value?.series?.key == seriesKey)
        if (tab.value == t && sameDetail && (t != Tab.SEARCH || query.value == p[2])) return true
        android.util.Log.i("NovaTest", "RETURN to ${t.name}${if (p[2].isNotBlank()) " \"" + p[2] + "\"" else ""}")
        if (tab.value != t) setTab(t)
        if (t == Tab.SEARCH && p[2].isNotBlank() && query.value != p[2]) setQuery(p[2])
        fun accIdx(key: String) = com.nova.iptv.data.SearchIndex.get(key.substringBefore('|'))
        if (movieKey.isNotEmpty() && movieDetail.value?.movie?.key != movieKey) {
            (accIdx(movieKey)?.movies?.firstOrNull { it.key == movieKey }
                ?: films.items.value.items.firstOrNull { it.key == movieKey })?.let { openMovie(it) }
        }
        if (seriesKey.isNotEmpty() && seriesDetail.value?.series?.key != seriesKey) {
            (accIdx(seriesKey)?.series?.firstOrNull { it.key == seriesKey }
                ?: series.items.value.items.firstOrNull { it.key == seriesKey })?.let { openSeries(it) }
        }
        return true
    }

    fun closeMovie() {
        movieJob?.cancel()
        movieDetail.value = null
    }

    /** The episode of this series watched last: (season, index in that season), if any. */
    fun resumeEpisode(info: SeriesInfo): Pair<Int, Int>? {
        val watched = recent.value.filter { it.type == ItemType.EPISODE && it.accountId == info.series.accountId }
        for (r in watched) {
            for ((season, eps) in info.episodes) {
                val i = eps.indexOfFirst { it.id == r.id }
                if (i >= 0) return season to i
            }
        }
        return null
    }

    // ================================================================ now on air (live rows)

    private val nowCache = HashMap<String, Pair<Long, EpgEntry?>>()
    private val nowGate = Semaphore(4)

    /** Programme on air now on a channel, for the line under its name (cached, max 4 requests at a time). */
    suspend fun nowPlaying(ch: LiveChannel): EpgEntry? {
        val now = System.currentTimeMillis()
        nowCache[ch.key]?.let { (fetched, e) ->
            if (e != null && now < e.end) return e
            if (e == null && now - fetched < 10 * 60_000) return null
        }
        val acc = account(ch.accountId) ?: return null
        val entry = nowGate.withPermit {
            try {
                XtreamApi.nowNext(api.shortEpg(acc, ch.streamId)).first
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        nowCache[ch.key] = now to entry
        return entry
    }

    fun selectSeason(season: Int) {
        seriesDetail.value = seriesDetail.value?.copy(season = season)
    }

    fun closeSeries() {
        seriesJob?.cancel()
        seriesDetail.value = null
    }

    // ================================================================ favorites & recent

    fun refreshLocal() {
        favorites.value = storage.favorites
        recent.value = storage.recent
        listOf<Zone<*>>(live, films, series).forEach { z ->
            if (z.selected.value == FAV) refreshFavGroup(z)
        }
    }

    private fun <T> refreshFavGroup(z: Zone<T>) {
        z.items.value = ListState(items = favoritesOf(z))
    }

    fun isFavorite(item: SavedItem) = favorites.value.any { it.sameAs(item) }

    fun toggleFavorite(item: SavedItem) {
        val added = storage.toggleFavorite(item)
        refreshLocal()
        android.util.Log.i("NovaTest", "FAVORITE ${if (added) "added" else "removed"}: ${item.name}")
        android.widget.Toast.makeText(
            getApplication(),
            if (added) "★ Added to Favorites: ${item.name}" else "Removed from Favorites: ${item.name}",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    fun addRecent(item: SavedItem) {
        storage.addRecent(item)
        recent.value = storage.recent
    }

    fun clearRecent() {
        storage.clearRecent()
        recent.value = emptyList()
    }

    fun hasResume(item: SavedItem): Boolean = storage.getPosition(item.resumeKey) > 0

    fun clearResume(item: SavedItem) = storage.setPosition(item.resumeKey, 0)

    // ================================================================ search (all accounts)

    /** Search filters (remembered): what to look for and in which server. */
    val searchKind = MutableStateFlow(storage.searchKind)
    val searchAccount = MutableStateFlow(storage.searchAccount)

    fun setSearchKind(kind: String) {
        searchKind.value = kind
        storage.searchKind = kind
        android.util.Log.i("NovaTest", "SEARCH look for $kind")
        setQuery(query.value)
    }

    fun setSearchAccount(id: String?) {
        searchAccount.value = id
        storage.searchAccount = id
        android.util.Log.i("NovaTest", "SEARCH in ${id?.let { accountName(it) } ?: "all servers"}")
        setQuery(query.value)
    }

    fun setQuery(q: String) {
        query.value = q
        searchJob?.cancel()
        val text = q.trim()
        if (text.length < 2) {
            search.value = SearchResults()
            return
        }
        val only = searchAccount.value?.takeIf { id -> accounts.value.any { it.id == id } }
        val accs = accounts.value.filter { only == null || it.id == only }
        val kind = searchKind.value
        val o = org.value
        search.value = search.value.copy(loading = true, error = null)
        searchJob = viewModelScope.launch {
            delay(if (accs.all { com.nova.iptv.data.SearchIndex.get(it.id) != null }) 200 else 500)
            try {
                val lives = mutableListOf<LiveChannel>()
                val movies = mutableListOf<Movie>()
                val seriesList = mutableListOf<Series>()
                val failures = mutableListOf<String>()
                accs.forEach { a ->
                    try {
                        // The search index answers at once; without it (first start), the lists are fetched.
                        val idx = com.nova.iptv.data.SearchIndex.get(a.id)
                        if (kind == "all" || kind == "live") lives += idx?.live ?: allOf(live, a)
                        if (kind == "all" || kind == "movie") movies += idx?.movies ?: allOf(films, a)
                        if (kind == "all" || kind == "series") seriesList += idx?.series ?: allOf(series, a)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failures += "${a.name}: ${e.message}"
                    }
                }
                val result = withContext(Dispatchers.Default) {
                    val liveNames = groupNamesOf(live); val filmNames = groupNamesOf(films); val seriesNames = groupNamesOf(series)
                    fun hidden(code: String, key: String) = "$code|$key" in o.hiddenItems
                    SearchResults(
                        channels = lives.filter { it.name.contains(text, true) && !hidden("live", it.key) && !ageBlocked(live, it, liveNames) }.take(150),
                        movies = movies.filter { it.name.contains(text, true) && !hidden("movie", it.key) && !ageBlocked(films, it, filmNames) }.take(150),
                        series = seriesList.filter { it.name.contains(text, true) && !hidden("series", it.key) && !ageBlocked(series, it, seriesNames) }.take(150),
                        error = failures.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                    )
                }
                search.value = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                search.value = SearchResults(error = e.message ?: "Search failed")
            }
        }
    }

    private suspend fun <T> allOf(z: Zone<T>, a: Account): List<T> {
        val key = "${a.id}|$ALL"
        z.cache[key]?.let { return it }
        val list = z.fetch(a, null)
        z.cache[key] = list
        return list
    }

    // ================================================================ building play items

    fun saved(ch: LiveChannel) = SavedItem(ItemType.LIVE, ch.streamId, ch.name, ch.icon, accountId = ch.accountId)
    fun saved(m: Movie) = SavedItem(ItemType.MOVIE, m.streamId, m.name, m.icon, m.extension, accountId = m.accountId)
    fun saved(s: Series) = SavedItem(ItemType.SERIES, s.seriesId, s.name, s.cover, accountId = s.accountId)
    fun saved(e: Episode, s: Series) = SavedItem(
        ItemType.EPISODE, e.id, "${s.name} · S${e.season} E${e.episodeNum} ${e.title}".trim(),
        e.image ?: s.cover, e.extension, accountId = s.accountId,
    )

    fun liveItems(list: List<LiveChannel>): List<PlayItem> {
        val fmt = storage.liveFormat
        return list.mapNotNull { ch ->
            val acc = account(ch.accountId) ?: return@mapNotNull null
            PlayItem(saved(ch), api.liveUrl(acc, ch.streamId, fmt), isLive = true)
        }
    }

    fun movieItem(m: Movie): PlayItem? {
        val acc = account(m.accountId) ?: return null
        val url = api.movieUrl(acc, m.streamId, m.extension)
        return PlayItem(
            saved(m), url, isLive = false,
            subtitle = m.rating?.let { "Rating $it" }, downloadExt = m.extension,
            downloadId = runningDownloadOf(url),
        )
    }

    /**
     * This film / episode is downloading right now: play it from the download (Watch now) instead of opening
     * a second connection to the server, which most accounts refuse (black screen, "0 MB on disk").
     */
    private fun runningDownloadOf(url: String): String? {
        val d = DownloadCenter.items.value.firstOrNull { it.url == url } ?: return null
        if (d.status != com.nova.iptv.data.DownloadStatus.DOWNLOADING && d.status != com.nova.iptv.data.DownloadStatus.QUEUED) return null
        return DownloadCenter.watchNow(nova, d.title, d.url, d.path.substringAfterLast('.', "mp4"), d.kind, d.icon).id
    }

    fun episodeItems(info: SeriesInfo, season: Int): List<PlayItem> {
        val acc = account(info.series.accountId) ?: return emptyList()
        return info.episodes[season].orEmpty().map {
            val url = api.episodeUrl(acc, it.id, it.extension)
            PlayItem(
                saved(it, info.series), url, isLive = false,
                subtitle = it.plot, downloadExt = it.extension, seriesKey = info.series.key,
                downloadId = DownloadCenter.items.value.firstOrNull { d -> d.url == url &&
                    (d.status == com.nova.iptv.data.DownloadStatus.DOWNLOADING || d.status == com.nova.iptv.data.DownloadStatus.QUEUED) }?.id,
            )
        }
    }

    /**
     * Every episode of every season, in order (so "next" after the last episode of a season is E1 of the
     * next season), and where episode [index] of [season] is in it.
     */
    fun allEpisodeItems(info: SeriesInfo, season: Int, index: Int): Pair<List<PlayItem>, Int> {
        val all = mutableListOf<PlayItem>()
        var at = 0
        info.seasons.forEach { s ->
            if (s == season) at = all.size + index
            all += episodeItems(info, s)
        }
        return all to at.coerceIn(0, (all.size - 1).coerceAtLeast(0))
    }

    fun autoNext(info: SeriesInfo): Boolean = storage.autoNext(info.series.key)
    fun setAutoNext(info: SeriesInfo, on: Boolean) = storage.setAutoNext(info.series.key, on)

    /** Play items for saved entries (recent). Series entries are opened instead. */
    fun savedItemsToPlay(list: List<SavedItem>): List<PlayItem> {
        val fmt = storage.liveFormat
        return list.mapNotNull { s ->
            val acc = account(s.accountId) ?: return@mapNotNull null
            when (s.type) {
                ItemType.LIVE -> PlayItem(s, api.liveUrl(acc, s.id, fmt), isLive = true)
                ItemType.MOVIE -> PlayItem(s, api.movieUrl(acc, s.id, s.extension ?: "mp4"), isLive = false, downloadExt = s.extension ?: "mp4")
                ItemType.EPISODE -> PlayItem(s, api.episodeUrl(acc, s.id, s.extension ?: "mp4"), isLive = false, downloadExt = s.extension ?: "mp4")
                else -> null
            }
        }
    }

    fun seriesFromSaved(s: SavedItem) = Series(s.accountId, s.id, s.name, s.icon, "", null, null)

    // ================================================================ settings

    private fun readSettings() = SettingsState(
        liveFormat = storage.liveFormat,
        cushionSeconds = storage.cushionSeconds,
        maxBufferSeconds = storage.maxBufferSeconds,
        vodCacheMb = storage.vodCacheMb,
        userAgent = storage.userAgent,
        usePublicFolder = storage.usePublicFolder,
        liveDiskBuffer = storage.liveDiskBuffer,
        liveDiskMb = storage.liveDiskMb,
        autoSkip = storage.autoSkip,
        skipTimeoutSec = storage.skipTimeoutSec,
        storageRoot = storage.storageRoot,
        tunneling = storage.tunneling,
        clockPosition = storage.clockPosition,
        groupsAlwaysLeft = storage.groupsAlwaysLeft,
        openGroupOnFocus = storage.openGroupOnFocus,
        autoPlayLast = storage.autoPlayLast,
        hasPin = storage.hasPin,
        playerMode = storage.playerMode,
        externalPlayer = storage.externalPlayer,
        audioDecoder = storage.audioDecoder,
        externalOnError = storage.externalOnError,
        videoDecoder = storage.videoDecoder,
        bufferMb = storage.bufferMb,
        audioOffload = storage.audioOffload,
        videoSurface = storage.videoSurface,
        requireVpn = storage.requireVpn,
        players = listOf("live", "movie", "series", "catchup", "recordings").associateWith { storage.playerFor(it) },
        sorts = listOf("live", "movie", "series").associateWith { storage.sortFor(it) },
        posterColumnsPhone = storage.posterColumnsPhone,
        posterColumnsTv = storage.posterColumnsTv,
        posterSize = storage.posterSize,
        posterTitles = storage.posterTitles,
        liveRowSize = storage.liveRowSize,
        liveLogos = storage.liveLogos,
        liveNowLine = storage.liveNowLine,
        liveColumns = storage.liveColumns,
        doubleBackLive = storage.doubleBackLive,
        doubleBackExit = storage.doubleBackExit,
        numberKeys = storage.numberKeys,
        stayInPosters = storage.stayInPosters,
        liveStart = storage.liveStart,
        readAheadMb = storage.readAheadMb,
        overlayTransparency = storage.overlayTransparency,
    )

    fun setOverlayTransparency(v: Int) { storage.overlayTransparency = v; settings.value = readSettings() }

    fun setDoubleBackLive(v: Boolean) { storage.doubleBackLive = v; settings.value = readSettings() }
    fun setDoubleBackExit(v: Boolean) { storage.doubleBackExit = v; settings.value = readSettings() }
    fun setNumberKeys(v: Boolean) { storage.numberKeys = v; settings.value = readSettings() }
    fun setStayInPosters(v: Boolean) { storage.stayInPosters = v; settings.value = readSettings() }
    fun setLiveStart(v: String) { storage.liveStart = v; settings.value = readSettings() }

    /** Storage for the remote-control settings page. */
    val appStorage get() = storage

    /** Recently watched channels, last one first (for the "last watched channel" button). */
    fun lastChannelItems(): List<PlayItem>? =
        savedItemsToPlay(storage.recent.filter { it.type == ItemType.LIVE && account(it.accountId) != null })
            .takeIf { it.isNotEmpty() }

    fun setSort(zone: String, v: String) { storage.setSortFor(zone, v); settings.value = readSettings() }
    fun setPosterColumns(tv: Boolean, v: Int) {
        if (tv) storage.posterColumnsTv = v.coerceIn(0, 10) else storage.posterColumnsPhone = v.coerceIn(0, 10)
        settings.value = readSettings()
    }
    fun setPosterSize(v: String) { storage.posterSize = v; settings.value = readSettings() }
    fun setPosterTitles(v: Boolean) { storage.posterTitles = v; settings.value = readSettings() }
    fun setLiveRowSize(v: String) { storage.liveRowSize = v; settings.value = readSettings() }
    fun setLiveLogos(v: Boolean) { storage.liveLogos = v; settings.value = readSettings() }
    fun setLiveNowLine(v: Boolean) { storage.liveNowLine = v; settings.value = readSettings() }
    fun setLiveColumns(v: Int) { storage.liveColumns = v.coerceIn(1, 2); settings.value = readSettings() }

    fun setLiveFormat(v: String) { storage.liveFormat = v; settings.value = readSettings() }
    fun setCushion(v: Int) { storage.cushionSeconds = v.coerceIn(2, 30); settings.value = readSettings() }
    fun setMaxBuffer(v: Int) { storage.maxBufferSeconds = v.coerceIn(30, 120); settings.value = readSettings() }
    fun setVodCache(v: Int) { storage.vodCacheMb = v.coerceIn(256, 4096); settings.value = readSettings() }
    fun setUserAgent(v: String) { storage.userAgent = v; settings.value = readSettings() }
    fun setLiveDiskBuffer(v: Boolean) { storage.liveDiskBuffer = v; settings.value = readSettings() }
    fun setLiveDiskMb(v: Int) { storage.liveDiskMb = v.coerceIn(200, 8000); settings.value = readSettings() }
    fun setAutoSkip(v: Boolean) { storage.autoSkip = v; settings.value = readSettings() }
    fun setSkipTimeout(v: Int) { storage.skipTimeoutSec = v.coerceIn(8, 60); settings.value = readSettings() }
    fun setTunneling(v: Boolean) { storage.tunneling = v; settings.value = readSettings() }
    fun setClockPosition(v: String) { storage.clockPosition = v; settings.value = readSettings() }
    fun setGroupsAlwaysLeft(v: Boolean) { storage.groupsAlwaysLeft = v; settings.value = readSettings() }
    fun setOpenGroupOnFocus(v: Boolean) { storage.openGroupOnFocus = v; settings.value = readSettings() }
    fun setAutoPlayLast(v: Boolean) { storage.autoPlayLast = v; settings.value = readSettings() }
    fun setPlayerMode(v: String) { storage.playerMode = v; settings.value = readSettings() }
    fun setExternalPlayer(v: String?) { storage.externalPlayer = v; settings.value = readSettings() }
    fun setAudioDecoder(v: String) { storage.audioDecoder = v; settings.value = readSettings() }

    /** "builtin", "ask" or an app package, from Settings → Player selection. */
    fun playerChoice(item: PlayItem): String = storage.playerFor(item.kind)

    /** External app chosen for films (null = ask each time). */
    fun externalPlayerPackage(): String? =
        storage.playerFor("movie").takeIf { it != "builtin" && it != "ask" }

    fun playerFor(kind: String) = storage.playerFor(kind)

    fun setPlayerFor(kind: String, value: String) {
        storage.setPlayerFor(kind, value)
        settings.value = readSettings()
    }

    fun setExternalOnError(v: Boolean) { storage.externalOnError = v; settings.value = readSettings() }
    fun setVideoDecoder(v: String) { storage.videoDecoder = v; settings.value = readSettings() }
    fun setBufferMb(v: Int) { storage.bufferMb = v; settings.value = readSettings() }
    fun setAudioOffload(v: Boolean) { storage.audioOffload = v; settings.value = readSettings() }
    fun setVideoSurface(v: String) { storage.videoSurface = v; settings.value = readSettings() }
    fun setRequireVpn(v: Boolean) { storage.requireVpn = v; settings.value = readSettings() }

    // ================================================================ catch-up

    suspend fun catchupFor(ch: LiveChannel): List<com.nova.iptv.data.CatchupEntry> {
        val acc = account(ch.accountId) ?: return emptyList()
        return api.catchup(acc, ch.streamId)
    }

    fun catchupItem(ch: LiveChannel, e: com.nova.iptv.data.CatchupEntry): PlayItem? {
        val acc = account(ch.accountId) ?: return null
        val time = XtreamApi.formatTime(e.start)
        return PlayItem(
            SavedItem("catchup", "${ch.streamId}_${e.start}", "${ch.name} · $time ${e.title}".trim(), ch.icon, accountId = ch.accountId),
            api.catchupUrl(acc, ch.streamId, e),
            isLive = false,
            subtitle = e.description.ifBlank { null },
            catchup = true,
        )
    }

    // ================================================================ parental control

    fun checkPin(pin: String) = !storage.hasPin || storage.checkPin(pin)

    fun setPin(pin: String?) {
        storage.setPin(pin)
        settings.value = readSettings()
        com.nova.iptv.data.AgeFilter.saveSettings(nova, storage)
    }

    // ================================================================ backup

    private fun backupDir() = Folders.keepDir(nova, "Backups")

    fun backupFileName(): String =
        "NovaIPTV-" + java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".bak"

    fun listsFileName(): String =
        "Nova-" + java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US).format(java.util.Date()) + "_list.bakL"

    /** Saves the lists only (order, favorites, hidden items) in Download/NovaIPTV/Backups. */
    /** Saves the lists; (null, why) when there is nothing to save yet. */
    fun saveLists(): Pair<File?, String> {
        val what = storage.currentListsSummary()
        if (what.isBlank()) return null to "No favorites, order or hidden items to save yet"
        val f = File(backupDir(), listsFileName())
        f.writeText(storage.exportLists())
        android.util.Log.i("NovaTest", "LISTS saved ${f.name}: $what")
        return f to what
    }

    fun listsSummary(f: File): String = runCatching { storage.listsSummary(f.readText()) }.getOrDefault("")

    fun listsFiles(): List<File> =
        backupDir().listFiles { f -> f.name.endsWith(".bakL", ignoreCase = true) }?.sortedByDescending { it.lastModified() }.orEmpty()

    fun restoreLists(text: String) {
        storage.importLists(text)
        org.value = storage.org
        favorites.value = storage.favorites
        android.util.Log.i("NovaTest", "LISTS restored")
    }

    /** Film / series page for the "Hidden by age" list: details from your server (poster, year, synopsis…). */
    suspend fun ageInfo(code: String, key: String): Pair<com.nova.iptv.data.MediaDetails, String?>? = withContext(Dispatchers.IO) {
        val accId = key.substringBefore('|')
        val acc = account(accId) ?: return@withContext null
        val idx = com.nova.iptv.data.SearchIndex.get(accId)
        if (code == "movie") {
            val m = idx?.movies?.firstOrNull { it.key == key } ?: return@withContext null
            val d = runCatching { api.movieInfo(acc, m) }.getOrNull() ?: com.nova.iptv.data.MediaDetails()
            d to (d.poster ?: m.icon)
        } else {
            val s = idx?.series?.firstOrNull { it.key == key } ?: return@withContext null
            val info = runCatching { api.seriesInfo(acc, s) }.getOrNull()
            val d = (info?.details ?: com.nova.iptv.data.MediaDetails()).let {
                it.copy(plot = it.plot ?: info?.plot ?: s.plot, genre = it.genre ?: info?.genre)
            }
            d to (d.poster ?: s.cover)
        }
    }

    /** Live TV: ▶ from the groups asks the channel list to put the remote on its first channel. */
    val liveTopAsk = MutableStateFlow(0)

    fun backupText(password: String? = null): String =
        storage.exportBackup().let { if (password.isNullOrEmpty()) it else com.nova.iptv.data.NovaCrypto.encrypt(it, password) }

    /** Saves a .bak file in the Backups folder and returns it (protected when [password] is given). */
    fun saveBackup(password: String? = null): File {
        val dir = backupDir()
        val file = File(dir, backupFileName())
        file.writeText(backupText(password))
        android.util.Log.i("NovaTest", "BACKUP saved ${if (password.isNullOrEmpty()) "" else "(password-protected) "}${file.name}")
        return file
    }

    /** Downloads a backup from any web link (blocking: call from a background thread). */
    fun downloadBackup(link: String): String = com.nova.iptv.data.LinkFetcher.fetchText(nova.http, link, storage.userAgent)

    fun rememberBackupLink(link: String) {
        storage.lastBackupLink = link.trim()
        storage.lastBackupLinkAt = System.currentTimeMillis()
    }

    val lastBackupLink: String? get() = storage.lastBackupLink
    val lastBackupLinkAt: Long get() = storage.lastBackupLinkAt
    val accountsLink: String? get() = storage.accountsLink
    val accountsLinkNote: String? get() = storage.accountsLinkNote
    val accountsLinkCheckedAt: Long get() = storage.accountsLinkCheckedAt

    /** True when [text] is a Nova backup (plain). */
    fun isBackup(text: String): Boolean = runCatching { BackupJson(text).optString("app") == "NovaIPTV" }.getOrDefault(false)

    fun backupFiles(): List<File> =
        backupDir().listFiles { f -> f.isFile && f.name.endsWith(".bak", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    /** Replaces everything with the backup's content and reloads the app. */
    fun restoreBackup(text: String) {
        storage.importBackup(text)
        com.nova.iptv.data.Locations.init(getApplication(), storage) // older backups: internal storage, rating sort, 100 MB
        Folders.customRoot = storage.storageRoot
        DownloadCenter.init(storage)
        accounts.value = storage.accounts
        org.value = storage.org
        settings.value = readSettings()
        userInfos.value = emptyMap()
        editMode.value = false
        if (accounts.value.isEmpty()) {
            listOf<Zone<*>>(live, films, series).forEach { it.clear() }
            showLogin.value = true
        } else {
            showLogin.value = false
            tab.value = Tab.LIVE
            reloadEverything()
        }
        welcomeForm.value = false
        android.util.Log.i("NovaTest", "BACKUP restored")
        runStartup()
    }

    // ================================================================ start-up (auto-play)

    /** The last watched channel or video (channels come with the other recent channels for zapping). */
    fun autoPlayItems(): List<PlayItem>? {
        if (!storage.autoPlayLast) return null
        val recentList = storage.recent.filter { account(it.accountId) != null }
        val first = recentList.firstOrNull() ?: return null
        return if (first.type == ItemType.LIVE) {
            savedItemsToPlay(recentList.filter { it.type == ItemType.LIVE }).takeIf { it.isNotEmpty() }
        } else {
            savedItemsToPlay(listOf(first)).takeIf { it.isNotEmpty() }
        }
    }

    // ================================================================ storage locations

    /** Files that are in the old folder of a location you just changed. */
    data class MoveOffer(val moves: List<Triple<DownloadItem, File, File>>, val bytes: Long)

    val moveOffer = MutableStateFlow<MoveOffer?>(null)
    val moveProgress = MutableStateFlow<String?>(null)

    /** Changes a location; if downloads now sit in the old place, offers to move them. */
    fun setLocation(kind: com.nova.iptv.data.Locations.Kind, loc: com.nova.iptv.data.Locations.Location) {
        val L = com.nova.iptv.data.Locations
        val kinds = listOf(com.nova.iptv.data.Locations.Kind.MOVIES, com.nova.iptv.data.Locations.Kind.SERIES, com.nova.iptv.data.Locations.Kind.RECORDINGS)
        val before: Map<com.nova.iptv.data.Locations.Kind, File?> = kinds.associateWith { runCatching { L.dir(it) }.getOrNull() }
        L.set(kind, loc)
        val after: Map<com.nova.iptv.data.Locations.Kind, File?> = kinds.associateWith { runCatching { L.dir(it) }.getOrNull() }
        val moves = mutableListOf<Triple<DownloadItem, File, File>>()
        kinds.forEach { k ->
            val old = before[k] ?: return@forEach
            val new = after[k] ?: return@forEach
            if (old.absolutePath == new.absolutePath) return@forEach
            val itemKind = when (k) {
                com.nova.iptv.data.Locations.Kind.MOVIES -> ItemType.MOVIE
                com.nova.iptv.data.Locations.Kind.SERIES -> ItemType.EPISODE
                else -> "recording"
            }
            DownloadCenter.items.value.filter {
                it.kind == itemKind && it.status == com.nova.iptv.data.DownloadStatus.DONE &&
                    it.path.startsWith(old.absolutePath + "/") && File(it.path).exists()
            }.forEach { d -> moves += Triple(d, old, new) }
        }
        if (moves.isNotEmpty()) moveOffer.value = MoveOffer(moves, moves.sumOf { File(it.first.path).length() })
        settings.value = readSettings()
    }

    /** Moves the offered downloads to their new folders (keeping sub-folders), updating the Downloads list. */
    fun moveFiles(offer: MoveOffer) {
        moveOffer.value = null
        viewModelScope.launch(Dispatchers.IO) {
            var done = 0
            var failed = 0
            offer.moves.forEach { (d, oldBase, newBase) ->
                moveProgress.value = "Moving ${done + 1} of ${offer.moves.size}: ${d.title}"
                val src = File(d.path)
                val target = File(newBase, src.absolutePath.removePrefix(oldBase.absolutePath).trimStart('/'))
                val ok = runCatching {
                    target.parentFile?.mkdirs()
                    var dest = target
                    var n = 2
                    while (dest.exists()) { dest = File(target.parentFile, target.nameWithoutExtension + " ($n)." + target.extension); n++ }
                    if (!src.renameTo(dest)) {
                        src.inputStream().use { input -> dest.outputStream().use { out -> input.copyTo(out, 1024 * 1024) } }
                        if (dest.length() != src.length()) throw java.io.IOException("Copy incomplete")
                        src.delete()
                    }
                    DownloadCenter.update(d.id) { it.copy(path = dest.absolutePath) }
                }.isSuccess
                if (ok) done++ else failed++
            }
            moveProgress.value = if (failed == 0) "Moved $done files" else "Moved $done files, $failed could not be moved (not enough space?)"
            delay(4000)
            moveProgress.value = null
        }
    }

    fun dismissMove() { moveOffer.value = null }

    fun setLayout(kind: String, v: String) {
        when (kind) {
            "series" -> storage.seriesLayout = v
            "film" -> storage.filmLayout = v
            "recording" -> storage.recordingLayout = v
        }
        settings.value = readSettings()
    }

    /** Settings → Reset all settings to Nova defaults (servers, favorites, order… are kept). */
    fun resetSettings() {
        storage.resetSettings()
        com.nova.iptv.data.AgeFilter.configure(storage)
        com.nova.iptv.data.AgeFilter.saveSettings(nova, storage)
        Folders.customRoot = storage.storageRoot
        settings.value = readSettings()
    }

    fun setUsePublicFolder(v: Boolean) {
        if (v && Folders.needsStoragePermission(nova)) needStoragePermission.value = true
        storage.usePublicFolder = v
        settings.value = readSettings()
    }

    fun storageOptions(): List<Folders.StorageOption> = Folders.storageOptions(nova)

    fun setStorageRoot(path: String?) {
        storage.storageRoot = path
        Folders.customRoot = path
        if (path == null) checkStoragePermission()
        settings.value = readSettings()
    }

    fun activeRootPath(): String = Folders.activeRoot(nova).absolutePath

    fun vodCacheFolder(): String = nova.vodCacheFolder?.absolutePath ?: "(not used yet)"

    // ================================================================ downloads

    private fun checkStoragePermission() {
        if (Folders.needsStoragePermission(nova)) needStoragePermission.value = true
    }

    fun downloadMovie(m: Movie) {
        val item = movieItem(m) ?: return
        checkStoragePermission()
        DownloadCenter.enqueue(nova, m.name, item.url, m.extension, ItemType.MOVIE, m.icon,
            com.nova.iptv.data.Locations.subFolderFor(com.nova.iptv.data.Locations.Kind.MOVIES, title = m.name))
    }

    /** "Download & watch" a film: plays its download file while it downloads (one connection). */
    fun downloadAndWatchMovie(m: Movie): PlayItem? {
        val base = movieItem(m) ?: return null
        checkStoragePermission()
        val d = DownloadCenter.watchNow(nova, m.name, base.url, m.extension, ItemType.MOVIE, m.icon,
            com.nova.iptv.data.Locations.subFolderFor(com.nova.iptv.data.Locations.Kind.MOVIES, title = m.name))
        return base.copy(downloadId = d.id, downloadExt = null)
    }

    /** "Download & watch" an episode. */
    fun downloadAndWatchEpisode(info: SeriesInfo, e: Episode): PlayItem? {
        val acc = account(info.series.accountId) ?: return null
        checkStoragePermission()
        val code = "S%02dE%02d".format(e.season, e.episodeNum)
        val title = "${info.series.name} $code ${e.title}".trim()
        val url = api.episodeUrl(acc, e.id, e.extension)
        val d = DownloadCenter.watchNow(nova, title, url, e.extension, ItemType.EPISODE, e.image ?: info.series.cover,
            com.nova.iptv.data.Locations.subFolderFor(com.nova.iptv.data.Locations.Kind.SERIES, show = info.series.name, season = e.season))
        return PlayItem(saved(e, info.series), url, isLive = false, subtitle = e.plot, downloadId = d.id)
    }

    /** A download that is still running, watched from what is already saved. */
    fun watchDownload(d: DownloadItem): PlayItem? {
        if (d.status == com.nova.iptv.data.DownloadStatus.DONE) return localPlayItem(d)
        val again = DownloadCenter.watchNow(nova, d.title, d.url, d.path.substringAfterLast('.', "mp4"), d.kind, d.icon)
        return PlayItem(SavedItem("file", d.id, d.title, d.icon), d.url, isLive = false, downloadId = again.id)
    }

    fun setReadAheadMb(v: Int) { storage.readAheadMb = v; settings.value = readSettings() }

    fun downloadEpisode(info: SeriesInfo, e: Episode) {
        val acc = account(info.series.accountId) ?: return
        checkStoragePermission()
        val code = "S%02dE%02d".format(e.season, e.episodeNum)
        val title = "${info.series.name} $code ${e.title}".trim()
        DownloadCenter.enqueue(nova, title, api.episodeUrl(acc, e.id, e.extension), e.extension, ItemType.EPISODE, e.image ?: info.series.cover,
            com.nova.iptv.data.Locations.subFolderFor(com.nova.iptv.data.Locations.Kind.SERIES, show = info.series.name, season = e.season))
    }

    fun pauseDownload(d: DownloadItem) = DownloadCenter.pause(d.id)

    fun resumeDownload(d: DownloadItem) {
        checkStoragePermission()
        DownloadCenter.resume(nova, d.id)
    }

    fun deleteDownload(d: DownloadItem) = DownloadCenter.remove(d.id, deleteFile = true)

    fun localPlayItem(d: DownloadItem): PlayItem? {
        val f = File(d.path)
        if (!f.exists()) return null
        return PlayItem(SavedItem("file", d.id, d.title, d.icon), Uri.fromFile(f).toString(), isLive = false)
    }

    fun downloadsFolder(): String = Folders.keepDir(nova, "").absolutePath

    @OptIn(UnstableApi::class)
    fun clearVodCache(done: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val cache = nova.vodCache
                cache.keys.toList().forEach { runCatching { cache.removeResource(it) } }
            }
            done()
        }
    }

    companion object {
        const val ALL = "__all__"
        const val FAV = "__fav__"
        const val NEW = "__new__"
        const val RECENTLY_ADDED_COUNT = 300
    }

    // ================================================================ search index

    private var indexJob: Job? = null

    /** Loads the saved index, then quietly updates the accounts whose index is missing or older than a day. */
    fun refreshIndex(force: Boolean = false) {
        if (indexJob?.isActive == true) return
        indexJob = viewModelScope.launch(Dispatchers.IO) {
            val accs = accounts.value
            com.nova.iptv.data.SearchIndex.load(nova, accs)
            // Servers still without an index: take it from the folder chosen with the folder picker, if any.
            if (indexTree != null && accs.any { com.nova.iptv.data.SearchIndex.get(it.id) == null }) runCatching {
                treeIndexFiles().firstOrNull()?.let { f -> readUri(f.second)?.let { importIndex(it, fromIndexFolder = true) } }
            }
            val todo = accs.filter { force || com.nova.iptv.data.SearchIndex.needsUpdate(it) }
            if (todo.isEmpty()) {
                // First run after this feature arrived: make sure index-latest exists.
                if (storage.autoExportIndex && !java.io.File(com.nova.iptv.data.SearchIndex.folder(nova), com.nova.iptv.data.SearchIndex.LATEST).exists()) autoExportIndex()
                checkAges(); return@launch
            }
            com.nova.iptv.data.SearchIndex.busy.value = true
            try {
                todo.forEach { com.nova.iptv.data.SearchIndex.update(nova, nova.http, { storage.userAgent }, it) }
            } finally {
                com.nova.iptv.data.SearchIndex.busy.value = false
            }
            if (storage.autoExportIndex) autoExportIndex()
            checkAges()
            if (query.value.trim().length >= 2) withContext(Dispatchers.Main) { setQuery(query.value) }
        }
    }

    /** Changes after an export or import, so the Search index panel redraws. */
    val indexExportTick = MutableStateFlow(0)

    private fun appVersion(): String =
        runCatching { nova.packageManager.getPackageInfo(nova.packageName, 0).versionName }.getOrNull() ?: "2.0"

    /**
     * Writes index-latest.novaindex (all accounts) — only when something changed since the last export.
     * Also into the folder chosen with the folder picker (works even for files an earlier install made). Never throws.
     */
    private fun autoExportIndex() {
        val stamp = com.nova.iptv.data.SearchIndex.stamp(accounts.value)
        val latest = java.io.File(com.nova.iptv.data.SearchIndex.folder(nova), com.nova.iptv.data.SearchIndex.LATEST)
        if (stamp.isBlank() || (stamp == storage.indexExportStamp && (latest.exists() || indexTree != null))) {
            android.util.Log.i("NovaTest", "INDEX auto-export skipped: nothing changed"); return
        }
        var saved = false
        runCatching { com.nova.iptv.data.SearchIndex.exportFile(nova, accounts.value, appVersion(), latest = true) }
            .onSuccess { if (it != null) saved = true }
            .onFailure { android.util.Log.i("NovaTest", "INDEX auto-export failed: $it") }
        if (indexTree != null) runCatching { exportToTree(com.nova.iptv.data.SearchIndex.LATEST, accounts.value) }
            .onSuccess { saved = true }.onFailure { android.util.Log.i("NovaTest", "INDEX export to the chosen folder failed: $it") }
        if (saved) { storage.indexExportStamp = stamp; storage.indexExportedAt = System.currentTimeMillis(); indexExportTick.value++ }
    }

    // ---------- the Index folder chosen with Android's folder picker (kept permission) ----------

    val indexTree: android.net.Uri? get() = storage.indexTreeUri?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }

    fun setIndexTree(uri: android.net.Uri) {
        runCatching {
            nova.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        storage.indexTreeUri = uri.toString()
        indexExportTick.value++
    }

    /** .novaindex files in the chosen folder: (name, link, modified), index-latest first then newest. */
    fun treeIndexFiles(): List<Triple<String, android.net.Uri, Long>> {
        val tree = indexTree ?: return emptyList()
        val kids = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
        val out = ArrayList<Triple<String, android.net.Uri, Long>>()
        runCatching {
            nova.contentResolver.query(kids, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME, android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    val name = cur.getString(1) ?: continue
                    if (name.endsWith("." + com.nova.iptv.data.SearchIndex.EXT, ignoreCase = true))
                        out += Triple(name, android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0)), cur.getLong(2))
                }
            }
        }
        return out.sortedWith(compareByDescending<Triple<String, android.net.Uri, Long>> { it.first == com.nova.iptv.data.SearchIndex.LATEST }.thenByDescending { it.third })
    }

    /** Writes an export into the chosen folder (overwriting a file with the same name, whoever made it). */
    private fun exportToTree(name: String, accs: List<Account>) {
        val tree = indexTree ?: return
        val cr = nova.contentResolver
        val target = treeIndexFiles().firstOrNull { it.first == name }?.second
            ?: android.provider.DocumentsContract.createDocument(cr, android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree)), "application/octet-stream", name)
            ?: throw IllegalStateException("Could not create $name in the chosen folder")
        // Written to memory first: the file on disk is only replaced by a complete index.
        val bytes = java.io.ByteArrayOutputStream().also { com.nova.iptv.data.SearchIndex.exportTo(it, com.nova.iptv.data.SearchIndex.exportable(accs), appVersion()) }.toByteArray()
        cr.openOutputStream(target, "wt")!!.use { it.write(bytes) }
    }

    /** Reads a file from the chosen folder. */
    fun readUri(uri: android.net.Uri): ByteArray? = runCatching { nova.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()

    /** New install with servers but no search index yet, and nothing chosen: offer to import. */
    fun shouldOfferIndexImport(): Boolean =
        !storage.indexOfferDone && indexTree == null && accounts.value.isNotEmpty() &&
            accounts.value.none { com.nova.iptv.data.SearchIndex.get(it.id) != null }

    fun dismissIndexOffer() { storage.indexOfferDone = true }

    /** After choosing the folder on a new install: imports its newest index (index-latest first). */
    suspend fun importFromChosenFolder(): String = withContext(Dispatchers.IO) {
        storage.indexOfferDone = true
        val f = treeIndexFiles().firstOrNull() ?: return@withContext "No .novaindex file in this folder"
        val bytes = readUri(f.second) ?: return@withContext "Could not read ${f.first}"
        val r = importIndex(bytes, fromIndexFolder = true)
        if (r.linked.isEmpty()) "${f.first}: none of its servers is in Nova yet"
        else "Imported ${"%,d".format(r.items)} items for ${r.linked.joinToString(", ")} from ${f.first}"
    }

    /** Export button: a dated file for [accs] (and refreshes index-latest). Blocking: call off the main thread. */
    fun exportIndex(accs: List<Account>): Pair<java.io.File, Int>? {
        val r = com.nova.iptv.data.SearchIndex.exportFile(nova, accs, appVersion(), latest = false) ?: return null
        runCatching { com.nova.iptv.data.SearchIndex.exportFile(nova, accounts.value, appVersion(), latest = true) }
        if (indexTree != null) runCatching { exportToTree(com.nova.iptv.data.SearchIndex.LATEST, accounts.value); exportToTree(r.first.name, accs) }
        storage.indexExportStamp = com.nova.iptv.data.SearchIndex.stamp(accounts.value)
        storage.indexExportedAt = System.currentTimeMillis()
        indexExportTick.value++
        return r
    }

    /** Writes the index of [accs] to a place the user picked (Other location…). */
    fun exportIndexTo(out: java.io.OutputStream, accs: List<Account>): Int =
        com.nova.iptv.data.SearchIndex.exportTo(out, com.nova.iptv.data.SearchIndex.exportable(accs), appVersion())

    /** Imports an index file; keeps a copy in the Index folder when it came from elsewhere. Blocking. */
    fun importIndex(bytes: ByteArray, fromIndexFolder: Boolean): com.nova.iptv.data.SearchIndex.ImportResult {
        val v = com.nova.iptv.data.SearchIndex.headerVersion(bytes)
            ?: throw IllegalArgumentException("This isn't a Nova search index file (.novaindex)")
        if (v > 2) throw IllegalArgumentException("This index was made by a newer Nova — update Nova first, then import it")
        val p = com.nova.iptv.data.SearchIndex.readPortable(bytes.inputStream())
            ?: throw IllegalArgumentException("This index file is damaged and can't be read")
        val r = com.nova.iptv.data.SearchIndex.importPortable(nova, p, accounts.value)
        if (!fromIndexFolder) com.nova.iptv.data.SearchIndex.keepCopy(nova, bytes)
        indexExportTick.value++
        if (query.value.trim().length >= 2) viewModelScope.launch(Dispatchers.Main) { setQuery(query.value) }
        return r
    }

    // Runs last, once every property above exists (the start-up check uses them).
    init {
        // New install / cleared data: the filter (and PIN) come back from Download/NovaIPTV/Filter.
        if (com.nova.iptv.data.AgeFilter.restoreSettings(nova, storage)) settings.value = readSettings()
        com.nova.iptv.data.AgeFilter.configure(storage)
        viewModelScope.launch(Dispatchers.IO) { com.nova.iptv.data.AgeFilter.load(nova) }
        if (com.nova.iptv.data.CrashLog.isNew(nova)) android.widget.Toast.makeText(
            nova, "Nova closed unexpectedly last time — see Settings › 7 System › Last problem", android.widget.Toast.LENGTH_LONG,
        ).show()
        if (accounts.value.isNotEmpty()) reloadEverything()
        runStartup()
        // Search index: load at start (and when accounts change), update quietly when older than a day.
        viewModelScope.launch {
            delay(4000)
            accounts.collect { refreshIndex() }
        }
        // Accounts link: re-read once a day while Nova is open.
        viewModelScope.launch {
            while (true) {
                delay(60 * 60_000L)
                refreshIndex()
                if (storage.accountsLink != null && System.currentTimeMillis() - storage.accountsLinkCheckedAt > 24 * 3600_000L) {
                    runCatching { syncAccountsLink() }
                }
            }
        }
    }
}
