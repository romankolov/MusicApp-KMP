package musicapp.player

import io.github.sinaraz.JudioPlayer
import io.github.sinaraz.PlaybackConfig
import io.github.sinaraz.PlaybackListener
import io.github.sinaraz.model.AudioInfo
import io.github.sinaraz.model.PlaybackError
import io.github.sinaraz.model.PlaybackSnapshot
import io.github.sinaraz.model.PlaybackState
import io.github.sinaraz.model.Track
import musicapp.utils.PlatformContext
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

/**
 * Desktop [MediaPlayerController] backed by Judio, a pure-Java audio player.
 *
 * Judio is created lazily and all backend commands run on one serial executor so track switches,
 * transport controls, and cleanup cannot race. The shared player API exposes milliseconds even
 * though [seekTo]'s parameter is historically named `seconds`.
 */
actual class MediaPlayerController actual constructor(val platformContext: PlatformContext) {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MusicApp-Judio").apply { isDaemon = true }
    }
    private val generation = AtomicLong()
    private val playlistLock = Any()
    private val logger = Logger.getLogger(MediaPlayerController::class.java.name)

    @Volatile
    private var mediaPlayer: JudioPlayer? = null

    @Volatile
    private var listener: MediaPlayerListener? = null

    @Volatile
    private var currentTrack: TrackItem? = null

    @Volatile
    private var trackList: List<TrackItem> = emptyList()

    @Volatile
    private var currentTrackIndex: Int = -1

    @Volatile
    private var released = false

    // Only touched on the serial executor.
    private var activeListener: PlaybackListener? = null
    private var playerFactory: () -> JudioPlayer = ::createPlayer

    internal constructor(
        platformContext: PlatformContext,
        playerFactory: () -> JudioPlayer,
    ) : this(platformContext) {
        this.playerFactory = playerFactory
    }

    actual fun prepare(mediaItem: TrackItem, listener: MediaPlayerListener) {
        val requestId = generation.incrementAndGet()
        synchronized(playlistLock) {
            this.listener = listener
            currentTrack = mediaItem
            if (trackList.isNotEmpty()) {
                trackList.indexOfFirst { it.id == mediaItem.id }
                    .takeIf { it >= 0 }
                    ?.let { currentTrackIndex = it }
            }
        }

        if (mediaItem.pathSource.isBlank()) {
            reportError(requestId, IllegalArgumentException("Track has no audio source"))
            return
        }

        execute(requestId) {
            try {
                val player = mediaPlayer ?: playerFactory().also { mediaPlayer = it }
                activeListener?.let(player::removeListener)
                player.clear()

                val events = object : PlaybackListener {
                    override fun onTrackLoaded(track: Track, info: AudioInfo) {
                        notifyIfCurrent(requestId) {
                            it.onBufferingStateChanged(false)
                            it.onReady()
                        }
                    }

                    override fun onStateChanged(snapshot: PlaybackSnapshot) {
                        notifyIfCurrent(requestId) {
                            when (snapshot.state) {
                                PlaybackState.LOADING, PlaybackState.SEEKING ->
                                    it.onBufferingStateChanged(true)

                                PlaybackState.PLAYING -> {
                                    it.onBufferingStateChanged(false)
                                    it.onPlaybackStateChanged(true)
                                }

                                PlaybackState.READY -> it.onBufferingStateChanged(false)

                                PlaybackState.PAUSED, PlaybackState.STOPPED,
                                PlaybackState.ENDED, PlaybackState.ERROR -> {
                                    it.onBufferingStateChanged(false)
                                    it.onPlaybackStateChanged(false)
                                }

                                else -> Unit
                            }
                        }
                    }

                    override fun onPlaybackCompleted(track: Track) {
                        notifyIfCurrent(requestId) {
                            if (!playNextTrack()) it.onAudioCompleted()
                        }
                    }

                    override fun onError(error: PlaybackError) {
                        reportError(requestId, IllegalStateException(error.message()))
                    }
                }
                activeListener = events
                player.addListener(events)
                loadSource(player, mediaItem.pathSource)
                listener.onBufferingStateChanged(true)
                player.play()
            } catch (e: Exception) {
                reportError(requestId, e)
            }
        }
    }

    actual fun setTrackList(trackList: List<TrackItem>, currentTrackId: String) {
        synchronized(playlistLock) {
            this.trackList = trackList.toList()
            currentTrackIndex = this.trackList.indexOfFirst { it.id == currentTrackId }
                .takeIf { it >= 0 } ?: 0
        }
    }

    actual fun playNextTrack(): Boolean {
        synchronized(playlistLock) {
            if (trackList.isEmpty() || currentTrackIndex < 0) return false
            val nextIndex = currentTrackIndex + 1
            return if (nextIndex >= trackList.size) false else playTrackAt(nextIndex)
        }
    }

    actual fun playPreviousTrack(): Boolean {
        synchronized(playlistLock) {
            if (trackList.isEmpty() || currentTrackIndex <= 0) return false
            return playTrackAt(currentTrackIndex - 1)
        }
    }

    private fun playTrackAt(index: Int): Boolean {
        val track = trackList.getOrNull(index) ?: return false
        val currentListener = listener ?: return false
        currentTrackIndex = index
        currentListener.onTrackChanged(track.id)
        prepare(track, currentListener)
        return true
    }

    actual fun start() {
        execute(generation.get()) {
            try {
                mediaPlayer?.play()
            } catch (e: Exception) {
                logger.warning("Unable to start playback: ${e.message}")
            }
        }
    }

    actual fun pause() {
        execute(generation.get()) {
            try {
                val player = mediaPlayer ?: return@execute
                when (player.snapshot().state) {
                    PlaybackState.PLAYING, PlaybackState.SEEKING, PlaybackState.STOPPING ->
                        player.pause()

                    else -> Unit
                }
            } catch (e: Exception) {
                logger.warning("Unable to pause playback: ${e.message}")
            }
        }
    }

    actual fun getCurrentPosition(): Long? =
        mediaPlayer?.snapshot()?.position()?.toMillis() ?: 0L

    actual fun getDuration(): Long? =
        mediaPlayer?.snapshot()?.duration()?.toMillis() ?: 0L

    actual fun seekTo(seconds: Long) {
        execute(generation.get()) {
            try {
                val player = mediaPlayer ?: return@execute
                if (player.snapshot().state.hasLoadedTrack()) {
                    player.seek(Duration.ofMillis(seconds.coerceAtLeast(0L)))
                }
            } catch (e: Exception) {
                // Seeking can be unavailable for a stream; keep the current playback alive.
                logger.warning("Unable to seek playback: ${e.message}")
            }
        }
    }

    actual fun isPlaying(): Boolean =
        mediaPlayer?.snapshot()?.state == PlaybackState.PLAYING

    actual fun getCurrentTrack(): TrackItem? = synchronized(playlistLock) {
        currentTrack ?: trackList.getOrNull(currentTrackIndex)
    }

    /** Stops playback and releases Judio's audio device and background threads. Safe to repeat. */
    fun release() {
        if (released) return
        released = true
        generation.incrementAndGet()
        listener = null
        try {
            executor.execute {
                try {
                    mediaPlayer?.let { player ->
                        activeListener?.let(player::removeListener)
                        player.close()
                    }
                } catch (e: Exception) {
                    logger.severe("Error releasing Judio player: ${e.message}")
                } finally {
                    activeListener = null
                    mediaPlayer = null
                }
            }
        } catch (_: RejectedExecutionException) {
            // Already shut down.
        }
        executor.shutdown()
    }

    private fun execute(requestId: Long, block: () -> Unit) {
        if (!isCurrent(requestId)) return
        try {
            executor.execute {
                if (isCurrent(requestId)) block()
            }
        } catch (_: RejectedExecutionException) {
            // A command raced with release(); dropping it is safe once the controller is closed.
        }
    }

    private fun isCurrent(requestId: Long): Boolean =
        !released && generation.get() == requestId

    private fun notifyIfCurrent(requestId: Long, block: (MediaPlayerListener) -> Unit) {
        if (isCurrent(requestId)) listener?.let(block)
    }

    private fun reportError(requestId: Long, error: Exception) {
        if (!isCurrent(requestId)) return
        logger.severe("Judio playback failed: ${error.message}")
        listener?.onError()
    }

    private fun loadSource(player: JudioPlayer, source: String) {
        val uri = try {
            URI(source)
        } catch (_: URISyntaxException) {
            null
        }
        when {
            uri?.scheme.equals("http", ignoreCase = true) ||
                uri?.scheme.equals("https", ignoreCase = true) -> player.addUrl(uri!!)

            uri?.scheme.equals("file", ignoreCase = true) -> player.addFiles(Path.of(uri!!))
            uri == null || !uri.isAbsolute -> player.addFiles(Path.of(source))
            else -> throw IllegalArgumentException("Unsupported audio source scheme: ${uri.scheme}")
        }
    }

    private fun createPlayer(): JudioPlayer =
        JudioPlayer.builder()
            .config(PlaybackConfig.builder().autoAdvanceOnError(false).build())
            .build()
}
