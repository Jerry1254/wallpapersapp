package com.qingjing.wallpaper_android.playback

import android.media.MediaPlayer
import android.view.SurfaceHolder
import java.io.File

/** Every preview/engine owns its player. No global player or Surface can be stolen by a second preview. */
internal class VideoSurfacePlayer(private val ready: () -> Unit, private val failed: () -> Unit) {
    private var player: MediaPlayer? = null
    var rendered: Boolean = false
        private set
    val playing: Boolean get() = try { player?.isPlaying == true } catch (_: Exception) { false }
    fun frameMetrics(): Map<String,Int> = try {
        val metrics = player?.metrics
        if(metrics == null) emptyMap() else listOf(
            "frames" to MediaPlayer.MetricsConstants.FRAMES,
            "droppedFrames" to MediaPlayer.MetricsConstants.FRAMES_DROPPED,
            "decodeErrors" to MediaPlayer.MetricsConstants.ERRORS,
        ).filter { metrics.containsKey(it.second) }.associate { it.first to metrics.getInt(it.second) }
    } catch (_: Exception) { emptyMap() }
    fun open(file: File, holder: SurfaceHolder) {
        close()
        try {
            val next = MediaPlayer(); player = next
            // Wallpaper SurfaceHolder rejects setKeepScreenOn, which setDisplay invokes even for false.
            // Pass the Surface directly; visibility controls playback without any screen/wake flags.
            next.setDataSource(file.absolutePath); next.setSurface(holder.surface)
            next.isLooping = true; next.setVolume(0f,0f)
            next.setOnPreparedListener {
                if (player === it && holder.surface.isValid) {
                    try { it.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING); it.start() }
                    catch (_: Exception) { close(); failed() }
                }
            }
            next.setOnInfoListener { media,what,_ ->
                if (player === media && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    rendered = true
                    try { ready() } catch (_: Exception) { close(); failed() }
                }
                false
            }
            next.setOnErrorListener { media,_,_ -> if (player === media) { close(); failed() }; true }
            next.prepareAsync()
        } catch (_: Exception) { close(); failed() }
    }
    fun close() { val old = player; player = null; rendered = false; try { old?.release() } catch (_: Exception) { } }
}
