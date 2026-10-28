package com.workeffortwaste

import org.junit.Assert.assertEquals
import org.junit.Test

/** Covers phase-weighted progress, completion limits, and update throttling. */
class ProgressReporterTest {
    @Test fun backupReadsUseNinetyNinePercentAndChecksCannotPublishCompletion() {
        val updates = mutableListOf<Float>()
        val phases = listOf(
            ProgressPhase("First read", 1048576, StatusMessage(), weight = 49.5),
            ProgressPhase("Second read", 1048576, StatusMessage(), weight = 49.5),
            ProgressPhase("Saved file", 1048576, StatusMessage(), weight = 1.0),
        )
        val reporter = ProgressReporter({ 0L }, phases, maximumFraction = 0.999f) { _, value ->
            updates += value
        }
        phases.forEach { reporter.report(it.name, it.totalBytes, it.totalBytes) }
        assertEquals(listOf(0.495f, 0.99f, 0.999f), updates)
    }

    @Test fun percentageMatchesAnimatedRingAndNeverRoundsUpToCompletion() {
        assertEquals(75, transferProgressPercent(0.75f, 0.999f))
        assertEquals(99, transferProgressPercent(0.999f, 0.999f))
        assertEquals(99, transferProgressPercent(1f, 0.999f))
        assertEquals(100, transferProgressPercent(1f, 1f))
    }

    @Test fun publishesFirstUpdateAndThrottlesUntilOneHundredMilliseconds() {
        var now = 0L
        val updates = mutableListOf<Pair<StatusMessage, Float>>()
        val backupRead = statusMessage("Backing up FM-1", "Reading flash")
        val reporter = ProgressReporter({ now }, listOf(
            ProgressPhase("Reading FM-1 flash", 1000, backupRead),
        )) { status, fraction -> updates += status to fraction }

        reporter.report("Reading FM-1 flash", 0, 1000)
        now = 99
        reporter.report("Reading FM-1 flash", 250, 1000)
        now = 100
        reporter.report("Reading FM-1 flash", 500, 1000)
        now = 199
        reporter.report("Reading FM-1 flash", 750, 1000)
        now = 200
        reporter.report("Reading FM-1 flash", 800, 1000)

        assertEquals(listOf(
            backupRead to 0f,
            backupRead to 0.5f,
            backupRead to 0.8f,
        ), updates)
    }

    @Test fun publishesPhaseChangesAndEveryPassFinalWithoutWaiting() {
        var now = 0L
        val updates = mutableListOf<Pair<StatusMessage, Float>>()
        val phases = listOf(
            ProgressPhase("First", 512, statusMessage("First", "Working with the FM-1")),
            ProgressPhase("Second", 1024, statusMessage("Second", "Working with the FM-1")),
            ProgressPhase("Third", 256, statusMessage("Third", "Working with the FM-1")),
        )
        val reporter = ProgressReporter({ now }, phases) { status, fraction -> updates += status to fraction }

        reporter.report("First", 256, 512)
        now = 100
        reporter.report("First", 512, 512)
        now = 200
        reporter.report("Second", 256, 1024)
        now = 300
        reporter.report("Second", 512, 1024)
        now = 400
        reporter.report("Second", 1024, 1024)
        now = 500
        reporter.report("Third", 256, 256)

        assertEquals(listOf(
            phases[0].status to (256f / 1792f),
            phases[0].status to (512f / 1792f),
            phases[1].status to (768f / 1792f),
            phases[1].status to (1024f / 1792f),
            phases[1].status to (1536f / 1792f),
            phases[2].status to 1f,
        ), updates)
    }
}
