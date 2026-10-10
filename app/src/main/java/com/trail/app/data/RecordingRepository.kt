package com.trail.app.data

import androidx.room.withTransaction
import com.trail.app.domain.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** 所有写操作在事务内重新读取状态，避免重复点击、迟到的 GPS 回调和通知操作相互覆盖。 */
class RecordingRepository(private val db: TrailDatabase) {
    val dao = db.dao()
    private fun id() = UUID.randomUUID().toString()

    suspend fun start(activity: ActivityType, wall: Long, elapsed: Long) = db.withTransaction {
        if (dao.active() != null) return@withTransaction
        val documentId = id(); val pathId = id()
        val date = DateTimeFormatter.ofPattern("MM月dd日 HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(wall))
        val name = "$date · ${activity.label}"
        dao.insert(DocumentEntity(documentId, name, wall))
        dao.insert(PathEntity(pathId, documentId, name, activity, wall))
        val session = SessionEntity(id(), pathId, RecordingState.WAITING, startedAt = wall,
            lastCommittedAt = wall, acceptingSinceMs = elapsed)
        dao.insert(session)
        event(session, "START", wall, elapsed)
    }

    suspend fun pause(wall: Long, elapsed: Long) = db.withTransaction {
        val s = dao.active() ?: return@withTransaction
        if (!RecordingRules.canPause(s.state)) return@withTransaction
        dao.update(advance(s, wall, elapsed).copy(state = RecordingState.PAUSED, segmentId = null, lastElapsedMs = null))
        event(s, "PAUSE", wall, elapsed)
    }

    suspend fun resume(wall: Long, elapsed: Long) = db.withTransaction {
        val s = dao.active() ?: return@withTransaction
        if (!RecordingRules.canResume(s.state)) return@withTransaction
        dao.update(s.copy(state = RecordingState.WAITING, segmentId = null, lastFixElapsedMs = null,
            lastElapsedMs = null, acceptingSinceMs = elapsed, lastCommittedAt = wall, accuracyM = null))
        event(s, "RESUME", wall, elapsed)
    }

    suspend fun finish(wall: Long, elapsed: Long) = db.withTransaction {
        val s = dao.active() ?: return@withTransaction
        dao.update(advance(s, wall, elapsed).copy(state = RecordingState.FINISHED, activeSlot = null,
            endedAt = wall, segmentId = null, lastElapsedMs = null))
        event(s, "FINISH", wall, elapsed)
    }

    /** 丢弃仅限暂停/中断的当前记录；与 GPS 写入共享服务队列，防止删除后迟到点重新写入。 */
    suspend fun discardPaused(pathId: String): Boolean = db.withTransaction {
        val session = dao.active() ?: return@withTransaction false
        if (session.pathId != pathId || session.state !in setOf(RecordingState.PAUSED, RecordingState.INTERRUPTED)) return@withTransaction false
        val path = dao.exportPath(pathId) ?: return@withTransaction false
        dao.deletePathElements(pathId)
        dao.deletePath(pathId)
        if (dao.pathCount(path.documentId) == 0 && dao.waypointCount(path.documentId) == 0) dao.deleteDocument(path.documentId)
        true
    }

    /** 启动时先封存上次进程的单调时钟，绝不能把进程死亡/关机时间计入有效时长。 */
    suspend fun recoverInterrupted(wall: Long) = db.withTransaction {
        val s = dao.active() ?: return@withTransaction
        if (s.state == RecordingState.INTERRUPTED) return@withTransaction
        dao.update(s.copy(state = RecordingState.INTERRUPTED, segmentId = null, lastElapsedMs = null, lastFixElapsedMs = null))
        event(s, "INTERRUPTED", wall, null)
    }

    suspend fun heartbeat(wall: Long, elapsed: Long) = db.withTransaction {
        val s = dao.active() ?: return@withTransaction
        if (s.state != RecordingState.RECORDING) return@withTransaction
        val next = advance(s, wall, elapsed)
        if (s.lastFixElapsedMs != null && elapsed - s.lastFixElapsedMs > RecordingRules.SIGNAL_TIMEOUT_MS) {
            dao.update(next.copy(state = RecordingState.WAITING, segmentId = null, lastElapsedMs = null))
            event(s, "SIGNAL_LOST", wall, elapsed)
        } else dao.update(next)
    }

    suspend fun sample(fix: LocationSample, wall: Long, elapsed: Long) = db.withTransaction {
        val s = dao.active() ?: return@withTransaction
        if (!RecordingRules.canPause(s.state) || !RecordingRules.valid(fix)) return@withTransaction
        // 拒绝缓存位置、乱序位置，以及暂停前排队但恢复后才到达的位置。
        if (fix.elapsedMs < s.acceptingSinceMs || fix.elapsedMs > elapsed ||
            elapsed - fix.elapsedMs > 15_000 || fix.elapsedMs <= (s.lastFixElapsedMs ?: -1)) return@withTransaction
        val good = RecordingRules.precise(fix)
        var segmentId = s.segmentId
        val gap = s.lastFixElapsedMs?.let { fix.elapsedMs - it > RecordingRules.SIGNAL_TIMEOUT_MS } ?: false
        if (segmentId == null || gap) {
            segmentId = id()
            dao.insert(SegmentEntity(segmentId, s.pathId, dao.segmentCount(s.pathId), if (gap) "SIGNAL_GAP" else "START_OR_RESUME"))
        }
        val previous = dao.lastPoint(segmentId)
        // 低精度原始点保留，但前后不连线、不计距离。阶段 4 再增加速度/漂移滤波。
        val addition = if (good && previous?.accepted == true) RecordingRules.distance(
            previous.latitude, previous.longitude, fix.latitude, fix.longitude) else 0.0
        dao.insert(PointEntity(segmentId, (previous?.ordinal ?: -1) + 1, fix.latitude, fix.longitude,
            fix.wallTimeMs, fix.altitudeM?.takeIf { it.isFinite() }, fix.accuracyM,
            fix.speedMps?.takeIf { it.isFinite() && it >= 0 }, fix.elapsedMs, good))
        val updated = advance(s, wall, elapsed)
        dao.update(updated.copy(state = if (good) RecordingState.RECORDING else RecordingState.WAITING,
            segmentId = if (good) segmentId else null, distanceM = s.distanceM + addition,
            lastElapsedMs = if (good) elapsed else null,
            lastFixElapsedMs = fix.elapsedMs, accuracyM = fix.accuracyM, elevationM = fix.altitudeM))
    }

    private fun advance(s: SessionEntity, wall: Long, elapsed: Long): SessionEntity {
        // 即使心跳被系统延迟，失联后的时间也不无限累计；下一点会重新开启计时。
        val capped = s.lastFixElapsedMs?.let { minOf(elapsed, it + RecordingRules.SIGNAL_TIMEOUT_MS) } ?: elapsed
        val delta = if (s.state == RecordingState.RECORDING) RecordingRules.durationDelta(s.lastElapsedMs, capped) else 0
        return s.copy(activeDurationMs = s.activeDurationMs + delta, lastElapsedMs = elapsed, lastCommittedAt = wall)
    }
    private suspend fun event(s: SessionEntity, type: String, wall: Long, elapsed: Long?) =
        dao.insert(RecordingEventEntity(id(), s.id, type, wall, elapsed))
}
