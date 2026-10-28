package musicapp.player

import com.sun.net.httpserver.HttpServer
import io.github.sinaraz.JudioPlayer
import io.github.sinaraz.PlaybackConfig
import io.github.sinaraz.model.PlaybackState
import musicapp.utils.PlatformContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Headless coverage for the Judio adapter; no sound card or external network is required. */
class MediaPlayerControllerDesktopTest {
    private val tempDir: Path = Files.createTempDirectory("musicapp-judio-test")
    private val controllers = mutableListOf<MediaPlayerController>()
    private val servers = mutableListOf<HttpServer>()
    private val createdPlayers = AtomicInteger()

    @AfterTest
    fun tearDown() {
        controllers.forEach(MediaPlayerController::release)
        servers.forEach { it.stop(0) }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `prepares plays pauses resumes and seeks a local audio source`() {
        val player = AtomicReference<JudioPlayer>()
        val controller = controller(player)
        val listener = RecordingListener()
        val track = track("local", writeWav("local.wav", seconds = 4).toString())

        controller.prepare(track, listener)

        assertTrue(listener.awaitReady(), "track should become ready")
        assertEquals(track, controller.getCurrentTrack())
        assertTrue((controller.getDuration() ?: 0L) >= 3_900L)
        await { controller.isPlaying() }

        controller.pause()
        await { !controller.isPlaying() }
        val pausedPosition = controller.getCurrentPosition() ?: 0L
        Thread.sleep(150)
        assertEquals(pausedPosition, controller.getCurrentPosition())

        controller.start()
        await { controller.isPlaying() }
        controller.seekTo(2_000L)
        await { (controller.getCurrentPosition() ?: 0L) >= 1_900L }

        controller.release()
        await { player.get()?.snapshot()?.state == PlaybackState.CLOSED }
    }

    @Test
    fun `advances through the playlist and reports final completion`() {
        val controller = controller(AtomicReference())
        val listener = RecordingListener()
        val first = track("first", writeWav("first.wav", seconds = 1).toUri().toString())
        val second = track("second", writeWav("second.wav", seconds = 1).toUri().toString())
        controller.setTrackList(listOf(first, second), first.id)

        controller.prepare(first, listener)

        assertEquals(second.id, listener.trackChanges.poll(10, TimeUnit.SECONDS))
        assertTrue(listener.completed.await(10, TimeUnit.SECONDS), "playlist should finish")
        assertEquals(second, controller.getCurrentTrack())
        assertFalse(controller.playNextTrack())
        assertTrue(listener.errors.isEmpty(), "playlist playback should not report errors")
    }

    @Test
    fun `previous track selects the preceding item`() {
        val controller = controller(AtomicReference())
        val listener = RecordingListener()
        val first = track("first", writeWav("first.wav", seconds = 2).toUri().toString())
        val second = track("second", writeWav("second.wav", seconds = 2).toUri().toString())
        controller.setTrackList(listOf(first, second), second.id)

        controller.prepare(second, listener)
        assertTrue(listener.awaitReady())
        assertTrue(controller.playPreviousTrack())

        assertEquals(first.id, listener.trackChanges.poll(5, TimeUnit.SECONDS))
        assertEquals(first, controller.getCurrentTrack())
    }

    @Test
    fun `invalid source reports an error and a later prepare can recover`() {
        val player = AtomicReference<JudioPlayer>()
        val controller = controller(player)
        val listener = RecordingListener()

        controller.prepare(track("empty", " "), listener)
        assertTrue(listener.errors.poll(5, TimeUnit.SECONDS) != null)
        assertEquals(null, player.get(), "invalid input must not open an audio device")

        controller.prepare(track("invalid", "not-a-uri"), listener)
        assertTrue(listener.errors.poll(5, TimeUnit.SECONDS) != null)

        val valid = track("valid", writeWav("valid.wav", seconds = 2).toUri().toString())
        controller.prepare(valid, listener)
        assertTrue(listener.awaitReady(), "controller should recover on the next prepare")
        assertEquals(valid, controller.getCurrentTrack())
    }

    @Test
    fun `rapid track switches keep only the latest source`() {
        val player = AtomicReference<JudioPlayer>()
        val controller = controller(player)
        val listener = RecordingListener()
        val first = track("first", writeWav("first.wav", seconds = 2).toUri().toString())
        val second = track("second", writeWav("second.wav", seconds = 2).toUri().toString())

        controller.prepare(first, listener)
        controller.prepare(second, listener)

        assertTrue(listener.awaitReady())
        await {
            controller.isPlaying() &&
                player.get()?.snapshot()?.track()?.location()?.toString()?.endsWith("second.wav") == true
        }
        assertEquals(second, controller.getCurrentTrack())
        assertEquals(1, player.get()?.queue()?.size)
        assertTrue(listener.errors.isEmpty())
    }

    @Test
    fun `plays an HTTP audio stream without a native media installation`() {
        val audio = Files.readAllBytes(writeWav("stream.wav", seconds = 2))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        servers += server
        server.createContext("/audio.wav") { exchange ->
            exchange.responseHeaders.add("Content-Type", "audio/wav")
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            exchange.sendResponseHeaders(200, audio.size.toLong())
            exchange.responseBody.use { it.write(audio) }
        }
        server.start()
        val controller = controller(AtomicReference())
        val listener = RecordingListener()
        val track = track("stream", "http://127.0.0.1:${server.address.port}/audio.wav")

        controller.prepare(track, listener)

        assertTrue(listener.awaitReady(), "HTTP stream should become ready")
        await { controller.isPlaying() }
        assertTrue((controller.getDuration() ?: 0L) >= 1_900L)
        assertEquals(track, controller.getCurrentTrack())
    }

    @Test
    fun `release is idempotent and prevents further playback`() {
        val player = AtomicReference<JudioPlayer>()
        val controller = controller(player)
        val listener = RecordingListener()
        controller.prepare(track("track", writeWav("track.wav", seconds = 2).toUri().toString()), listener)
        assertTrue(listener.awaitReady())

        controller.release()
        controller.release()
        await { player.get()?.snapshot()?.state == PlaybackState.CLOSED }
        controller.start()
        controller.prepare(track("late", "https://example.invalid/late.mp3"), listener)

        assertEquals(1, createdPlayers.get())
        assertFalse(controller.isPlaying())
    }

    private fun controller(player: AtomicReference<JudioPlayer>): MediaPlayerController =
        MediaPlayerController(PlatformContext()) {
            JudioPlayer.builder()
                .simulatedOutput()
                .config(
                    PlaybackConfig.builder()
                        .autoAdvanceOnError(false)
                        .closeTimeout(java.time.Duration.ofSeconds(1))
                        .build()
                )
                .build()
                .also {
                    createdPlayers.incrementAndGet()
                    player.set(it)
                }
        }.also(controllers::add)

    private fun track(id: String, source: String): TrackItem =
        TrackItem(id = id, title = id, artist = "Test", albumImageUrl = "", pathSource = source)

    private fun writeWav(name: String, seconds: Int): Path {
        val sampleRate = 8_000
        val frames = sampleRate * seconds
        val bytes = ByteArrayOutputStream(frames * 2)
        repeat(frames) { frame ->
            val sample = (Short.MAX_VALUE / 4 * Math.sin(frame * 2.0 * Math.PI * 440 / sampleRate)).toInt()
            bytes.write(sample and 0xff)
            bytes.write((sample shr 8) and 0xff)
        }
        val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
        val path = tempDir.resolve(name)
        AudioInputStream(ByteArrayInputStream(bytes.toByteArray()), format, frames.toLong()).use { input ->
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, path.toFile())
        }
        return path
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            if (System.nanoTime() > deadline) error("Condition was not met within 5 seconds")
            Thread.sleep(10)
        }
    }

    private class RecordingListener : MediaPlayerListener {
        private val readyCount = AtomicInteger()
        val completed = CountDownLatch(1)
        val errors = LinkedBlockingQueue<Unit>()
        val trackChanges = LinkedBlockingQueue<String>()

        override fun onReady() {
            readyCount.incrementAndGet()
        }

        override fun onAudioCompleted() {
            completed.countDown()
        }

        override fun onError() {
            errors.offer(Unit)
        }

        override fun onTrackChanged(trackId: String) {
            trackChanges.offer(trackId)
        }

        override fun onPlaybackStateChanged(isPlaying: Boolean) = Unit

        fun awaitReady(): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (readyCount.get() == 0) {
                if (System.nanoTime() > deadline) return false
                Thread.sleep(10)
            }
            return true
        }
    }
}
