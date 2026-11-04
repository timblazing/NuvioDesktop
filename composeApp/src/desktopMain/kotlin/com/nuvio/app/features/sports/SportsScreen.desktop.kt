package com.nuvio.app.features.sports

import androidx.compose.foundation.layout.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
internal actual fun SportsScreen(topChromePadding: Dp?, active: Boolean, onPlay: (SportsPlaybackRequest) -> Unit) {
    val source = remember { SportsHttpSource() }
    DisposableEffect(source) { onDispose { source.close() } }
    SportsHub(source, topChromePadding, active, onPlay)
}

@Composable
internal fun SportsHub(
    source: SportsSource,
    topChromePadding: Dp? = null,
    active: Boolean = true,
    onPlay: (SportsPlaybackRequest) -> Unit,
    resolveSources: (suspend (SportsEvent, XtreamConnection, List<SportsChannel>) -> List<SportsPlaybackSource>)? = null,
) {
    val resolver = remember { SportsResolver() }
    val resolve = resolveSources ?: resolver::resolve
    val scope = rememberCoroutineScope()
    val preferences = remember { DesktopStorage.store("sports_preferences") }
    var favorites by remember { mutableStateOf(preferences.getStringSet("teams").orEmpty()) }
    var league by remember { mutableStateOf(sportsLeagues.first()) }
    var day by remember { mutableStateOf(LocalDate.now()) }
    var filter by remember { mutableStateOf("All events") }
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val cache = remember { mutableMapOf<Pair<String, LocalDate>, Pair<List<SportsEvent>, String>>() }
    var events by remember { mutableStateOf<List<SportsEvent>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var scheduleError by remember { mutableStateOf<String?>(null) }
    var updated by remember { mutableStateOf<String?>(null) }
    var connection by remember { mutableStateOf<XtreamConnection?>(null) }
    var channels by remember { mutableStateOf<List<SportsChannel>>(emptyList()) }
    var showConnection by remember { mutableStateOf(false) }
    var pendingEvent by remember { mutableStateOf<SportsEvent?>(null) }
    fun watch(event: SportsEvent, account: XtreamConnection, available: List<SportsChannel>) {
        onPlay(SportsPlaybackRequest(event.name, event.id) { resolve(event, account, available) })
    }
    var connectionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    LaunchedEffect(league, day, active, refresh) {
        if (!active) return@LaunchedEffect
        val key = league.id to day
        events = cache[key]?.first.orEmpty()
        updated = cache[key]?.second
        do {
            loading = true
            scheduleError = null
            try {
                events = source.events(league, day)
                updated = LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm:ss a"))
                cache[key] = events to updated!!
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { scheduleError = "Could not refresh the schedule. Check your connection and try Refresh." }
            finally { loading = false }
            delay(if (events.any { it.isLive }) 30_000L else 300_000L)
        } while (true)
    }

    val visible = events.filter { event ->
        (filter != "Live" || event.isLive) &&
            (filter != "My teams" || event.teams.any { it.id in favorites }) &&
            (query.isBlank() || (event.name + " " + event.networks.joinToString()).contains(query, ignoreCase = true))
    }.sortedWith(compareBy<SportsEvent> { if (it.isLive) 0 else if (it.isFinal) 2 else 1 }.thenBy { it.start })

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 600.dp
        Column(
            Modifier.fillMaxSize().padding(horizontal = if (narrow) 16.dp else 32.dp)
                .padding(top = (topChromePadding ?: 40.dp), bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sports", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { refresh++ }, enabled = !loading) { Text("Refresh") }
                FilledTonalButton(onClick = { showConnection = true }) { Text(if (connection == null) "Connect IPTV" else "IPTV connected") }
            }
            if (!narrow) Text("Your games, scores and channels.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sportsLeagues, key = { it.id }) { item ->
                    FilterChip(league == item, { league = item }, label = { Text(item.label) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { day = day.minusDays(1) }, enabled = day > LocalDate.now().minusDays(7)) { Text("Previous") }
                TextButton(onClick = { day = LocalDate.now() }) { Text(if (day == LocalDate.now()) "Today" else day.format(DateTimeFormatter.ofPattern("EEE, MMM d"))) }
                TextButton(onClick = { day = day.plusDays(1) }, enabled = day < LocalDate.now().plusDays(7)) { Text("Next") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("All events", "Live", "My teams")) { item -> FilterChip(filter == item, { filter = item }, label = { Text(item) }) }
            }
            OutlinedTextField(query, { query = it }, label = { Text("Find a team or network") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${visible.size} events · ${events.count { it.isLive }} live", style = MaterialTheme.typography.labelLarge)
                Text(if (loading) "Refreshing…" else updated?.let { "Updated $it" } ?: "Schedule unavailable", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            scheduleError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (visible.isEmpty() && !loading && scheduleError == null) {
                Text(when {
                    filter == "My teams" && favorites.isEmpty() -> "Follow a team on an event card to see its games here."
                    filter == "Live" -> "No live events for this league and date."
                    query.isNotBlank() -> "No events match your search."
                    else -> "No events scheduled for this league and date."
                }, modifier = Modifier.padding(vertical = 24.dp))
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (narrow) 280.dp else 340.dp),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(visible, key = { it.id }) { event ->
                    SportsEventCard(event, favorites, onFollow = { team ->
                        favorites = if (team.id in favorites) favorites - team.id else favorites + team.id
                        preferences.putStringSet("teams", favorites)
                    }, onWatch = {
                        val account = connection
                        if (account == null) { pendingEvent = event; showConnection = true }
                        else watch(event, account, channels)
                    })
                }
            }
        }
    }

    }

    if (showConnection) SportsConnectionDialog(
        connected = connection != null,
        onDismiss = { showConnection = false; pendingEvent = null },
        onDisconnect = {
            connectionJob?.cancel()
            connection = null; channels = emptyList(); showConnection = false; pendingEvent = null
        },
        onConnect = { candidate, completed ->
            connectionJob?.cancel()
            connectionJob = scope.launch {
                try {
                    val loaded = source.channels(candidate).filter { isAllowedSportsGroup(it.category) }
                    if (loaded.isEmpty()) {
                        completed("No USA or UK channel groups were found in this account.")
                        return@launch
                    }
                    connection = candidate; channels = loaded; showConnection = false
                    completed(null)
                    pendingEvent?.let { event -> pendingEvent = null; watch(event, candidate, loaded) }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { completed("Could not connect. Check the server address, account status, username and password.") }
            }
        },
    )
}

@Composable
private fun SportsEventCard(event: SportsEvent, favorites: Set<String>, onFollow: (SportsTeam) -> Unit, onWatch: () -> Unit) {
    Card(onClick = onWatch, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Watch ${event.name}" }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (event.isLive) "Live · ${event.detail}" else if (event.isFinal) event.detail else "${event.localTime} · ${event.detail}",
                style = MaterialTheme.typography.labelLarge, color = if (event.isLive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            if (event.teams.size == 2) {
                event.teams.forEach { team ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (team.logo != null) AsyncImage(team.logo, null, Modifier.size(32.dp))
                        Text(team.shortName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2)
                        if (event.isLive || event.isFinal) Text(team.score ?: "—", style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { onFollow(team) }, contentPadding = PaddingValues(4.dp)) { Text(if (team.id in favorites) "Following" else "Follow", style = MaterialTheme.typography.labelMedium) }
                    }
                }
            } else Text(event.name, style = MaterialTheme.typography.titleMedium)
            Text(event.networks.joinToString(" · ").ifEmpty { "Broadcast network to be announced" }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SportsConnectionDialog(
    connected: Boolean, onDismiss: () -> Unit, onDisconnect: () -> Unit,
    onConnect: (XtreamConnection, (String?) -> Unit) -> Unit,
) {
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(SportsStreamFormat.HLS) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Xtream IPTV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Credentials stay in memory for this session. Reconnect after closing the app.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(server, { server = it }, label = { Text("Server URL") }, placeholder = { Text("https://provider.example:8080") }, singleLine = true, enabled = !busy)
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, enabled = !busy)
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SportsStreamFormat.entries.forEach { item -> FilterChip(item == format, { format = item }, enabled = !busy, label = { Text(item.label) }) }
                }
                if (server.trim().startsWith("http://")) Text("This provider uses HTTP; the connection sends credentials without encryption.", style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (connected) TextButton(onClick = onDisconnect, enabled = !busy) { Text("Disconnect and forget credentials") }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && server.isNotBlank() && username.isNotBlank() && password.isNotEmpty(), onClick = {
                val candidate = try { XtreamConnection(server, username.trim(), password, format) }
                catch (_: IllegalArgumentException) { error = "Enter a valid http:// or https:// server address without a playlist URL or query parameters."; return@TextButton }
                busy = true; error = null
                onConnect(candidate) { message -> busy = false; error = message; if (message == null) password = "" }
            }) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

