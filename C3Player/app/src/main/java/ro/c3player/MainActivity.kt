package ro.c3player

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val uri: Uri,
    val duration: Long,
    val artworkUri: Uri?
) {
    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(artworkUri)
                .build()
        )
        .build()
}

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        setContent {
            C3Theme {
                C3PlayerApp(
                    player = player,
                    loadTracks = { queryAudio() }
                )
            }
        }
    }

    private suspend fun queryAudio(): List<Track> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val result = mutableListOf<Track>()
        contentResolver.query(collection, projection, selection, null, sort)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val albumId = cursor.getLong(albumIdCol)
                val artPath = queryAlbumArtPath(albumId)
                result += Track(
                    id = id,
                    title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Titlu necunoscut",
                    artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() } ?: "Artist necunoscut",
                    album = cursor.getString(albumCol)?.takeIf { it.isNotBlank() } ?: "Album necunoscut",
                    uri = ContentUris.withAppendedId(collection, id),
                    duration = cursor.getLong(durationCol),
                    artworkUri = artPath?.let { Uri.parse("file://${Uri.encode(it)}") }
                )
            }
        }
        result
    }

    private fun queryAlbumArtPath(albumId: Long): String? {
        if (albumId < 0) return null
        val albums = MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI
        contentResolver.query(
            ContentUris.withAppendedId(albums, albumId),
            arrayOf(MediaStore.Audio.Albums.ALBUM_ART),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(MediaStore.Audio.Albums.ALBUM_ART)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return null
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}

@Composable
fun C3Theme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Color(0xFFB6F36B),
        secondary = Color(0xFF72D6C2),
        background = Color(0xFF0B0E12),
        surface = Color(0xFF151A20),
        surfaceVariant = Color(0xFF202832)
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun C3PlayerApp(player: ExoPlayer, loadTracks: suspend () -> List<Track>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var permissionGranted by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf("Piese") }
    var refreshToken by remember { mutableIntStateOf(0) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var shuffleEnabled by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var isBuffering by remember { mutableStateOf(false) }

    val permission = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
    }

    LaunchedEffect(Unit) {
        permissionGranted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (permissionGranted) tracks = loadTracks()
    }
    LaunchedEffect(permissionGranted) {
        if (permissionGranted && tracks.isEmpty()) tracks = loadTracks()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                refreshToken++
                isPlaying = player.isPlaying
                shuffleEnabled = player.shuffleModeEnabled
                repeatMode = player.repeatMode
                isBuffering = player.playbackState == Player.STATE_BUFFERING
                duration = player.duration.coerceAtLeast(0L)
                position = player.currentPosition.coerceAtLeast(0L)
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                duration = player.duration.coerceAtLeast(0L)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            isPlaying = player.isPlaying
            delay(250)
        }
    }

    val currentTrack = remember(refreshToken, tracks) {
        tracks.getOrNull(player.currentMediaItemIndex)
    }
    val title = currentTrack?.title
        ?: player.currentMediaItem?.mediaMetadata?.title?.toString()
        ?: "Alege o piesă"
    val artist = currentTrack?.artist
        ?: player.currentMediaItem?.mediaMetadata?.artist?.toString()
        ?: "Biblioteca ta muzicală"

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("C3 PLAYER", fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                        Text("MUSIC • YOUR WAY", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
                    }
                },
                actions = {
                    IconButton(onClick = { tracks = emptyList() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reîncarcă biblioteca")
                    }
                    IconButton(onClick = { selectedTab = "Setări" }) {
                        Icon(Icons.Default.Tune, contentDescription = "Setări")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0B0E12))
            )
        },
        bottomBar = {
            Column {
                if (player.mediaItemCount > 0) {
                    MiniPlayer(
                        title = title, artist = artist, playing = isPlaying,
                        position = position, duration = duration,
                        artwork = currentTrack?.artworkUri,
                        onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
                        onNext = { player.seekToNextMediaItem() },
                        onOpen = { selectedTab = "Acum" }
                    )
                }
                NavigationBar(containerColor = Color(0xFF11161C)) {
                    listOf("Piese" to Icons.Default.LibraryMusic, "Albume" to Icons.Default.Album,
                        "Playlist" to Icons.Default.QueueMusic, "Acum" to Icons.Default.GraphicEq).forEach { (label, icon) ->
                        NavigationBarItem(
                            selected = selectedTab == label,
                            onClick = { selectedTab = label },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label, fontSize = 10.sp) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!permissionGranted) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.LibraryMusic, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
                        Text("Acces la muzica de pe dispozitiv")
                        Text("C3 Player redă fișierele audio locale.", color = Color.LightGray)
                        Button(onClick = { launcher.launch(permission) }) { Text("Permite accesul") }
                    }
                }
            } else when (selectedTab) {
                "Acum" -> NowPlayingScreen(
                    title = title, artist = artist, album = currentTrack?.album ?: "",
                    artwork = currentTrack?.artworkUri, playing = isPlaying,
                    position = position, duration = duration, buffering = isBuffering,
                    shuffle = shuffleEnabled, repeat = repeatMode,
                    hasQueue = player.mediaItemCount > 0,
                    onSeek = { player.seekTo(it) },
                    onPrevious = { player.seekToPreviousMediaItem() },
                    onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
                    onNext = { player.seekToNextMediaItem() },
                    onShuffle = { player.shuffleModeEnabled = !player.shuffleModeEnabled },
                    onRepeat = { player.repeatMode = when (player.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    } }
                )
                "Setări" -> SettingsScreen()
                else -> {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("BIBLIOTECA", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, letterSpacing = 2.sp)
                            Text("${tracks.size} piese găsite", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = { tracks = emptyList() }) { Icon(Icons.Default.Sort, contentDescription = "Actualizează") }
                    }
                    if (tracks.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Nu am găsit fișiere audio sau biblioteca nu a fost încărcată.")
                                TextButton(onClick = { tracks = emptyList() }) { Text("Reîncarcă") }
                            }
                        }
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
                            itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                                TrackRow(track, isCurrent = player.currentMediaItemIndex == index) {
                                    player.setMediaItems(tracks.map { it.toMediaItem() }, index, 0L)
                                    player.prepare()
                                    player.play()
                                    selectedTab = "Acum"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TrackRow(track: Track, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Artwork(uri = track.artworkUri, modifier = Modifier.size(48.dp), corner = 12.dp)
        Column(Modifier.weight(1f).padding(start = 13.dp)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.White)
            Text("${track.artist}  •  ${track.album}", color = Color(0xFF9AA5B1), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatTime(track.duration), color = Color(0xFF89939E), fontSize = 11.sp)
        IconButton(onClick = onClick) { Icon(if (isCurrent) Icons.Default.GraphicEq else Icons.Default.PlayArrow, null, tint = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Gray) }
    }
}

@Composable
fun Artwork(uri: Uri?, modifier: Modifier = Modifier, corner: androidx.compose.ui.unit.Dp = 20.dp) {
    Box(
        modifier.clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(Color(0xFF31423D), Color(0xFF17251F)))),
        contentAlignment = Alignment.Center
    ) {
        if (uri != null) {
            AsyncImage(model = uri, contentDescription = "Copertă album", modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Default.Album, null, tint = Color(0xFFB6F36B), modifier = Modifier.fillMaxSize(0.42f))
        }
    }
}

@Composable
fun MiniPlayer(
    title: String, artist: String, playing: Boolean, position: Long, duration: Long,
    artwork: Uri?, onPlayPause: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit
) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF1A222A)).clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(artwork, Modifier.size(42.dp), 9.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(artist, color = Color.LightGray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onPlayPause) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Redare/Pauză") }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, contentDescription = "Piesa următoare") }
        }
        LinearProgressIndicator(
            progress = { if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = MaterialTheme.colorScheme.primary, trackColor = Color.DarkGray
        )
    }
}

@Composable
fun NowPlayingScreen(
    title: String, artist: String, album: String, artwork: Uri?, playing: Boolean,
    position: Long, duration: Long, buffering: Boolean, shuffle: Boolean, repeat: Int,
    hasQueue: Boolean, onSeek: (Long) -> Unit, onPrevious: () -> Unit, onPlayPause: () -> Unit,
    onNext: () -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit
) {
    val safeDuration = duration.coerceAtLeast(0L)
    var seekPosition by remember(position, safeDuration) { mutableFloatStateOf(position.coerceIn(0L, max(1L, safeDuration)).toFloat()) }
    var isDragging by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("NOW PLAYING", color = MaterialTheme.colorScheme.primary, letterSpacing = 3.sp, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("C3 • LOCAL LIBRARY", color = Color.Gray, fontSize = 9.sp, letterSpacing = 1.5.sp)
            }
            if (buffering) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            } else {
                Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f, fill = false).aspectRatio(1f).padding(vertical = 8.dp)) {
            Artwork(artwork, Modifier.fillMaxSize(), 28.dp)
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(
                Brush.verticalGradient(listOf(Color.Transparent, Color(0x55000000)))
            ))
            AudioSpectrum(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(78.dp).padding(horizontal = 28.dp, vertical = 10.dp), playing)
        }

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, color = Color(0xFFB6C0C9), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (album.isNotBlank()) Text(album, color = Color.Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Column(Modifier.fillMaxWidth()) {
            Slider(
                value = if (isDragging) seekPosition else position.coerceIn(0L, max(1L, safeDuration)).toFloat(),
                onValueChange = {
                    isDragging = true
                    seekPosition = it
                },
                onValueChangeFinished = {
                    onSeek(seekPosition.toLong())
                    isDragging = false
                },
                valueRange = 0f..max(1L, safeDuration).toFloat(),
                enabled = hasQueue && safeDuration > 0,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color(0xFF39434D)
                )
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(if (isDragging) seekPosition.toLong() else position), color = Color.Gray, fontSize = 11.sp)
                Text("-${formatTime((safeDuration - if (isDragging) seekPosition.toLong() else position).coerceAtLeast(0L))}", color = Color.Gray, fontSize = 11.sp)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onShuffle) {
                Icon(Icons.Default.Shuffle, null, tint = if (shuffle) MaterialTheme.colorScheme.primary else Color(0xFF9AA5B1))
            }
            IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, null, modifier = Modifier.size(36.dp)) }
            FilledIconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color(0xFF101510))
            ) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(38.dp))
            }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null, modifier = Modifier.size(36.dp)) }
            IconButton(onClick = onRepeat) {
                Box {
                    Icon(
                        if (repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        null, tint = if (repeat != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else Color(0xFF9AA5B1)
                    )
                }
            }
        }
        Text(
            when (repeat) {
                Player.REPEAT_MODE_ONE -> "REPETĂ PIESA"
                Player.REPEAT_MODE_ALL -> "REPETĂ COADA"
                else -> "REDĂ ÎN ORDINE"
            },
            color = Color.Gray, fontSize = 9.sp, letterSpacing = 1.5.sp
        )
    }
}

@Composable
fun AudioSpectrum(modifier: Modifier = Modifier, playing: Boolean) {
    val transition = rememberInfiniteTransition(label = "spectrum")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "spectrum-phase"
    )
    Canvas(modifier) {
        val bars =  thirtyBars
        val gap = size.width * 0.012f
        val barWidth = (size.width - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val wave = kotlin.math.abs(kotlin.math.sin(i * 0.63f + phase * 3.14f))
            val envelope = 0.25f + 0.75f * kotlin.math.abs(kotlin.math.sin(i * 0.21f + phase * 2f))
            val animated = if (playing) wave * envelope else 0.12f + (i % 3) * 0.025f
            val h = size.height * (0.14f + animated * 0.78f)
            val x = i * (barWidth + gap)
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(Color(0xFF72D6C2), Color(0xFFB6F36B))),
                topLeft = Offset(x, size.height - h),
                size = Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2, barWidth / 2)
            )
        }
    }
}

private const val thirtyBars = 30

@Composable
fun SettingsScreen() {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("SETĂRI AUDIO", color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp, fontSize = 11.sp)
        Text("Sunet și redare", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        SettingsRow("Egalizator", "Pregătit pentru integrarea Android Audio Effects", Icons.Default.Equalizer)
        SettingsRow("Bass Boost", "Efect audio (în dezvoltare)", Icons.Default.GraphicEq)
        SettingsRow("Crossfade", "Tranziție între melodii (în dezvoltare)", Icons.Default.SwapHoriz)
        SettingsRow("Sleep Timer", "Oprire automată (în dezvoltare)", Icons.Default.Bedtime)
        SettingsRow("Temă", "Dark • C3 Lime", Icons.Default.DarkMode)
        Text("C3 Player 1.0.1 • Redare locală Media3", color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable
fun SettingsRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF151A20)).padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color.Gray, fontSize = 11.sp)
        }
    }
}

fun formatTime(ms: Long): String {
    if (ms < 0) return "0:00"
    val total = ms / 1000
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
