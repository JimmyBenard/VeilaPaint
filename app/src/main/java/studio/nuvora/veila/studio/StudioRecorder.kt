package studio.nuvora.veila.studio

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.*
import android.opengl.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import studio.nuvora.veila.core.*

/** Real MP4 encoder fed by the app's own EGL surface. No MediaProjection or audio. */
class StudioRecorder(
    private val activity: Activity,
    private val captureRoot: View,
    private val view: RevealView,
    private val onSaved: (File) -> Unit,
) {
    private val session = view.session
    private val lifecycle = RecordingLifecycle()
    private var recorder: MediaRecorder? = null
    private var surface: android.view.Surface? = null
    private var encoderSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var width = 0
    private var height = 0
    @Volatile private var fps = 30
    private var lastFrame = 0L
    private var file: File? = null
    private var generation = 0
    @Volatile private var capturePending = false
    private var uiBitmap: Bitmap? = null
    private var uiTexture = 0
    private var uiProgram = 0
    private var slow = 0
    private val main = Handler(Looper.getMainLooper())
    private val quad =
        ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f, -1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f))
            position(0)
        }
    private val pump =
        object : Runnable {
            override fun run() {
                if (
                    lifecycle.state == RecordingState.IDLE ||
                        lifecycle.state == RecordingState.STOPPING
                )
                    return
                if (lifecycle.state == RecordingState.RECORDING) view.requestRender()
                main.postDelayed(this, (1000L / fps).coerceAtLeast(16))
            }
        }

    fun start() {
        check(lifecycle.state == RecordingState.IDLE)
        val withUi = session.project.videoWithUi
        val sw = if (withUi) captureRoot.width else view.painter.viewportWidth
        val sh = if (withUi) captureRoot.height else view.painter.viewportHeight
        require(sw > 0 && sh > 0) { "Atelier pas encore visible." }
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        var selected: Triple<Int, Int, Int>? = null
        for ((side, rate) in
            listOf(
                    session.project.videoHeight to session.project.videoFps,
                    session.project.videoHeight to 30,
                    720 to 30,
                )
                .distinct()) {
            val factor =
                minOf(
                    side.toFloat() / minOf(sw, sh),
                    (if (side == 1080) 1920 else 1280).toFloat() / maxOf(sw, sh),
                )
            val w = ((sw * factor).toInt() / 16 * 16).coerceAtLeast(16)
            val h = ((sh * factor).toInt() / 16 * 16).coerceAtLeast(16)
            val format =
                MediaFormat.createVideoFormat("video/avc", w, h).apply {
                    setInteger(MediaFormat.KEY_FRAME_RATE, rate)
                    setInteger(MediaFormat.KEY_BIT_RATE, if (side == 1080) 8_000_000 else 4_000_000)
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                    )
                }
            val name = codecs.findEncoderForFormat(format)
            val info = codecs.codecInfos.firstOrNull { it.name == name }
            if (
                info != null &&
                    info
                        .getCapabilitiesForType("video/avc")
                        .videoCapabilities
                        .areSizeAndRateSupported(w, h, rate.toDouble())
            ) {
                selected = Triple(w, h, rate)
                break
            }
        }
        val chosen = selected ?: error("Aucun encodeur H.264 compatible 720p/30 FPS.")
        width = chosen.first
        height = chosen.second
        fps = chosen.third
        val videos = File(activity.filesDir, "videos").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())
        file = File(videos, "Veila-${session.project.id.take(8)}-$stamp.pending.mp4")
        try {
            @Suppress("DEPRECATION") val media = MediaRecorder()
            recorder = media
            media.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            media.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            media.setVideoSize(width, height)
            media.setVideoFrameRate(fps)
            media.setVideoEncodingBitRate(if (maxOf(width, height) > 1280) 8_000_000 else 4_000_000)
            media.setOutputFile(file!!.absolutePath)
            media.setOnErrorListener { _, _, _ ->
                view.submit { stop("Enregistrement interrompu par l’encodeur.") }
            }
            media.prepare()
            surface = media.surface
            display = EGL14.eglGetCurrentDisplay()
            context = EGL14.eglGetCurrentContext()
            val configId = IntArray(1)
            check(EGL14.eglQueryContext(display, context, EGL14.EGL_CONFIG_ID, configId, 0))
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            check(
                EGL14.eglChooseConfig(
                    display,
                    intArrayOf(EGL14.EGL_CONFIG_ID, configId[0], EGL14.EGL_NONE),
                    0,
                    configs,
                    0,
                    1,
                    count,
                    0,
                ) && count[0] > 0
            )
            encoderSurface =
                EGL14.eglCreateWindowSurface(
                    display,
                    configs[0],
                    surface,
                    intArrayOf(EGL14.EGL_NONE),
                    0,
                )
            check(encoderSurface != EGL14.EGL_NO_SURFACE) { "Surface vidéo indisponible." }
            if (withUi) {
                uiBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val id = IntArray(1)
                GLES30.glGenTextures(1, id, 0)
                uiTexture = id[0]
                uiProgram = RevealRenderer.link(UI_VERTEX, UI_FRAGMENT)
            }
            media.start()
            lifecycle.start()
            generation++
            session.videoState = lifecycle.state
            session.videoMessage = "$width × $height · $fps FPS · ${if(withUi)"Atelier"else"Toile"}"
            session.publish()
            main.post(pump)
        } catch (e: Exception) {
            release()
            file?.delete()
            throw e
        }
    }

    fun pause() {
        recorder?.pause()
        lifecycle.pause()
        session.videoState = lifecycle.state
        session.publish()
    }

    fun resume() {
        recorder?.resume()
        lifecycle.resume()
        session.videoState = lifecycle.state
        session.publish()
        lastFrame = 0
    }

    fun frame(renderer: RevealRenderer) {
        if (lifecycle.state != RecordingState.RECORDING) return
        val now = SystemClock.elapsedRealtimeNanos()
        if (now - lastFrame < 1_000_000_000L / fps) return
        lastFrame = now
        if (session.project.videoWithUi) captureUi(renderer)
        else encode { renderer.drawScene(width, height) }
    }

    private fun captureUi(renderer: RevealRenderer) {
        if (capturePending) return
        val target = uiBitmap ?: return
        capturePending = true
        val token = generation
        main.post {
            if (lifecycle.state != RecordingState.RECORDING || !captureRoot.isAttachedToWindow) {
                capturePending = false
                return@post
            }
            val loc = IntArray(2)
            captureRoot.getLocationInWindow(loc)
            val rect = Rect(loc[0], loc[1], loc[0] + captureRoot.width, loc[1] + captureRoot.height)
            val canvasLocation = IntArray(2)
            view.getLocationInWindow(canvasLocation)
            val sx = width.toFloat() / captureRoot.width
            val sy = height.toFloat() / captureRoot.height
            val canvasRect =
                Rect(
                    ((canvasLocation[0] - loc[0]) * sx).toInt(),
                    ((canvasLocation[1] - loc[1]) * sy).toInt(),
                    ((canvasLocation[0] - loc[0] + view.width) * sx).toInt(),
                    ((canvasLocation[1] - loc[1] + view.height) * sy).toInt(),
                )
            try {
                PixelCopy.request(
                    activity.window,
                    rect,
                    target,
                    { result ->
                        view.queueEvent {
                            capturePending = false
                            if (
                                token == generation &&
                                    lifecycle.state == RecordingState.RECORDING &&
                                    result == PixelCopy.SUCCESS
                            )
                                encode { drawUi(target, renderer, canvasRect) }
                        }
                    },
                    main,
                )
            } catch (_: Exception) {
                capturePending = false
            }
        }
    }

    private fun encode(draw: () -> Unit) {
        val drawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val readSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        val before = SystemClock.elapsedRealtimeNanos()
        try {
            check(EGL14.eglMakeCurrent(display, encoderSurface, encoderSurface, context))
            draw()
            EGLExt.eglPresentationTimeANDROID(
                display,
                encoderSurface,
                SystemClock.elapsedRealtimeNanos(),
            )
            check(EGL14.eglSwapBuffers(display, encoderSurface)) { "L’encodeur ne répond plus." }
        } catch (e: Exception) {
            EGL14.eglMakeCurrent(display, drawSurface, readSurface, context)
            stop("Vidéo interrompue : ${e.message}")
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY)
                EGL14.eglMakeCurrent(display, drawSurface, readSurface, context)
        }
        if (SystemClock.elapsedRealtimeNanos() - before > 12_000_000L) slow++
        else slow = maxOf(0, slow - 1)
        if (slow >= 5 && fps > 15) {
            fps = if (fps > 30) 30 else 15
            slow = 0
            session.videoMessage = "Capture réduite à $fps FPS pour préserver le stylet."
            session.publish()
        }
    }

    private fun drawUi(bitmap: Bitmap, renderer: RevealRenderer, canvasRect: Rect) {
        // PixelCopy(Window) copies the window buffer. SurfaceView is a separate surface
        // behind its transparent hole. Render the canvas, then composite the window's
        // premultiplied RGBA overlay so controls and panels remain above the artwork.
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val y = height - canvasRect.bottom
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glScissor(
            canvasRect.left,
            y,
            canvasRect.width().coerceAtLeast(1),
            canvasRect.height().coerceAtLeast(1),
        )
        renderer.drawScene(
            canvasRect.width().coerceAtLeast(1),
            canvasRect.height().coerceAtLeast(1),
            canvasRect.left,
            y,
        )
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(uiProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, uiTexture)
        RevealRenderer.textureParameters(true)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(uiProgram, "uTexture"), 0)
        quad.position(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, quad)
        GLES30.glEnableVertexAttribArray(0)
        quad.position(2)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, quad)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    fun stop(message: String = "Vidéo enregistrée.") {
        if (lifecycle.state == RecordingState.IDLE || lifecycle.state == RecordingState.STOPPING)
            return
        lifecycle.stop()
        generation++
        main.removeCallbacks(pump)
        session.videoState = lifecycle.state
        session.publish()
        try {
            recorder?.stop()
            val source = file ?: error("Fichier manquant")
            val final = File(source.parentFile, source.name.replace(".pending.mp4", ".mp4"))
            check(source.renameTo(final)) { "Finalisation du fichier impossible." }
            session.videoMessage = message
            main.post { onSaved(final) }
        } catch (e: Exception) {
            file?.delete()
            session.videoMessage =
                "Vidéo non conservée : ${e.message?:"enregistrement trop court"}. Le dessin reste sauvegardé."
        } finally {
            release()
            lifecycle.finalized()
            session.videoState = lifecycle.state
            session.publish()
        }
    }

    private fun release() {
        main.removeCallbacks(pump)
        if (encoderSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encoderSurface)
        encoderSurface = EGL14.EGL_NO_SURFACE
        try {
            recorder?.reset()
        } catch (_: Exception) {}
        recorder?.release()
        recorder = null
        surface?.release()
        surface = null
        // A PixelCopy callback may still own this bitmap. Do not recycle it early.
        uiBitmap = null
        if (uiTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(uiTexture), 0)
            uiTexture = 0
        }
        if (uiProgram != 0) {
            GLES30.glDeleteProgram(uiProgram)
            uiProgram = 0
        }
    }

    companion object {
        private const val UI_VERTEX =
            """#version 300 es
            layout(location=0) in vec2 p;layout(location=1) in vec2 uv;out vec2 vUv;
            void main(){gl_Position=vec4(p,0.,1.);vUv=uv;}
        """
        private const val UI_FRAGMENT =
            """#version 300 es
            precision mediump float;uniform sampler2D uTexture;in vec2 vUv;out vec4 color;
            void main(){color=texture(uTexture,vUv);}
        """
    }
}
