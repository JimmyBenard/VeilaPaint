package studio.nuvora.veila.studio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.*
import java.nio.*
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import studio.nuvora.veila.core.*

class RevealRenderer(private val view: RevealView, private val session: StudioSession) :
    GLSurfaceView.Renderer {
    private var program = 0
    /** Lower composite, active layer, upper composite, then active layer mask. */
    private val textures = IntArray(4)
    private var currentTexture = 0
    private var followingTexture = 0
    private var upperTexture = 0
    private var renderedStep = -1
    private var loadingStep = -1
    private var generation = 0
    private var closed = false
    private var hasImage = false
    private val decoder = Executors.newSingleThreadExecutor()
    private val tileBuffer =
        ByteBuffer.allocateDirect(64 * 64 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
    private val vertices =
        ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
            position(0)
        }
    var viewportWidth = 1
        private set

    var viewportHeight = 1
        private set

    var recording: StudioRecorder? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        if (closed) return
        try {
            hasImage = false
            renderedStep = -1
            loadingStep = -1
            program = link(VERTEX, FRAGMENT)
            GLES30.glGenTextures(4, textures, 0)
            currentTexture = textures[0]
            followingTexture = textures[1]
            upperTexture = textures[2]
            val maxTexture = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTexture, 0)
            if (session.project.width > maxTexture[0] || session.project.height > maxTexture[0]) {
                session.failure =
                    "Le GPU limite les textures à ${maxTexture[0]} px. Réduisez la toile depuis Préparation."
                session.publish()
                return
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[3])
            textureParameters(false)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                GLES30.GL_R16UI,
                session.project.width,
                session.project.height,
                0,
                GLES30.GL_RED_INTEGER,
                GLES30.GL_UNSIGNED_SHORT,
                null,
            )
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "Allocation du masque GPU impossible." }
            session.engine.mask.markAllDirty()
            loadPair()
        } catch (e: Throwable) {
            program = 0
            hasImage = false
            session.failure =
                "Initialisation de l’Atelier impossible : ${e.message ?: "GPU incompatible"}"
            session.publish()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (viewportWidth > 1 && (viewportWidth != width || viewportHeight != height)) {
            recording?.stop("Vidéo finalisée après changement d’orientation.")
            recording = null
        }
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (closed || program == 0) return
        synchronizeStep()
        if (renderedStep == session.project.currentStep) uploadDirty()
        drawScene(viewportWidth, viewportHeight)
        if (session.ready) recording?.frame(this)
    }

    fun synchronizeStep() {
        if (
            program == 0 ||
                closed ||
                renderedStep == session.project.currentStep ||
                loadingStep == session.project.currentStep
        ) return
        loadPair()
    }

    private fun loadPair() {
        val token = ++generation
        val step = session.project.currentStep
        val project = session.project
        val states = session.layerSnapshotsForRendering()
        loadingStep = step
        session.ready = false
        session.publish()
        decoder.execute {
            var active: Bitmap? = null
            var lower: Bitmap? = null
            var upper: Bitmap? = null
            try {
                lower = composeLayers(project, 0, step, states)
                upper = composeLayers(project, step + 1, project.stages.size, states)
                active = decode(project.stages[step].image)
                val assistanceMaps = DirectionalGuideAnalyzer.buildMaps(active, lower)
                val activeBitmap = active
                val lowerBitmap = lower
                val upperBitmap = upper
                view.queueEvent {
                    try {
                        if (!closed && token == generation) {
                            uploadBitmap(currentTexture, lowerBitmap!!)
                            uploadBitmap(followingTexture, activeBitmap!!)
                            uploadBitmap(upperTexture, upperBitmap!!)
                            session.engine.setDirectionalGuide(assistanceMaps.directionalGuide)
                            session.engine.setTraitIsolationMap(assistanceMaps.traitIsolationMap)
                            renderedStep = step
                            loadingStep = -1
                            session.engine.mask.markAllDirty()
                            hasImage = true
                            session.ready = true
                            session.publish()
                            view.requestRender()
                        }
                    } catch (e: Throwable) {
                        loadError(e, token)
                    } finally {
                        activeBitmap?.recycle()
                        lowerBitmap?.recycle()
                        upperBitmap?.recycle()
                    }
                }
            } catch (e: Throwable) {
                active?.recycle()
                lower?.recycle()
                upper?.recycle()
                loadError(e, token)
            }
        }
    }

    /** Flattens one side of the active layer at a time, respecting transparent source pixels. */
    private fun composeLayers(
        project: studio.nuvora.veila.data.Project,
        fromIndex: Int,
        untilIndex: Int,
        states: Map<String, EngineSnapshot>,
    ): Bitmap {
        val result = Bitmap.createBitmap(project.width, project.height, Bitmap.Config.ARGB_8888)
        result.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(result)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val stripeHeight = 32
        val pixels = IntArray(project.width * stripeHeight)
        for (index in fromIndex until untilIndex) {
            val layer = project.stages[index]
            val state = states[layer.id] ?: continue
            val bitmap = decode(layer.image)
            try {
                var y = 0
                while (y < project.height) {
                    val rows = minOf(stripeHeight, project.height - y)
                    bitmap.getPixels(pixels, 0, project.width, 0, y, project.width, rows)
                    for (row in 0 until rows) for (x in 0 until project.width) {
                        val offset = row * project.width + x
                        val source = pixels[offset]
                        val reveal = state.mask[(y + row) * project.width + x].toInt()
                        val alpha = Color.alpha(source) * reveal / 1000
                        pixels[offset] = (source and 0x00ffffff) or (alpha shl 24)
                    }
                    bitmap.setPixels(pixels, 0, project.width, 0, y, project.width, rows)
                    y += rows
                }
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
            } finally {
                bitmap.recycle()
            }
        }
        return result
    }

    private fun loadError(e: Throwable, token: Int) {
        view.queueEvent {
            if (!closed && token == generation) {
                loadingStep = -1
                session.failure =
                    if (e is OutOfMemoryError) "Mémoire insuffisante. Réduisez la toile."
                    else "Chargement impossible : ${e.message}"
                session.publish()
            }
        }
    }

    private fun decode(name: String) =
        BitmapFactory.decodeFile(
            session.repository.asset(session.project, name).absolutePath,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
                inMutable = true
            },
        ) ?: error("Image illisible")

    private fun uploadBitmap(texture: Int, bitmap: Bitmap) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        textureParameters(true)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "Allocation de texture impossible." }
    }

    private fun uploadDirty() {
        val mask = session.engine.mask
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[3])
        for (id in mask.consumeDirty()) {
            val data = mask.copyTile(id)
            val (x, y) = mask.tileOrigin(id)
            tileBuffer.clear()
            tileBuffer.put(data)
            tileBuffer.flip()
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                x,
                y,
                mask.tileWidth(id),
                mask.tileHeight(id),
                GLES30.GL_RED_INTEGER,
                GLES30.GL_UNSIGNED_SHORT,
                tileBuffer,
            )
        }
    }

    fun drawScene(width: Int, height: Int, x: Int = 0, y: Int = 0) {
        GLES30.glViewport(x, y, width, height)
        GLES30.glClearColor(.022f, .032f, .06f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (renderedStep < 0 || !hasImage) return
        GLES30.glUseProgram(program)
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(program, "uViewport"),
            width.toFloat(),
            height.toFloat(),
        )
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(program, "uImage"),
            session.project.width.toFloat(),
            session.project.height.toFloat(),
        )
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(program, "uCenter"),
            session.camera.centerX,
            session.camera.centerY,
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(program, "uScale"),
            session.camera.scale(width, height, session.project.width, session.project.height),
        )
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(program, "uRotation"),
            kotlin.math.cos(session.camera.rotation),
            kotlin.math.sin(session.camera.rotation),
        )
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, currentTexture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, followingTexture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, upperTexture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[3])
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uBelow"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uActive"), 1)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uAbove"), 2)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uMask"), 3)
        GLES30.glUniform1i(
            GLES30.glGetUniformLocation(program, "uUnrevealedOverlay"),
            if (session.unrevealedOverlayVisible) 1 else 0,
        )
        GLES30.glUniform1i(
            GLES30.glGetUniformLocation(program, "uTemporaryPreview"),
            if (session.temporaryPreviewVisible) 1 else 0,
        )
        vertices.position(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, vertices)
        GLES30.glEnableVertexAttribArray(0)
        vertices.position(2)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, vertices)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    fun close() {
        closed = true
        generation++
        recording?.stop()
        recording = null
        decoder.shutdown()
    }

    companion object {
        fun textureParameters(linear: Boolean) {
            val filter = if (linear) GLES30.GL_LINEAR else GLES30.GL_NEAREST
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_WRAP_S,
                GLES30.GL_CLAMP_TO_EDGE,
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_WRAP_T,
                GLES30.GL_CLAMP_TO_EDGE,
            )
        }

        fun link(vertex: String, fragment: String): Int {
            fun shader(type: Int, source: String): Int {
                val s = GLES30.glCreateShader(type)
                GLES30.glShaderSource(s, source)
                GLES30.glCompileShader(s)
                val ok = IntArray(1)
                GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { GLES30.glGetShaderInfoLog(s) }
                return s
            }
            val vs = shader(GLES30.GL_VERTEX_SHADER, vertex)
            val fs = shader(GLES30.GL_FRAGMENT_SHADER, fragment)
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, vs)
            GLES30.glAttachShader(p, fs)
            GLES30.glLinkProgram(p)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { GLES30.glGetProgramInfoLog(p) }
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            return p
        }

        private const val VERTEX =
            """#version 300 es
            layout(location=0) in vec2 aPosition; layout(location=1) in vec2 aUv;
            uniform vec2 uViewport,uImage,uCenter,uRotation; uniform float uScale; out vec2 vUv;
            void main(){vec2 d=aPosition*uImage-uCenter;
            vec2 r=vec2(uRotation.x*d.x-uRotation.y*d.y,uRotation.y*d.x+uRotation.x*d.y);
            vec2 p=r*uScale+uViewport*0.5;
            gl_Position=vec4(p.x/uViewport.x*2.0-1.0,1.0-p.y/uViewport.y*2.0,0.0,1.0);vUv=aUv;}
        """
        private const val FRAGMENT =
            """#version 300 es
            precision highp float; precision highp usampler2D;
            uniform sampler2D uBelow,uActive,uAbove; uniform usampler2D uMask; uniform vec2 uImage; uniform int uUnrevealedOverlay,uTemporaryPreview;
            in vec2 vUv;out vec4 color;
            float maskAt(ivec2 p){return float(texelFetch(uMask,clamp(p,ivec2(0),ivec2(uImage)-1),0).r)/1000.0;}
            void main(){vec2 p=vUv*uImage-0.5;ivec2 b=ivec2(floor(p));vec2 f=fract(p);
              float m=mix(mix(maskAt(b),maskAt(b+ivec2(1,0)),f.x),mix(maskAt(b+ivec2(0,1)),maskAt(b+ivec2(1,1)),f.x),f.y);
              if(uTemporaryPreview==1)m=1.0;
              float checker=mod(floor(vUv.x*uImage.x/24.0)+floor(vUv.y*uImage.y/24.0),2.0);
              vec3 bg=mix(vec3(0.12),vec3(0.16),checker); vec4 below=texture(uBelow,vUv); vec4 activeLayer=texture(uActive,vUv); vec4 above=texture(uAbove,vUv);
              // Android uploads ARGB bitmaps premultiplied. Composite in display order instead
              // of mixing RGB values: selection never changes revealed layers' final result.
              vec3 shown=below.rgb+bg*(1.0-below.a); float revealAlpha=activeLayer.a*m;
              shown=activeLayer.rgb*m+shown*(1.0-revealAlpha);
              shown=mix(shown,above.rgb,above.a);
              if(uUnrevealedOverlay==1) shown=mix(shown,vec3(0.92,0.04,0.08),pow(1.0-m,0.65)*0.72);
              color=vec4(shown,1.0);}
        """
    }
}
