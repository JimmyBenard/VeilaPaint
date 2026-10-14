package studio.nuvora.veila.studio

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.MotionEvent
import android.view.ViewConfiguration
import javax.microedition.khronos.egl.EGL10
import studio.nuvora.veila.core.*

class RevealView(context: Context, val session: StudioSession) : GLSurfaceView(context) {
    val painter = RevealRenderer(this, session)
    private val holdSlop = ViewConfiguration.get(context).scaledTouchSlop * 1.5f
    private var holdEligible = false
    private var holdActive = false
    private var holdPointers = emptyMap<Int, Pair<Float, Float>>()
    private val previewHold = Runnable {
        if (holdEligible && holdPointers.size == 2 && !holdActive) {
            holdActive = true
            submit { session.beginTemporaryPreview() }
        }
    }

    init {
        session.recorderProvider = { painter.recording }
        session.afterConsultationHidden = { action ->
            // One UI frame clears Compose text; the following GL submission verifies the canvas
            // overlay is also absent before MediaRecorder resumes.
            postOnAnimation { postOnAnimation { submit(action) } }
        }
        session.router.setTouchSlop(ViewConfiguration.get(context).scaledTouchSlop * 1.5f)
        setEGLContextClientVersion(3)
        setEGLConfigChooser(
            EGLConfigChooser { egl, display ->
                chooseEglConfig(egl, display)
            }
        )
        preserveEGLContextOnPause = true
        setRenderer(painter)
        renderMode = RENDERMODE_WHEN_DIRTY
        contentDescription =
            "Toile de redessin. Stylet ou un doigt selon le réglage pour dévoiler. Deux doigts pour zoomer, déplacer et tourner, trois pour annuler, quatre pour rétablir."
        isFocusable = true
        if (Build.VERSION.SDK_INT >= 33) isAutoHandwritingEnabled = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun submit(block: () -> Unit) {
        queueEvent {
            block()
            painter.synchronizeStep()
            requestRender()
        }
    }

    private fun cancelPreviewHold(endPreview: Boolean = false) {
        removeCallbacks(previewHold)
        holdEligible = false
        if (endPreview && holdActive) {
            holdActive = false
            submit { session.endTemporaryPreview() }
        }
    }

    private fun trackPreviewHold(event: MotionEvent) {
        val fingers = (0 until event.pointerCount).filter {
            event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (fingers.size == 2 && !holdActive) {
                    holdPointers = fingers.associate { i ->
                        event.getPointerId(i) to (event.getX(i) to event.getY(i))
                    }
                    holdEligible = true
                    postDelayed(previewHold, 300)
                } else if (fingers.size > 2) cancelPreviewHold(holdActive)
            }
            MotionEvent.ACTION_MOVE -> if (holdEligible || holdActive) {
                val moved = fingers.any { i ->
                    val start = holdPointers[event.getPointerId(i)] ?: return@any true
                    val dx = event.getX(i) - start.first
                    val dy = event.getY(i) - start.second
                    dx * dx + dy * dy > holdSlop * holdSlop
                }
                if (moved || fingers.size != 2) cancelPreviewHold(holdActive)
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                cancelPreviewHold(holdActive)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) requestUnbufferedDispatch(event)
        trackPreviewHold(event)
        val frames = AndroidInputMapper.frames(event)
        if (frames.isEmpty()) return true
        val diagnostic =
            if (
                event.actionMasked == MotionEvent.ACTION_DOWN ||
                    event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
            )
                AndroidInputMapper.diagnostic(event)
            else null
        val vw = width
        val vh = height
        submit {
            if (diagnostic != null) session.inputDiagnostic = diagnostic
            frames.forEach { session.input(it, vw, vh) }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER) {
            val diagnostic = AndroidInputMapper.diagnostic(event)
            submit {
                session.inputDiagnostic = diagnostic
                session.publish()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * The video recorder prefers a recordable surface, but some otherwise compatible tablet
     * drivers do not expose a recordable RGBA8888 config. Falling back keeps the Atelier usable;
     * recording then reports its own capability instead of preventing the screen from opening.
     */
    private fun chooseEglConfig(
        egl: EGL10,
        display: javax.microedition.khronos.egl.EGLDisplay,
    ): javax.microedition.khronos.egl.EGLConfig {
        val candidates = listOf(
            attributes(EGL_OPENGL_ES3_BIT_KHR, recordable = true),
            attributes(EGL_OPENGL_ES2_BIT, recordable = true),
            attributes(EGL_OPENGL_ES3_BIT_KHR, recordable = false),
            attributes(EGL_OPENGL_ES2_BIT, recordable = false),
        )
        candidates.forEach { requested ->
            val configs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(1)
            val count = IntArray(1)
            if (egl.eglChooseConfig(display, requested, configs, 1, count) && count[0] > 0)
                return configs[0]!!
        }
        error("Aucune configuration OpenGL ES 3 compatible n’est disponible sur cet appareil.")
    }

    private fun attributes(renderableType: Int, recordable: Boolean): IntArray =
        buildList {
            add(EGL10.EGL_RED_SIZE); add(8)
            add(EGL10.EGL_GREEN_SIZE); add(8)
            add(EGL10.EGL_BLUE_SIZE); add(8)
            add(EGL10.EGL_ALPHA_SIZE); add(8)
            add(EGL10.EGL_SURFACE_TYPE); add(EGL10.EGL_WINDOW_BIT)
            add(EGL10.EGL_RENDERABLE_TYPE); add(renderableType)
            if (recordable) { add(EGL_RECORDABLE_ANDROID); add(1) }
            add(EGL10.EGL_NONE)
        }.toIntArray()

    private companion object {
        // EGL_KHR_create_context / EGL_ANDROID_recordable values; unavailable as EGL10 constants.
        const val EGL_OPENGL_ES2_BIT = 0x0004
        const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
