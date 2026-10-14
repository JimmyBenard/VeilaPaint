package studio.nuvora.veila.core

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RevealEngineTest {
    private fun tap(
        e: RevealEngine,
        intensity: Float = .1f,
        point: Point = Point(8f, 8f),
        size: Float = 100f,
    ) {
        e.begin(point, 1f, Brush(size, intensity, 1f))
        e.end()
    }

    @Test fun acceptsTwoImages() = ProjectRules.validate(2, 1080, 1350)

    @Test fun acceptsTwentyImagesIn4k() = ProjectRules.validate(20, 3840, 2160)

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTwentyOneImages() = ProjectRules.validate(21, 32, 32)

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSingleImage() = ProjectRules.validate(1, 32, 32)

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsafeCanvasBeforeAllocation() = ProjectRules.validate(2, 8192, 8192)

    @Test
    fun startsWithAnEmptyMask() {
        val e = RevealEngine(16, 16, 3)
        assertEquals(0.0, e.progress, 0.0)
        assertEquals(0L, e.mask.sum)
    }

    @Test
    fun repeatedPassesAddExactlyTenPercent() {
        val e = RevealEngine(16, 16, 3)
        for (i in 1..9) {
            tap(e)
            assertEquals(i / 10.0, e.progress, 0.0)
            assertEquals(i * 100, e.mask.get(5, 5))
        }
    }

    @Test
    fun stationarySamplesDoNotMultiplyOpacity() {
        val e = RevealEngine(16, 16, 3)
        e.begin(Point(8f, 8f), 1f, Brush(100f, .1f, 1f))
        repeat(500) { e.move(Point(8f, 8f), 1f) }
        e.end()
        assertEquals(.1, e.progress, 0.0)
    }

    @Test
    fun exactCompletionAdvancesSynchronouslyWithoutAnimationOrDelay() {
        val e = RevealEngine(16, 16, 3)
        repeat(9) { tap(e) }
        e.begin(Point(8f, 8f), 1f, Brush(100f, .1f, 1f))
        // No end(), animation frame, clock or scheduler runs between these assertions.
        assertEquals(1, e.step)
        assertEquals(0.0, e.progress, 0.0)
        assertFalse(e.inStroke)
        assertEquals(0L, e.mask.sum)
    }

    @Test
    fun roundedPercentageCannotCompleteAnUnfinishedMask() {
        val e = RevealEngine(16, 16, 3)
        tap(e, .999f)
        assertEquals(.999, e.progress, 0.0)
        assertEquals(0, e.step)
    }

    @Test
    fun finalStageIsStableAndIgnoresDrawing() {
        val e = RevealEngine(16, 16, 2)
        tap(e, 1f)
        assertTrue(e.finished)
        assertEquals(1.0, e.progress, 0.0)
        val revision = e.revision
        tap(e, .5f)
        assertEquals(revision, e.revision)
        assertEquals(1.0, e.overallProgress, 0.0)
    }

    @Test
    fun undoAndRedoRestorePixelsAndRecalculateProgress() {
        val e = RevealEngine(32, 32, 3)
        tap(e, .426f)
        val before = e.mask.copyValues()
        tap(e, .2f, size = 8f)
        val after = e.mask.copyValues()
        val progress = e.progress
        assertTrue(progress > .426)
        assertTrue(e.undo())
        assertArrayEquals(before, e.mask.copyValues())
        assertEquals(.426, e.progress, 0.0)
        assertTrue(e.redo())
        assertArrayEquals(after, e.mask.copyValues())
        assertEquals(progress, e.progress, 0.0)
    }

    @Test
    fun undoAndRedoCanCrossStageCompletion() {
        val e = RevealEngine(16, 16, 3)
        repeat(10) { tap(e) }
        assertTrue(e.undo())
        assertEquals(0, e.step)
        assertEquals(.9, e.progress, 0.0)
        assertTrue(e.mask.copyValues().all { it.toInt() == 900 })
        assertTrue(e.redo())
        assertEquals(1, e.step)
        assertEquals(0.0, e.progress, 0.0)
    }

    @Test
    fun severalStageTransitionsAreReversible() {
        val e = RevealEngine(16, 16, 4)
        repeat(6) { tap(e, .5f) }
        assertTrue(e.finished)
        repeat(6) { assertTrue(e.undo()) }
        assertEquals(0, e.step)
        assertEquals(0.0, e.progress, 0.0)
        repeat(6) { assertTrue(e.redo()) }
        assertTrue(e.finished)
    }

    @Test
    fun newStrokeClearsRedoBranch() {
        val e = RevealEngine(16, 16, 3)
        tap(e)
        tap(e)
        e.undo()
        tap(e, .2f)
        assertFalse(e.canRedo)
        assertEquals(.3, e.progress, 0.0)
    }

    @Test
    fun cancellationRestoresStrokeBaseline() {
        val e = RevealEngine(16, 16, 3)
        tap(e)
        e.begin(Point(8f, 8f), 1f, Brush(100f, .5f, 1f))
        e.cancel()
        assertEquals(.1, e.progress, 0.0)
        assertFalse(e.inStroke)
    }

    @Test
    fun aTouchCompletionCanBeCancelledWhenItBecomesNavigation() {
        val e = RevealEngine(16, 16, 3)
        tap(e, .9f)
        tap(e, .1f)
        e.undo()
        val before = e.snapshot()
        e.begin(Point(8f, 8f), 1f, Brush(100f, .1f, 1f), cancellableCompletion = true)
        assertEquals("Promotion is immediate, even before release", 1, e.step)
        assertEquals(0.0, e.progress, 0.0)
        assertTrue(e.inStroke)
        e.cancel()
        assertEquals(0, e.step)
        assertArrayEquals(before.mask, e.mask.copyValues())
        assertEquals(before.undo.size, e.snapshot().undo.size)
        assertEquals(before.redo.size, e.snapshot().redo.size)
        assertTrue(e.redo())
        assertEquals(1, e.step)
    }

    @Test
    fun liftingASingleFingerCommitsAnImmediateCompletion() {
        val e = RevealEngine(16, 16, 2)
        e.begin(Point(8f, 8f), 1f, Brush(100f, 1f, 1f), cancellableCompletion = true)
        assertTrue(e.finished)
        e.end()
        assertFalse(e.inStroke)
        e.cancel()
        assertTrue(e.finished)
        assertTrue(e.undo())
        assertEquals(0.0, e.progress, 0.0)
    }

    @Test
    fun stylusEraserRemovesRevealAndRemainsUndoable() {
        val e = RevealEngine(16, 16, 3)
        tap(e, .5f)
        e.begin(Point(8f, 8f), 1f, Brush(100f, .1f, 1f), erasing = true)
        e.end()
        assertEquals(.4, e.progress, 0.0)
        e.undo()
        assertEquals(.5, e.progress, 0.0)
    }

    @Test
    fun pressureOnlyAffectsIntensityWhenEnabled() {
        val e = RevealEngine(16, 16, 3)
        e.begin(Point(8f, 8f), .5f, Brush(100f, .2f, 1f, pressureSize = false, pressureIntensity = false))
        e.end()
        assertEquals(.2, e.progress, 0.0)
        e.begin(Point(8f, 8f), .5f, Brush(100f, .2f, 1f, pressureSize = false, pressureIntensity = true))
        e.end()
        assertEquals(.3, e.progress, 0.0)
    }

    @Test
    fun pressureCanReduceBrushSize() {
        val small = RevealEngine(64, 64, 3)
        val large = RevealEngine(64, 64, 3)
        val brush = Brush(32f, .5f, 1f, pressureSize = true)
        small.begin(Point(32f, 32f), .5f, brush)
        small.end()
        large.begin(Point(32f, 32f), 1f, brush)
        large.end()
        assertTrue(small.mask.sum < large.mask.sum)
    }

    @Test
    fun sparseStylusEventsProduceAContinuousCapsule() {
        val e = RevealEngine(128, 32, 3)
        e.begin(Point(4f, 16f), 1f, Brush(8f, .5f, 1f))
        e.move(Point(124f, 16f), 1f)
        e.end()
        for (x in 4..123) assertEquals("Gap at $x", 500, e.mask.get(x, 16))
    }

    @Test
    fun local4kStrokeStoresLocalTileDifferences() {
        val e = RevealEngine(3840, 2160, 20)
        tap(e, .1f, Point(2000f, 1000f), 12f)
        assertTrue(e.progress > 0.0)
        assertTrue(e.snapshot().undo.single().bytes < 16 * 1024)
    }

    @Test
    fun cameraZoomAndPanLeaveMaskStrictlyIdentical() {
        val e = RevealEngine(32, 32, 3)
        tap(e, .426f)
        val pixels = e.mask.copyValues()
        val camera = Camera(16f, 16f)
        camera.transform(Point(100f, 100f), Point(180f, 140f), 2f, 800, 600, 32, 32)
        assertEquals(.426, e.progress, 0.0)
        assertArrayEquals(pixels, e.mask.copyValues())
    }

    @Test
    fun pinchKeepsTheImagePointUnderItsMovingFocus() {
        val c = Camera(540f, 675f)
        val before = c.imagePoint(340f, 400f, 1200, 800, 1080, 1350)
        c.transform(Point(340f, 400f), Point(390f, 440f), 2f, 1200, 800, 1080, 1350)
        val after = c.imagePoint(390f, 440f, 1200, 800, 1080, 1350)
        assertEquals(before.x, after.x, .001f)
        assertEquals(before.y, after.y, .001f)
    }

    @Test
    fun rotationKeepsCameraCenterInImageCoordinates() {
        val c = Camera(40f, 50f, 3f)
        assertEquals(Point(40f, 50f), c.imagePoint(600f, 400f, 1200, 800, 100, 100))
        assertEquals(Point(40f, 50f), c.imagePoint(400f, 600f, 800, 1200, 100, 100))
    }

    @Test
    fun codecRestoresMaskStageAndPendingRedo() {
        val e = RevealEngine(16, 16, 3)
        tap(e, 1f)
        tap(e, .426f)
        e.undo()
        val restored = EngineCodec.decode(EngineCodec.encode(e.snapshot()))
        val other = RevealEngine(16, 16, 3, restored)
        assertEquals(1, other.step)
        assertEquals(0.0, other.progress, 0.0)
        assertTrue(other.redo())
        assertEquals(.426, other.progress, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun codecRejectsCorruption() {
        val data = EngineCodec.encode(RevealEngine(16, 16, 2).snapshot())
        data[data.lastIndex] = (data.last().toInt() xor 1).toByte()
        EngineCodec.decode(data)
    }

    @Test(expected = Exception::class)
    fun codecRejectsTruncation() {
        EngineCodec.decode(
            EngineCodec.encode(RevealEngine(16, 16, 2).snapshot()).take(7).toByteArray()
        )
    }

    @Test
    fun atomicSlotsRecoverPreviousValidGenerationAndRepairNextSave() {
        val dir = Files.createTempDirectory("veila-slots").toFile()
        try {
            val slots = AtomicSlots(dir)
            slots.write(byteArrayOf(1))
            slots.write(byteArrayOf(2))
            dir.resolve("state.b").writeBytes(byteArrayOf(9))
            assertArrayEquals(byteArrayOf(1), slots.read()!!.bytes)
            assertTrue(slots.read()!!.recovered)
            slots.write(byteArrayOf(3))
            assertArrayEquals(byteArrayOf(3), slots.read()!!.bytes)
            assertFalse(slots.read()!!.recovered)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun interruptedPendingWriteDoesNotReplaceCommittedState() {
        val dir = Files.createTempDirectory("veila-pending").toFile()
        try {
            val slots = AtomicSlots(dir)
            slots.write(byteArrayOf(42))
            dir.resolve("state.pending").writeText("incomplete")
            assertArrayEquals(byteArrayOf(42), slots.read()!!.bytes)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun workClockUsesMonotonicElapsedTimeAndPausesIdempotently() {
        var now = 1000L
        val clock = WorkClock(7000) { now }
        clock.resume()
        now += 1234
        clock.resume()
        clock.pause()
        clock.pause()
        assertEquals(8234L, clock.millis())
        now += 100_000
        assertEquals(8234L, clock.millis())
        clock.resume()
        now += 500
        assertEquals(8734L, clock.millis())
    }

    @Test
    fun workClockScalesOnlyRunningTimeAndNeverBackfillsConsultationPause() {
        var now = 0L
        val clock = WorkClock(0, { now }, 2f)
        clock.resume()
        now += 500
        assertEquals(1_000L, clock.millis())
        clock.pause()
        now += 10_000
        assertEquals(1_000L, clock.millis())
        clock.setSpeed(.5f)
        clock.resume()
        now += 1_000
        assertEquals(1_500L, clock.millis())
    }

    @Test
    fun workClockResetPreservesSpeedAndCurrentPauseState() {
        var now = 0L
        val clock = WorkClock(2_000, { now }, 2f)
        clock.resume()
        now += 500
        clock.reset()
        assertEquals(0L, clock.millis())
        assertTrue(clock.running)
        now += 500
        assertEquals(1_000L, clock.millis())
        clock.pause()
        clock.reset()
        now += 5_000
        assertEquals(0L, clock.millis())
        assertFalse(clock.running)
    }

    @Test
    fun recordingPauseDoesNotPauseProjectClock() {
        var now = 0L
        val clock = WorkClock { now }
        val video = RecordingLifecycle()
        clock.resume()
        video.start()
        now = 1000
        video.pause()
        now = 2000
        assertEquals(2000L, clock.millis())
        assertEquals(RecordingState.PAUSED, video.state)
        video.resume()
        video.stop()
        video.finalized()
        assertEquals(RecordingState.IDLE, video.state)
        assertTrue(clock.running)
    }

    @Test
    fun pausedRecordingCanBeFinalizedWhenLeaving() {
        val video = RecordingLifecycle()
        video.start()
        video.pause()
        video.stop()
        assertEquals(RecordingState.STOPPING, video.state)
        video.finalized()
        assertEquals(RecordingState.IDLE, video.state)
    }
}
