package studio.nuvora.veila.studio

import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import studio.nuvora.veila.core.*
import studio.nuvora.veila.data.*

data class StudioStatus(
    val project: Project,
    val step: Int,
    val progress: Double,
    val overall: Double,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val time: Long,
    val simulatedTime: Long,
    val ready: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val video: String = "",
    val recording: RecordingState = RecordingState.IDLE,
    val tilt: Float = 0f,
    val drawing: Boolean = false,
    val inputDiagnostic: String = "Approchez ou posez le stylet sur la toile.",
    val rotation: Float = 0f,
    val progressVisible: Boolean = false,
    val unrevealedOverlayVisible: Boolean = false,
    val temporaryPreviewVisible: Boolean = false,
    val layerProgress: Map<String, Double> = emptyMap(),
)

/** The live reveal percentage is intentionally hidden while a recording is active. */
fun shouldShowRevealProgress(recording: RecordingState, enabled: Boolean = false): Boolean =
    enabled && recording == RecordingState.IDLE

private enum class ClockPauseSource {
    LIFECYCLE,
    UNREVEALED_OVERLAY,
    PROGRESS_OVERLAY,
    TEMPORARY_PREVIEW,
}

/** One GL-thread writer; immutable checkpoints cross to a bounded IO queue. */
class StudioSession(val repository: ProjectRepository, loaded: LoadedProject) {
    var project = loaded.project
    private val snapshots = loaded.layerEngines.toMutableMap()
    private val progresses =
        snapshots.mapValuesTo(mutableMapOf()) { (_, state) ->
            state.mask.sumOf { it.toLong() }.toDouble() /
                (state.width.toLong() * state.height * 1000)
        }
    private fun activeSnapshot(): EngineSnapshot =
        snapshots[project.stages[project.currentStep].id]
            ?: emptyLayerSnapshot(project.width, project.height).also {
                snapshots[project.stages[project.currentStep].id] = it
            }
    var engine = RevealEngine(project.width, project.height, 2, activeSnapshot(), autoAdvance = false)
        private set
    val camera = project.camera.copy()
    val clock = WorkClock(project.workMillis, SystemClock::elapsedRealtime, project.workSpeed)
    val router =
        GestureRouter(
            mouseEnabled = project.mouseEnabled,
            fingerDrawingEnabled = project.fingerDrawingEnabled,
        )
    var ready = false
    var videoMessage = if (loaded.recovered) "Sauvegarde précédente récupérée." else ""
    var videoState = RecordingState.IDLE
    var tilt = 0f
    var inputDiagnostic = "Approchez ou posez le stylet sur la toile."
    var progressVisible = false
    var unrevealedOverlayVisible = false
    /** Display-only full reveal of the active layer. It never changes a mask or its history. */
    var temporaryPreviewVisible = false
    var recorderProvider: () -> StudioRecorder? = { null }
    /** UI/GL frame barrier used before automatically resuming a consultation-paused recording. */
    var afterConsultationHidden: ((() -> Unit) -> Unit) = { action -> action() }
    private var pausedForConsultation = false
    private var consultationResumePending = false
    private var recorderPausedForPreview = false
    private val clockPauseSources = mutableSetOf<ClockPauseSource>()
    @Volatile var failure: String? = null
    @Volatile private var saveFailure: String? = null
    @Volatile private var savedSerial = 0L
    private var serial = 0L
    private var savedRevision = 0L
    private var checkpoint = loaded.engine
    private var committedProgress = loaded.project.progress
    private var lastTimedSave = 0L
    private val writer = Executors.newSingleThreadExecutor()

    private data class Save(
        val serial: Long,
        val meta: Project,
        val states: Map<String, EngineSnapshot>,
    )

    private val pending = AtomicReference<Save?>()
    private val mutable =
        MutableStateFlow(
            StudioStatus(
                project,
                project.currentStep,
                engine.progress,
                progresses.values.average().takeIf { !it.isNaN() } ?: 0.0,
                engine.canUndo,
                engine.canRedo,
                clock.millis(),
                clock.epochMillis(project.simulatedEpochMillis),
            )
        )
    val status = mutable.asStateFlow()

    fun publish() {
        mutable.value =
            StudioStatus(
                project,
                project.currentStep,
                engine.progress,
                progresses.values.average().takeIf { !it.isNaN() } ?: 0.0,
                engine.canUndo,
                engine.canRedo,
                clock.millis(),
                clock.epochMillis(project.simulatedEpochMillis),
                ready,
                savedSerial < serial,
                failure ?: saveFailure,
                videoMessage,
                videoState,
                tilt,
                engine.inStroke,
                inputDiagnostic,
                camera.rotation,
                progressVisible,
                unrevealedOverlayVisible,
                temporaryPreviewVisible,
                progresses.toMap(),
            )
    }

    private val writable
        get() = ready && failure == null && saveFailure == null

    fun input(frame: InputFrame, vw: Int, vh: Int) {
        val old = engine.revision
        for (g in router.handle(frame)) when (g) {
            is Gesture.PenDown ->
                if (writable) {
                    tilt = g.p.tilt
                    inputDiagnostic =
                        if (g.p.tool == Tool.FINGER) "Doigt actif · dévoilement en cours."
                        else "Stylet actif · dévoilement en cours."
                    engine.begin(
                        camera.imagePoint(g.p.x, g.p.y, vw, vh, project.width, project.height),
                        g.p.pressure,
                        project.brush,
                        project.eraserEnabled,
                        cancellableCompletion = g.p.tool == Tool.FINGER,
                    )
                }
            is Gesture.PenMove ->
                if (writable) {
                    tilt = g.p.tilt
                    if (g.p.tool == Tool.FINGER) inputDiagnostic = "Doigt actif · dévoilement en cours."
                    engine.move(
                        camera.imagePoint(g.p.x, g.p.y, vw, vh, project.width, project.height),
                        g.p.pressure,
                    )
                }
            Gesture.PenUp -> engine.end()
            Gesture.PenCancel -> engine.cancel()
            is Gesture.Navigate ->
                camera.transform(
                    g.from,
                    g.to,
                    g.ratio,
                    vw,
                    vh,
                    project.width,
                    project.height,
                    g.angle,
                )
            Gesture.Undo -> if (failure == null && saveFailure == null) engine.undo()
            Gesture.Redo -> if (failure == null && saveFailure == null) engine.redo()
            Gesture.ToggleUnrevealedOverlay -> toggleUnrevealedOverlay()
            Gesture.ToggleProgress -> toggleProgress()
        }
        if (engine.revision != old) save()
        publish()
    }

    fun tick() {
        if (writer.isShutdown) return
        if (clock.running && SystemClock.elapsedRealtime() - lastTimedSave >= 5000) save()
        publish()
    }

    fun save() {
        if (writer.isShutdown) return
        if (!engine.inStroke && engine.revision != savedRevision) {
            checkpoint = engine.snapshot()
            committedProgress = engine.progress
            savedRevision = engine.revision
        }
        lastTimedSave = SystemClock.elapsedRealtime()
        snapshots[project.stages[project.currentStep].id] = checkpoint
        progresses[project.stages[project.currentStep].id] = committedProgress
        project =
            project.copy(
                progress = committedProgress,
                overallProgress = progresses.values.average().takeIf { !it.isNaN() } ?: 0.0,
                workMillis = clock.millis(),
                workSpeed = clock.speed,
                camera = camera.copy(),
                modified = System.currentTimeMillis(),
            )
        val immutableStates = snapshots.toMap()
        val previous = pending.getAndSet(Save(++serial, project, immutableStates))
        if (previous == null)
            writer.execute {
                while (true) {
                    val job = pending.getAndSet(null) ?: break
                    try {
                        repository.save(job.meta, job.states)
                        savedSerial = job.serial
                        saveFailure = null
                    } catch (e: Exception) {
                        saveFailure =
                            "Sauvegarde impossible : ${e.message}. Réessayez avant de continuer."
                    } catch (_: OutOfMemoryError) {
                        saveFailure = "Mémoire insuffisante pendant la sauvegarde. Réessayez."
                    }
                }
            }
        publish()
    }

    fun flush(callback: (String?) -> Unit) {
        engine.end()
        setClockPaused(ClockPauseSource.LIFECYCLE, true)
        save()
        writer.execute { callback(saveFailure) }
    }

    fun undo() {
        if (failure == null && saveFailure == null) {
            engine.end()
            if (engine.undo()) save()
        }
        publish()
    }

    fun redo() {
        if (failure == null && saveFailure == null && engine.redo()) save()
        publish()
    }

    fun selectLayer(index: Int) {
        if (index !in project.stages.indices || index == project.currentStep) return
        engine.end()
        snapshots[project.stages[project.currentStep].id] = engine.snapshot()
        project = project.copy(currentStep = index)
        val state = snapshots[project.stages[index].id] ?: emptyLayerSnapshot(project.width, project.height)
        snapshots[project.stages[index].id] = state
        engine = RevealEngine(project.width, project.height, 2, state, autoAdvance = false)
        checkpoint = state
        committedProgress = engine.progress
        savedRevision = engine.revision
        project = project.copy(
            progress = committedProgress,
            overallProgress = progresses.values.average().takeIf { !it.isNaN() } ?: 0.0,
        )
        save(); publish()
    }

    fun renameLayer(index: Int, name: String) {
        val clean = name.trim().take(100)
        if (index !in project.stages.indices || clean.isEmpty()) return
        project = project.copy(stages = project.stages.mapIndexed { i, stage ->
            if (i == index) stage.copy(name = clean) else stage
        })
        save()
        publish()
    }

    /** Stable states consumed only when the renderer rebuilds its bounded layer composite. */
    fun layerSnapshotsForRendering(): Map<String, EngineSnapshot> {
        if (!engine.inStroke) snapshots[project.stages[project.currentStep].id] = engine.snapshot()
        return snapshots.toMap()
    }

    fun resetActiveLayer() {
        if (engine.reset()) save()
        publish()
    }

    fun setWorkSpeed(speed: Float) {
        clock.setSpeed(speed)
        project = project.copy(workSpeed = clock.speed)
        save()
        publish()
    }

    /** Resets only the simulated duration; the configured speed and pause sources are preserved. */
    fun resetWorkTime() {
        clock.reset()
        project = project.copy(workMillis = 0L)
        save()
        publish()
    }

    /** Aligns the simulated civil clock to the tablet now while retaining elapsed simulated work. */
    fun resetSimulatedDate() {
        project = project.copy(simulatedEpochMillis = System.currentTimeMillis() - clock.millis())
        save()
        publish()
    }

    fun setEraserEnabled(enabled: Boolean) {
        project = project.copy(eraserEnabled = enabled)
        save()
        publish()
    }

    fun beginTemporaryPreview() {
        if (temporaryPreviewVisible) return
        router.claimTwoFingerHold()
        recorderPausedForPreview = false
        if (videoState == RecordingState.RECORDING) {
            try {
                recorderProvider()?.pause()
                videoState = RecordingState.PAUSED
                recorderPausedForPreview = true
            } catch (e: Exception) {
                videoMessage = "Prévisualisation sans pause vidéo : ${e.message}."
            }
        }
        temporaryPreviewVisible = true
        setClockPaused(ClockPauseSource.TEMPORARY_PREVIEW, true)
        publish()
    }

    fun endTemporaryPreview() {
        if (!temporaryPreviewVisible) return
        temporaryPreviewVisible = false
        setClockPaused(ClockPauseSource.TEMPORARY_PREVIEW, false)
        resumePreviewRecordingIfPossible()
        publish()
    }

    private fun resumePreviewRecordingIfPossible() {
        if (
            !recorderPausedForPreview || temporaryPreviewVisible || progressVisible ||
                unrevealedOverlayVisible || videoState != RecordingState.PAUSED ||
                consultationResumePending
        ) return
        consultationResumePending = true
        afterConsultationHidden {
            consultationResumePending = false
            if (
                recorderPausedForPreview && !temporaryPreviewVisible && !progressVisible &&
                    !unrevealedOverlayVisible && videoState == RecordingState.PAUSED
            ) {
                try {
                    recorderProvider()?.resume()
                    videoState = RecordingState.RECORDING
                    recorderPausedForPreview = false
                } catch (e: Exception) {
                    videoMessage = "La vidéo reste en pause : ${e.message}."
                }
                publish()
            }
        }
    }

    fun setBrushSize(size: Float) {
        val valid = size.takeIf { it.isFinite() }?.coerceIn(1f, 1600f) ?: project.brush.size
        project = project.copy(brush = project.brush.copy(size = valid))
        save()
        publish()
    }

    private fun toggleUnrevealedOverlay() {
        val show = !unrevealedOverlayVisible
        toggleConsultation { unrevealedOverlayVisible = show }
        if (unrevealedOverlayVisible == show) {
            setClockPaused(ClockPauseSource.UNREVEALED_OVERLAY, show)
        }
        save()
    }

    private fun toggleProgress() {
        val show = !progressVisible
        toggleConsultation { progressVisible = show }
        if (progressVisible == show) {
            setClockPaused(ClockPauseSource.PROGRESS_OVERLAY, show)
        }
        save()
    }

    /** A source can only resume time after every independent pause has been cleared. */
    private fun setClockPaused(source: ClockPauseSource, paused: Boolean) {
        if (paused) clockPauseSources += source else clockPauseSources -= source
        if (clockPauseSources.isEmpty()) clock.resume() else clock.pause()
    }

    private fun toggleConsultation(change: () -> Unit) {
        if (videoState == RecordingState.IDLE) pausedForConsultation = false
        if (videoState == RecordingState.RECORDING) {
            try {
                recorderProvider()?.pause()
                videoState = RecordingState.PAUSED
                pausedForConsultation = true
            } catch (e: Exception) {
                videoMessage = "Consultation indisponible : pause vidéo impossible (${e.message})."
                publish()
                return
            }
        }
        change()
        resumePreviewRecordingIfPossible()
        // The renderer receives this state only after MediaRecorder.pause() above has returned.
        if (
            pausedForConsultation &&
                !progressVisible &&
                !unrevealedOverlayVisible &&
                videoState == RecordingState.PAUSED &&
                !consultationResumePending
        ) {
            consultationResumePending = true
            afterConsultationHidden {
                consultationResumePending = false
                if (
                    pausedForConsultation &&
                        !progressVisible &&
                        !unrevealedOverlayVisible &&
                        videoState == RecordingState.PAUSED
                ) {
                    try {
                        recorderProvider()?.resume()
                        videoState = RecordingState.RECORDING
                        pausedForConsultation = false
                    } catch (e: Exception) {
                        videoMessage = "La vidéo reste en pause : ${e.message}."
                    }
                    publish()
                }
            }
        }
    }

    fun toggleRecordingByUser() {
        when (videoState) {
            RecordingState.RECORDING -> {
                pausedForConsultation = false
                recorderProvider()?.pause()
            }
            RecordingState.PAUSED -> {
                if (progressVisible || unrevealedOverlayVisible || temporaryPreviewVisible) {
                    videoMessage = "Masquez les outils de consultation avant de reprendre."
                    publish()
                } else {
                    pausedForConsultation = false
                    recorderProvider()?.resume()
                }
            }
            else -> Unit
        }
    }

    fun pause() {
        engine.end()
        endTemporaryPreview()
        router.reset()
        setClockPaused(ClockPauseSource.LIFECYCLE, true)
        save()
    }

    fun resume() {
        setClockPaused(ClockPauseSource.LIFECYCLE, false)
        publish()
    }

    fun shutdown() {
        writer.shutdown()
    }
}
