package studio.nuvora.veila.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import studio.nuvora.veila.core.VideoSpeed

data class VideoInfo(val duration: Long, val width: Int, val height: Int) {
    companion object {
        fun read(file: File): VideoInfo {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                fun value(key: Int) = retriever.extractMetadata(key)?.toLongOrNull() ?: 0L
                val duration = value(MediaMetadataRetriever.METADATA_KEY_DURATION)
                var width = value(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
                var height = value(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
                if (value(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180L != 0L) {
                    val swap = width
                    width = height
                    height = swap
                }
                require(width > 0 && height > 0 && duration > 0) {
                    "Vidéo illisible ou incomplète."
                }
                return VideoInfo(duration, width, height)
            } finally {
                retriever.release()
            }
        }
    }
}

data class VideoEditState(
    val source: File? = null,
    val info: VideoInfo? = null,
    val speed: Int = 1,
    val prepared: File? = null,
    val preparedSpeed: Int = 1,
    val preparedInfo: VideoInfo? = null,
    val preparing: Boolean = false,
    val progress: Int? = null,
    val error: String? = null,
) {
    val preview: File?
        get() =
            if (info == null || preparing) null
            else if (speed == 1) source else prepared?.takeIf { preparedSpeed == speed }

    val previewInfo: VideoInfo?
        get() = if (speed == 1) info else preparedInfo?.takeIf { preparedSpeed == speed }

    val exportName: String
        get() = "${source?.nameWithoutExtension ?: "VeilaPaint"}-x$speed.mp4"
}

/** A single, cancellable hardware transcode. The exact preview file is also the export source. */
@OptIn(UnstableApi::class)
class VideoEditor(private val context: Context, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(VideoEditState())
    val state = mutable.asStateFlow()
    private var transformer: Transformer? = null
    private var pending: File? = null
    private var job: Job? = null
    private var progressJob: Job? = null
    private var generation = 0L

    fun open(file: File) {
        close()
        mutable.value = VideoEditState(source = file)
        val token = generation
        job =
            scope.launch {
                try {
                    val info =
                        withContext(Dispatchers.IO) {
                            cleanCache()
                            VideoInfo.read(file)
                        }
                    if (token == generation) mutable.value = mutable.value.copy(info = info)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (token == generation) mutable.value = mutable.value.copy(error = e.message)
                }
            }
    }

    fun setSpeed(speed: Int) {
        VideoSpeed.validate(speed)
        if (mutable.value.preparing) return
        mutable.value = mutable.value.copy(speed = speed, error = null)
    }

    fun prepare() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val current = mutable.value
        val source = current.source ?: return
        if (current.info == null || current.preparing || current.preview != null) return
        cancel()
        val token = generation
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        val target = File(directory, "veila-${UUID.randomUUID()}-x${current.speed}.mp4")
        val temporary = File(directory, target.nameWithoutExtension + ".pending.mp4")
        pending = temporary
        mutable.value = current.copy(preparing = true, progress = null, error = null)
        try {
            require(directory.usableSpace >= 32L * 1024 * 1024) {
                "Espace insuffisant pour préparer la vidéo."
            }
            val effects =
                Effects(
                    emptyList(),
                    listOf(
                        SpeedChangeEffect(current.speed.toFloat()),
                        FrameDropEffect.createDefaultFrameDropEffect(
                            VideoSpeed.OUTPUT_FPS.toFloat()
                        ),
                    ),
                )
            val item =
                EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                    .setRemoveAudio(true) // Atelier recordings are silent.
                    .setEffects(effects)
                    .build()
            transformer =
                Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setMuxerFactory(
                        DefaultMuxer.Factory()
                            .setVideoDurationUs(
                                (VideoSpeed.durationMillis(current.info.duration, current.speed) *
                                        1000L)
                                    .coerceAtLeast(1_000_000L / VideoSpeed.OUTPUT_FPS + 1)
                            )
                    )
                    .addListener(
                        object : Transformer.Listener {
                            override fun onCompleted(
                                composition: Composition,
                                exportResult: ExportResult,
                            ) {
                                if (token != generation) return
                                transformer = null
                                progressJob?.cancel()
                                job =
                                    scope.launch {
                                        try {
                                            val info =
                                                withContext(Dispatchers.IO) {
                                                    VideoInfo.read(temporary)
                                                }
                                            if (token != generation) return@launch
                                            check(temporary.renameTo(target)) {
                                                "Impossible de finaliser l’aperçu."
                                            }
                                            pending = null
                                            mutable.value =
                                                mutable.value.copy(
                                                    preparing = false,
                                                    progress = 100,
                                                    prepared = target,
                                                    preparedSpeed = current.speed,
                                                    preparedInfo = info,
                                                )
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            fail(token, e)
                                        }
                                    }
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException,
                            ) {
                                fail(token, exportException)
                            }
                        }
                    )
                    .build()
            transformer!!.start(item, temporary.absolutePath)
            progressJob =
                scope.launch {
                    val holder = ProgressHolder()
                    while (isActive && token == generation) {
                        val available =
                            transformer?.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE
                        mutable.value =
                            mutable.value.copy(progress = if (available) holder.progress else null)
                        delay(250)
                    }
                }
        } catch (e: Exception) {
            fail(token, e)
        }
    }

    private fun fail(token: Long, error: Exception) {
        if (token != generation) return
        cancel()
        mutable.value =
            mutable.value.copy(
                error =
                    "Préparation impossible : ${error.message ?: "encodeur indisponible"}. La vidéo originale est conservée."
            )
    }

    fun cancel(message: String? = null) {
        generation++
        progressJob?.cancel()
        progressJob = null
        job?.cancel()
        job = null
        transformer?.cancel()
        transformer = null
        pending?.delete()
        pending = null
        mutable.value = mutable.value.copy(preparing = false, progress = null, error = message)
    }

    fun background() {
        if (mutable.value.preparing)
            cancel("Préparation interrompue. Vous pouvez relancer l’aperçu.")
    }

    fun close() {
        cancel()
        mutable.value = VideoEditState()
    }

    // Finished previews remain available to SAF and recipient apps after the dialog closes.
    // On the next opening, delete only our cache files older than 24 hours.
    private fun cleanCache() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        File(context.cacheDir, "exports")
            .listFiles()
            .orEmpty()
            .filter { it.name.startsWith("veila-") && it.lastModified() < cutoff }
            .forEach { it.delete() }
    }
}
