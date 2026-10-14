package studio.nuvora.veila

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import studio.nuvora.veila.core.*
import studio.nuvora.veila.core.VideoSpeed
import studio.nuvora.veila.data.*
import studio.nuvora.veila.studio.*
import studio.nuvora.veila.video.VideoEditor

/** Actual Android surfaces/codecs/MotionEvents; these tests do not fake the recorder. */
@RunWith(AndroidJUnit4::class)
class NativeIntegrationTest {
    private data class Studio(
        val activity: MainActivity,
        val root: FrameLayout,
        val view: RevealView,
        val session: StudioSession,
    )

    private fun setup(scenario: ActivityScenario<MainActivity>, withUi: Boolean = false): Studio {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = ProjectRepository(context)
        var p = Project(name = "Validation native", width = 512, height = 512, videoWithUi = withUi)
        p =
            p.copy(
                stages =
                    listOf(Color.CYAN, Color.MAGENTA, Color.YELLOW).mapIndexed { i, color ->
                        val bitmap =
                            Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
                                eraseColor(color)
                            }
                        val file = File(context.cacheDir, "native-step-$i.png")
                        file.outputStream().use {
                            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                        bitmap.recycle()
                        repo.import(p, Uri.fromFile(file), "Étape ${i + 1}")
                    }
            )
        repo.save(p, RevealEngine(512, 512, 3).snapshot())
        val session = StudioSession(repo, repo.load(p.id))
        var studio: Studio? = null
        scenario.onActivity { activity ->
            val root = FrameLayout(activity)
            root.setBackgroundColor(Color.BLACK)
            val view = RevealView(activity, session)
            val barHeight = (72 * activity.resources.displayMetrics.density).toInt()
            root.addView(
                view,
                FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    .apply { topMargin = barHeight },
            )
            root.addView(
                TextView(activity).apply {
                    text = "VEILA PAINT · VALIDATION VIDÉO"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.RED)
                    textSize = 18f
                    gravity = android.view.Gravity.CENTER
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barHeight),
            )
            activity.setContentView(root)
            view.onResume()
            view.submit { session.resume() }
            studio = Studio(activity, root, view, session)
        }
        val result = studio!!
        await("Textures chargées") { result.session.status.value.ready }
        gl(result) {}
        return result
    }

    private fun await(label: String, timeout: Long = 30_000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(25)
        assertTrue(label, condition())
    }

    private fun gl(s: Studio, block: () -> Unit) {
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        s.view.submit {
            try {
                block()
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                done.countDown()
            }
        }
        assertTrue("GL thread did not respond", done.await(30, TimeUnit.SECONDS))
        error.get()?.let { throw AssertionError("GL operation failed", it) }
    }

    private fun cleanup(s: Studio) {
        gl(s) {
            s.view.painter.close()
            s.session.pause()
            s.session.shutdown()
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { s.view.onPause() }
    }

    @Test
    fun motionEventFingerAndStylusPaintAndTheFingerSettingCanDisableOnlyTouch() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val s = setup(scenario)
            fun contact(tool: Int) {
                scenario.onActivity {
                    val now = SystemClock.uptimeMillis()
                    val properties =
                        arrayOf(
                            MotionEvent.PointerProperties().apply {
                                id = 0
                                toolType = tool
                            }
                        )
                    val coordinates =
                        arrayOf(
                            MotionEvent.PointerCoords().apply {
                                x = s.view.width / 2f
                                y = s.view.height / 2f
                                pressure = .6f
                                size = .1f
                            }
                        )
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event =
                            MotionEvent.obtain(
                                now,
                                now + if (action == MotionEvent.ACTION_UP) 20 else 0,
                                action,
                                1,
                                properties,
                                coordinates,
                                0,
                                0,
                                1f,
                                1f,
                                0,
                                0,
                                if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS
                                else InputDevice.SOURCE_TOUCHSCREEN,
                                0,
                            )
                        s.view.dispatchTouchEvent(event)
                        event.recycle()
                    }
                }
                gl(s) {}
            }
            try {
                contact(MotionEvent.TOOL_TYPE_FINGER)
                gl(s) {
                    val fingerProgress = s.session.engine.progress
                    assertTrue(fingerProgress > 0.0)
                    s.session.undo()
                    assertEquals(0.0, s.session.engine.progress, 0.0)
                    s.session.redo()
                    assertEquals(fingerProgress, s.session.engine.progress, 0.0)
                    s.session.project = s.session.project.copy(fingerDrawingEnabled = false)
                    s.session.router.fingerDrawingEnabled = false
                }
                val beforeDisabledFinger = s.session.status.value.progress
                contact(MotionEvent.TOOL_TYPE_FINGER)
                gl(s) { assertEquals(beforeDisabledFinger, s.session.engine.progress, 0.0) }
                contact(MotionEvent.TOOL_TYPE_STYLUS)
                gl(s) {
                    val progress = s.session.engine.progress
                    assertTrue(progress > 0.0)
                    s.session.undo()
                    assertEquals(beforeDisabledFinger, s.session.engine.progress, 0.0)
                    s.session.redo()
                    assertEquals(progress, s.session.engine.progress, 0.0)
                }
            } finally {
                cleanup(s)
            }
        }
    }

    private fun video(withUi: Boolean, exportSpeed: Int? = null) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val s = setup(scenario, withUi)
            val saved = AtomicReference<File?>()
            try {
                gl(s) {
                    s.view.painter.recording =
                        StudioRecorder(s.activity, s.root, s.view) { saved.set(it) }
                    s.view.painter.recording!!.start()
                }
                SystemClock.sleep(1200)
                gl(s) { s.view.painter.recording!!.pause() }
                SystemClock.sleep(200)
                gl(s) {
                    assertTrue(s.session.clock.running)
                    s.view.painter.recording!!.resume()
                    s.session.engine.begin(Point(256f, 256f), 1f, Brush(100f, .5f, 1f))
                    s.session.engine.end()
                }
                SystemClock.sleep(1200)
                // The same finalize + timer pause operations run on leaving the real studio.
                gl(s) {
                    s.view.painter.recording!!.stop()
                    s.session.pause()
                }
                await("Fichier finalisé") { saved.get() != null }
                val file = saved.get()!!
                assertTrue(file.length() > 0)
                assertFalse(file.name.contains("pending"))
                val catalog = VideoRepository(s.activity.applicationContext)
                val entry = catalog.register(file, s.session.project)
                assertEquals(s.session.project.id, entry.projectId)
                assertTrue(entry.durationMillis > 0)
                assertTrue(entry.width > 0 && entry.height > 0)
                assertNotNull(catalog.thumbnail(entry))
                assertTrue(catalog.list().any { it.id == entry.id })
                MediaMetadataRetriever().use { media ->
                    media.setDataSource(file.absolutePath)
                    assertTrue(
                        media
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!
                            .toLong() >= 1000
                    )
                    val frame =
                        media.getFrameAtTime(100_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    assertNotNull("Le MP4 doit être décodable", frame)
                    val canvasPixel = frame!!.getPixel(frame.width / 2, frame.height / 2)
                    assertTrue(
                        "La toile cyan doit être présente, pas un écran noir",
                        Color.green(canvasPixel) > 140 && Color.blue(canvasPixel) > 140,
                    )
                    if (withUi) {
                        val toolbarPixel =
                            frame.getPixel(frame.width / 4, (frame.height * .02).toInt())
                        assertTrue(
                            "Le bandeau rouge doit être capturé",
                            Color.red(toolbarPixel) > 140 && Color.green(toolbarPixel) < 100,
                        )
                    }
                    frame.recycle()
                }
                if (exportSpeed != null) {
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                    val editor = VideoEditor(s.activity.applicationContext, scope)
                    try {
                        scenario.onActivity { editor.open(file) }
                        await("Métadonnées vidéo") {
                            editor.state.value.info != null || editor.state.value.error != null
                        }
                        assertNull(editor.state.value.error)
                        val originalDuration = editor.state.value.info!!.duration
                        val originalLength = file.length()
                        scenario.onActivity {
                            editor.setSpeed(exportSpeed)
                            editor.prepare()
                        }
                        await("Encodage accéléré", 120_000) {
                            !editor.state.value.preparing &&
                                (editor.state.value.preview != null ||
                                    editor.state.value.error != null)
                        }
                        assertNull(editor.state.value.error)
                        val result = editor.state.value.preview!!
                        assertNotEquals(file, result)
                        assertEquals(originalLength, file.length())
                        val actual = editor.state.value.previewInfo!!.duration
                        val expected =
                            VideoSpeed.durationMillis(originalDuration, exportSpeed)
                                .coerceAtLeast(34)
                        assertEquals(expected.toDouble(), actual.toDouble(), 150.0)
                        MediaMetadataRetriever().use { media ->
                            media.setDataSource(result.absolutePath)
                            val frame =
                                media.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            assertNotNull("L’aperçu accéléré doit être décodable", frame)
                            frame!!.recycle()
                        }
                    } finally {
                        scenario.onActivity {
                            editor.close()
                            scope.cancel()
                        }
                    }
                }
            } finally {
                cleanup(s)
            }
        }
    }

    @Test fun canvasOnlyVideoIsEncodedPausedResumedAndFinalizedOnExit() = video(false)

    @Test fun studioVideoContainsBothOpenGlCanvasAndInterface() = video(true)

    @Test fun previewAtTwoTimesIsARealShorterDecodableMp4() = video(false, 2)

    @Test fun previewAtTwoHundredTimesRetainsAtLeastOneDecodableFrame() = video(false, 200)

    @Test
    fun projectsScreenLaunchesAndProducesAnActualScreenshot() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertFalse(it.isFinishing) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            SystemClock.sleep(600)
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            assertNotNull(bitmap)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), "qa-projets.png").outputStream().use {
                bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap!!.recycle()
        }
    }
}
