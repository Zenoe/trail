package com.trail.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trail.app.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingRepositoryTest {
    private lateinit var db: TrailDatabase
    private lateinit var repo: RecordingRepository
    private val wall = 1_700_000_000_000L
    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TrailDatabase::class.java).build()
        repo = RecordingRepository(db)
    }
    @After fun close() { db.close() }
    private fun fix(t: Long, lat: Double = 1.0, accuracy: Float = 5f) = LocationSample(lat, 103.0, wall + t, t, accuracy, null, null)

    @Test fun pauseResumeCreatesNewSegmentWithoutJoiningGap() = runBlocking {
        repo.start(ActivityType.HIKE, wall, 0)
        repo.start(ActivityType.HIKE, wall, 0) // repeated tap must not create a second session
        repo.sample(fix(1_000), wall + 1_000, 1_000)
        repo.sample(fix(4_000, 1.0001), wall + 4_000, 4_000)
        val firstDistance = repo.dao.active()!!.distanceM
        repo.pause(wall + 5_000, 5_000)
        repo.sample(fix(6_000, 2.0), wall + 6_000, 6_000)
        assertEquals(2, repo.dao.pointCount())
        repo.resume(wall + 100_000, 100_000)
        repo.sample(fix(4_000), wall + 100_001, 100_001) // delayed pre-pause callback
        repo.sample(fix(101_000, 2.0), wall + 101_000, 101_000)
        val active = repo.dao.active()!!
        assertEquals(2, repo.dao.segmentCount(active.pathId))
        assertEquals(firstDistance, active.distanceM, 0.001)
        assertEquals(4_000L, active.activeDurationMs)
        assertEquals(3, repo.dao.pointCount())
        repo.finish(wall + 102_000, 102_000)
        assertNull(repo.dao.active())
        val saved = repo.dao.observeSession(active.pathId).first()!!
        assertEquals(RecordingState.FINISHED, saved.state)
        assertEquals(5_000L, saved.activeDurationMs)
    }

    @Test fun signalLossBreaksGeometryAndStopsClock() = runBlocking {
        repo.start(ActivityType.WALK, wall, 0)
        repo.sample(fix(1_000), wall + 1_000, 1_000)
        repo.heartbeat(wall + 40_000, 40_000)
        assertEquals(RecordingState.WAITING, repo.dao.active()!!.state)
        assertEquals(30_000L, repo.dao.active()!!.activeDurationMs)
        repo.sample(fix(50_000, 2.0), wall + 50_000, 50_000)
        assertEquals(0.0, repo.dao.active()!!.distanceM, 0.001)
        assertEquals(2, repo.dao.segmentCount(repo.dao.active()!!.pathId))
    }

    @Test fun inaccurateAndOutOfOrderPointsNeverCreateFalseDistance() = runBlocking {
        repo.start(ActivityType.RUN, wall, 0)
        repo.sample(fix(1_000), wall + 1_000, 1_000)
        repo.sample(fix(4_000, 2.0, 90f), wall + 4_000, 4_000)
        repo.sample(fix(3_000, 3.0), wall + 5_000, 5_000)
        repo.sample(fix(7_000, 1.0001), wall + 7_000, 7_000)
        assertEquals(3, repo.dao.pointCount())
        assertEquals(0.0, repo.dao.active()!!.distanceM, 0.001)
    }

    @Test fun recoveryNeverCountsDowntimeAndCanFinishWithoutLocation() = runBlocking {
        repo.start(ActivityType.BIKE, wall, 0)
        repo.sample(fix(1_000), wall + 1_000, 1_000)
        repo.heartbeat(wall + 6_000, 6_000)
        val pathId = repo.dao.active()!!.pathId
        repo.recoverInterrupted(wall + 100_000)
        assertEquals(RecordingState.INTERRUPTED, repo.dao.active()!!.state)
        assertEquals(5_000L, repo.dao.active()!!.activeDurationMs)
        repo.resume(wall + 200_000, 100) // device reboot reset monotonic clock
        repo.sample(fix(1_000, 2.0), wall + 201_000, 1_000)
        assertEquals(2, repo.dao.segmentCount(pathId))
        assertEquals(0.0, repo.dao.active()!!.distanceM, 0.001)
        repo.recoverInterrupted(wall + 210_000)
        repo.finish(wall + 220_000, 20_000)
        assertEquals(5_000L, repo.dao.observeSession(pathId).first()!!.activeDurationMs)
        assertTrue(repo.dao.events(repo.dao.observeSession(pathId).first()!!.id).any { it.type == "INTERRUPTED" })
    }

    @Test fun diskDatabaseSurvivesCloseAndReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "recovery-test-${java.util.UUID.randomUUID()}.db"
        var disk = Room.databaseBuilder(context, TrailDatabase::class.java, name).build()
        try {
            var diskRepo = RecordingRepository(disk)
            diskRepo.start(ActivityType.HIKE, wall, 0)
            diskRepo.sample(fix(1_000), wall + 1_000, 1_000)
            disk.close()
            disk = Room.databaseBuilder(context, TrailDatabase::class.java, name).build()
            diskRepo = RecordingRepository(disk)
            diskRepo.recoverInterrupted(wall + 10_000)
            assertEquals(1, diskRepo.dao.pointCount())
            assertEquals(RecordingState.INTERRUPTED, diskRepo.dao.active()!!.state)
        } finally { disk.close(); context.deleteDatabase(name) }
    }

    @Test fun onlyPausedCurrentRecordingCanBeDiscarded() = runBlocking {
        repo.start(ActivityType.HIKE, wall, 0)
        val pathId = repo.dao.active()!!.pathId
        repo.sample(fix(1_000), wall + 1_000, 1_000)
        assertFalse(repo.discardPaused(pathId))
        assertFalse(repo.discardPaused("other-path"))
        repo.pause(wall + 2_000, 2_000)
        assertTrue(repo.discardPaused(pathId))
        assertNull(repo.dao.active())
        assertEquals(0, repo.dao.pointCount())
        assertNull(repo.dao.exportPath(pathId))
    }
}
