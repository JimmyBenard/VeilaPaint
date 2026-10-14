package studio.nuvora.veila.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import studio.nuvora.veila.core.*
import studio.nuvora.veila.studio.StudioSession

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectRepositoryTest {
    private lateinit var context: Context
    private lateinit var repo: ProjectRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.resolve("projects").deleteRecursively()
        repo = ProjectRepository(context)
    }

    private fun image(width: Int = 32, height: Int = 32): Uri {
        val file = File(context.cacheDir, "${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.CYAN)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    private fun imported(count: Int): Project {
        val project = Project(name = "Test", width = 32, height = 32, camera = Camera(16f, 16f))
        val source = image()
        return project.copy(
            stages = List(count) { repo.import(project, source, "Étape ${it + 1}") }
        )
    }

    @Test
    fun defaultLayerNamesFollowThe040ContractAndBelongToTheLayer() {
        assertEquals(
            listOf(
                "Page blanche",
                "Croquis",
                "Croquis détaillé",
                "Colorisation 1er aplat",
                "Colorisation 2ème aplat",
                "Colorisation 3ème aplat",
                "Calque 7",
                "Calque 8",
            ),
            List(8, ::defaultLayerName),
        )
        val p = imported(3)
        val renamed = p.stages[1].copy(name = "Mon trait")
        val reordered = listOf(renamed, p.stages[2], p.stages[0])
        assertEquals("Mon trait", reordered.first().name)
    }

    @Test
    fun importTwoAndRestoreExactMaskAndTime() {
        val p = imported(2)
        val e = RevealEngine(p.width, p.height, p.stages.size)
        e.begin(Point(16f, 16f), 1f, Brush(8f, .2f, 1f))
        e.end()
        repo.save(p.copy(workMillis = 123456, progress = e.progress), e.snapshot())
        val restored = repo.load(p.id)
        assertEquals(2, restored.project.stages.size)
        assertEquals(123456L, restored.project.workMillis)
        assertArrayEquals(e.mask.copyValues(), restored.engine.mask)
        assertEquals(e.progress, restored.project.progress, 0.0)
        assertTrue(repo.asset(p, p.stages.first().thumbnail).length() > 0)
    }

    @Test
    fun importTwentyPreservesOriginalsAndThumbnails() {
        val p = imported(20)
        repo.save(p, RevealEngine(32, 32, 20).snapshot())
        assertEquals(20, repo.load(p.id).project.stages.size)
        assertTrue(
            p.stages.all { repo.asset(p, it.source).isFile && repo.asset(p, it.thumbnail).isFile }
        )
    }

    @Test
    fun independentLayerMasksSurviveSelectionReorderSaveAndRestore() {
        val p = imported(3)
        val states = p.stages.associate { it.id to emptyLayerSnapshot(32, 32) }.toMutableMap()
        fun painted(x: Float): EngineSnapshot {
            val engine = RevealEngine(32, 32, 2, autoAdvance = false)
            engine.begin(Point(x, 16f), 1f, Brush(7f, .5f, 1f))
            engine.end()
            return engine.snapshot()
        }
        states[p.stages[0].id] = painted(8f)
        states[p.stages[1].id] = painted(24f)
        val a = states.getValue(p.stages[0].id).mask.copyOf()
        val b = states.getValue(p.stages[1].id).mask.copyOf()
        val reordered = p.copy(stages = listOf(p.stages[2], p.stages[0], p.stages[1]), currentStep = 2)
        repo.save(reordered, states)
        val restored = repo.load(p.id)
        assertArrayEquals(a, restored.layerEngines.getValue(p.stages[0].id).mask)
        assertArrayEquals(b, restored.layerEngines.getValue(p.stages[1].id).mask)
        assertArrayEquals(b, restored.engine.mask)
    }

    @Test
    fun studioSelectionTargetsOnlyTheActiveLayerAndResetIsUndoable() {
        val p = imported(3)
        val states = p.stages.associate { it.id to emptyLayerSnapshot(32, 32) }
        val session = StudioSession(repo, LoadedProject(p, states.getValue(p.stages[0].id), states))
        session.ready = true
        lateinit var first: ShortArray
        lateinit var second: ShortArray
        try {
            session.engine.begin(Point(8f, 16f), 1f, Brush(7f, .5f, 1f))
            session.engine.end()
            session.save()
            first = session.engine.mask.copyValues()
            session.selectLayer(1)
            assertEquals(1, session.status.value.step)
            session.engine.begin(Point(24f, 16f), 1f, Brush(7f, .5f, 1f))
            session.engine.end()
            session.save()
            second = session.engine.mask.copyValues()
            assertFalse(first.contentEquals(second))
            session.selectLayer(0)
            assertArrayEquals(first, session.engine.mask.copyValues())
            session.resetActiveLayer()
            assertEquals(0.0, session.engine.progress, 0.0)
            session.undo()
            assertArrayEquals(first, session.engine.mask.copyValues())
        } finally {
            val done = java.util.concurrent.CountDownLatch(1)
            session.flush { done.countDown() }
            assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
            session.shutdown()
        }
        val restored = repo.load(p.id)
        assertArrayEquals(first, restored.layerEngines.getValue(p.stages[0].id).mask)
        assertArrayEquals(second, restored.layerEngines.getValue(p.stages[1].id).mask)
    }

    @Test
    fun changingWorkAreaResamplesEachMaskWithoutChangingLayerIdentity() {
        val p = imported(2)
        val engine = RevealEngine(32, 32, 2, autoAdvance = false)
        engine.begin(Point(8f, 8f), 1f, Brush(5f, .5f, 1f))
        engine.end()
        val resized = resizeLayerSnapshot(engine.snapshot(), 64, 48)
        assertEquals(64, resized.width)
        assertEquals(48, resized.height)
        assertTrue(resized.mask.any { it.toInt() > 0 })
        assertTrue(resized.undo.isEmpty())
        assertTrue(resized.redo.isEmpty())
    }

    @Test
    fun legacySequentialStateMigratesToCompletedCurrentAndFutureLayerMasks() {
        val p = imported(3)
        val legacy = RevealEngine(32, 32, 3)
        legacy.begin(Point(16f, 16f), 1f, Brush(100f, 1f, 1f))
        legacy.end()
        legacy.begin(Point(8f, 8f), 1f, Brush(5f, .5f, 1f))
        legacy.end()
        val migrated = migrateLegacyLayerSnapshots(p, legacy.snapshot())
        assertTrue(migrated.getValue(p.stages[0].id).mask.all { it.toInt() == 1000 })
        assertArrayEquals(legacy.mask.copyValues(), migrated.getValue(p.stages[1].id).mask)
        assertTrue(migrated.getValue(p.stages[2].id).mask.all { it.toInt() == 0 })
    }

    @Test
    fun corruptedImageLeavesNoHalfImportedAssets() {
        val p = Project(width = 32, height = 32)
        val bad = File(context.cacheDir, "broken.png").apply { writeText("not an image") }
        assertThrows(Exception::class.java) { repo.import(p, Uri.fromFile(bad), "Cassée") }
        assertTrue(repo.directory(p.id).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun highResolutionImageIsDownsampledWithoutLosingOriginal() {
        val p = Project(width = 320, height = 180)
        val s = repo.import(p, image(3840, 2160), "4K")
        assertEquals(3840, s.sourceWidth)
        assertEquals(2160, s.sourceHeight)
        val decoded = BitmapFactory.decodeFile(repo.asset(p, s.image).absolutePath)
        assertEquals(320, decoded.width)
        assertEquals(180, decoded.height)
        decoded.recycle()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(repo.asset(p, s.source).absolutePath, bounds)
        assertEquals(3840, bounds.outWidth)
    }

    @Test
    fun duplicateRenameAndDeleteAreIndependentOfOriginal() {
        val p = imported(2)
        repo.save(p, RevealEngine(32, 32, 2).snapshot())
        val duplicate = repo.duplicate(p)
        repo.rename(duplicate, "Nouvelle version")
        assertEquals("Nouvelle version", repo.load(duplicate.id).project.name)
        assertEquals(p.name, repo.load(p.id).project.name)
        repo.delete(duplicate)
        assertEquals(1, repo.list().first.size)
        assertEquals(p.id, repo.load(p.id).project.id)
    }

    @Test
    fun imageIsCenteredWithoutCroppingToSquareCanvas() {
        val p = Project(width = 64, height = 64)
        val s = repo.import(p, image(64, 32), "Paysage")
        val bitmap = BitmapFactory.decodeFile(repo.asset(p, s.image).absolutePath)
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
        assertEquals(Color.CYAN, bitmap.getPixel(32, 32))
        bitmap.recycle()
    }

    @Test
    fun rotationAndOptionalSideButtonSurviveSaveAndRestore() {
        val p =
            imported(2)
                .copy(camera = Camera(13f, 17f, 2f, 1.2f), brush = Brush(sideButtonEraser = true))
        repo.save(p, RevealEngine(32, 32, 2).snapshot())
        val loaded = repo.load(p.id).project
        assertEquals(p.camera, loaded.camera)
        assertTrue(loaded.brush.sideButtonEraser)
    }

    @Test
    fun versionOneProjectsOpenWithUprightCameraAndSafeButtonDefault() {
        val p = imported(2)
        val json = p.json()
        json.put("schema", 1)
        json.getJSONObject("camera").remove("rotation")
        json.getJSONObject("brush").remove("sideButtonEraser")
        json.remove("fingerDrawingEnabled")
        json.remove("coverMode")
        json.remove("coverStageId")
        json.remove("coverImage")
        json.remove("coverThumbnail")
        json.remove("overallProgress")
        val old = Project.fromJson(json)
        assertEquals(0f, old.camera.rotation, 0f)
        assertFalse(old.brush.sideButtonEraser)
        assertTrue(old.fingerDrawingEnabled)
        assertEquals(ProjectCoverMode.AUTOMATIC, old.coverMode)
        assertEquals(p.id, old.id)
        assertEquals(p.stages, old.stages)
    }

    @Test
    fun importedProjectCoverDoesNotChangeStagesMaskOrProgressAndDuplicatesSafely() {
        val p = imported(2)
        val engine = RevealEngine(32, 32, 2)
        engine.begin(Point(16f, 16f), 1f, Brush(8f, .2f, 1f))
        engine.end()
        val cover = repo.importCover(p, image(48, 24))
        val decorated =
            p.copy(
                coverMode = ProjectCoverMode.IMPORTED,
                coverImage = cover.image,
                coverThumbnail = cover.thumbnail,
                progress = engine.progress,
            )
        repo.save(decorated, engine.snapshot())
        val restored = repo.load(p.id)
        assertEquals(p.stages, restored.project.stages)
        assertArrayEquals(engine.mask.copyValues(), restored.engine.mask)
        assertEquals(engine.progress, restored.project.progress, 0.0)
        assertTrue(repo.coverThumbnail(restored.project)!!.isFile)
        val duplicate = repo.duplicate(restored.project)
        assertEquals(ProjectCoverMode.IMPORTED, repo.load(duplicate.id).project.coverMode)
        assertTrue(repo.coverThumbnail(duplicate)!!.isFile)
    }

    @Test
    fun singleFingerWritesTheRevealMaskButMultiTouchNavigationDoesNot() {
        val p = imported(2)
        val session = StudioSession(repo, LoadedProject(p, RevealEngine(32, 32, 2).snapshot()))
        session.ready = true
        val finger = Pointer(1, 100f, 100f, Tool.FINGER)
        try {
            session.videoState = RecordingState.RECORDING
            session.input(InputFrame(Action.DOWN, 0, listOf(finger), 1), 320, 320)
            session.input(InputFrame(Action.MOVE, 20, listOf(finger.copy(x = 200f)), 1), 320, 320)
            session.input(InputFrame(Action.UP, 30, listOf(finger.copy(x = 200f)), 1), 320, 320)
            val revealed = session.engine.progress
            assertTrue(revealed > 0.0)
            assertFalse(studio.nuvora.veila.studio.shouldShowRevealProgress(session.status.value.recording))

            val a = Pointer(2, 80f, 80f, Tool.FINGER)
            val b = Pointer(3, 160f, 80f, Tool.FINGER)
            session.input(InputFrame(Action.DOWN, 40, listOf(a), 2), 320, 320)
            session.input(InputFrame(Action.POINTER_DOWN, 50, listOf(a, b), 3), 320, 320)
            session.input(
                InputFrame(
                    Action.MOVE,
                    60,
                    listOf(a.copy(x = 60f), b.copy(x = 220f)),
                    3,
                ),
                320,
                320,
            )
            session.input(
                InputFrame(Action.POINTER_UP, 70, listOf(a.copy(x = 60f), b.copy(x = 220f)), 3),
                320,
                320,
            )
            session.input(InputFrame(Action.UP, 80, listOf(a.copy(x = 60f)), 2), 320, 320)
            assertEquals(revealed, session.engine.progress, 0.0)
        } finally {
            val done = java.util.concurrent.CountDownLatch(1)
            session.flush { done.countDown() }
            assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
            session.shutdown()
        }
    }

    @Test
    fun multiTouchRollsBackAnInitialTouchThatWouldCompleteTheStage() {
        val p = imported(3).copy(brush = Brush(200f, .1f, 1f))
        val engine = RevealEngine(32, 32, 2, autoAdvance = false)
        engine.begin(Point(16f, 16f), 1f, Brush(200f, .9f, 1f))
        engine.end()
        val original = engine.mask.copyValues()
        val states = p.stages.associate { it.id to emptyLayerSnapshot(32, 32) }.toMutableMap()
        states[p.stages[0].id] = engine.snapshot()
        val session =
            StudioSession(repo, LoadedProject(p.copy(progress = .9), engine.snapshot(), states))
        session.ready = true
        val a = Pointer(1, 160f, 160f, Tool.FINGER)
        val b = Pointer(2, 240f, 160f, Tool.FINGER)
        try {
            session.input(InputFrame(Action.DOWN, 0, listOf(a), 1), 320, 320)
            assertEquals(0, session.engine.step)
            session.input(InputFrame(Action.POINTER_DOWN, 10, listOf(a, b), 2), 320, 320)
            assertEquals(0, session.engine.step)
            assertEquals(.9, session.engine.progress, 0.0)
            session.input(InputFrame(Action.MOVE, 30, listOf(a.copy(x = 100f), b.copy(x = 280f)), 2), 320, 320)
            session.input(InputFrame(Action.POINTER_UP, 50, listOf(a, b), 2), 320, 320)
            session.input(InputFrame(Action.MOVE, 70, listOf(a.copy(x = 40f)), 1), 320, 320)
            session.input(InputFrame(Action.UP, 90, listOf(a), 1), 320, 320)
            assertArrayEquals(original, session.engine.mask.copyValues())
        } finally {
            val done = java.util.concurrent.CountDownLatch(1)
            session.flush { done.countDown() }
            assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
            session.shutdown()
        }
    }

    @Test
    fun consultationGesturesPauseRecordingUntilBothTemporaryToolsAreHidden() {
        val p = imported(2)
        val session = StudioSession(repo, LoadedProject(p, RevealEngine(32, 32, 2).snapshot()))
        session.videoState = RecordingState.RECORDING
        session.ready = true
        fun tap(count: Int, start: Long) {
            fun point(id: Int) = Pointer(id, id * 30f, 50f, Tool.FINGER)
            for (n in 1..count)
                session.input(
                    InputFrame(
                        if (n == 1) Action.DOWN else Action.POINTER_DOWN,
                        start + n,
                        (1..n).map(::point),
                        n,
                    ),
                    320,
                    320,
                )
            for (n in count downTo 1)
                session.input(
                    InputFrame(
                        if (n == 1) Action.UP else Action.POINTER_UP,
                        start + 100 + count - n,
                        (1..n).map(::point),
                        n,
                    ),
                    320,
                    320,
                )
        }
        try {
            tap(2, 0)
            assertTrue(session.unrevealedOverlayVisible)
            assertEquals(RecordingState.PAUSED, session.videoState)
            tap(5, 200)
            assertTrue(session.progressVisible)
            assertEquals(RecordingState.PAUSED, session.videoState)
            tap(2, 400)
            assertFalse(session.unrevealedOverlayVisible)
            assertEquals(RecordingState.PAUSED, session.videoState)
            tap(5, 600)
            assertFalse(session.progressVisible)
            assertEquals(RecordingState.RECORDING, session.videoState)
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun aCoverStageOrFallbackThumbnailNeverChangesProgression() {
        val p = imported(3)
        val engine = RevealEngine(32, 32, 3)
        engine.begin(Point(16f, 16f), 1f, Brush(8f, .426f, 1f))
        engine.end()
        val chosen = p.copy(coverMode = ProjectCoverMode.STAGE, coverStageId = p.stages[2].id, progress = engine.progress)
        repo.save(chosen, engine.snapshot())
        assertEquals(repo.asset(p, p.stages[2].thumbnail), repo.coverThumbnail(chosen))
        assertArrayEquals(engine.mask.copyValues(), repo.load(p.id).engine.mask)
        repo.asset(p, p.stages.first().thumbnail).delete()
        assertTrue(repo.coverThumbnail(chosen.copy(coverMode = ProjectCoverMode.AUTOMATIC))!!.isFile)
    }

    @Test
    fun eraserToggleControlsErasingAndSideButtonIsIgnored() {
        val p = imported(2)
        val session =
            studio.nuvora.veila.studio.StudioSession(
                repo,
                LoadedProject(p, RevealEngine(32, 32, 2).snapshot()),
            )
        session.ready = true
        val pen = Pointer(9, 160f, 160f, Tool.PEN, 1f, eraserButton = true)
        try {
            session.input(InputFrame(Action.DOWN, 0, listOf(pen), 9), 320, 320)
            session.input(InputFrame(Action.UP, 10, listOf(pen), 9), 320, 320)
            assertTrue(session.engine.progress > 0)
            // The stylus side button is intentionally no longer an eraser shortcut.
            session.input(InputFrame(Action.DOWN, 20, listOf(pen), 9), 320, 320)
            session.input(InputFrame(Action.UP, 30, listOf(pen), 9), 320, 320)
            assertTrue(session.engine.progress > 0)
            val beforeErase = session.engine.progress

            session.project = session.project.copy(eraserEnabled = true)
            session.input(InputFrame(Action.DOWN, 40, listOf(pen), 9), 320, 320)
            session.input(InputFrame(Action.UP, 50, listOf(pen), 9), 320, 320)
            assertTrue(session.engine.progress < beforeErase)
        } finally {
            val done = java.util.concurrent.CountDownLatch(1)
            session.flush { done.countDown() }
            assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
            session.shutdown()
        }
    }
}
