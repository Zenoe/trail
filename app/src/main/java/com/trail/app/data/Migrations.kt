package com.trail.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migrations {
    val FROM_2_TO_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            listOf("sessions", "import_stats").forEach { table ->
                db.execSQL("ALTER TABLE $table ADD COLUMN ascentM REAL")
                db.execSQL("ALTER TABLE $table ADD COLUMN descentM REAL")
            }
            db.execSQL("ALTER TABLE sessions ADD COLUMN elevationAnchorM REAL")
            // 游标逐点回填老轨迹；无需把长轨迹载入内存，原始高程与 GPX 数据保持不变。
            db.query("SELECT id FROM paths").use { paths -> while (paths.moveToNext()) {
                val path = paths.getString(0)
                var totals = com.trail.app.domain.ElevationTotals()
                var segment: String? = null
                db.query("SELECT p.segmentId, p.elevationM, p.accepted FROM points p JOIN segments s ON p.segmentId=s.id WHERE s.pathId=? ORDER BY s.ordinal, p.ordinal", arrayOf(path)).use { points ->
                    while (points.moveToNext()) {
                        val nextSegment = points.getString(0)
                        if (nextSegment != segment) totals = totals.copy(anchorM = null)
                        segment = nextSegment
                        totals = totals.next(if (points.isNull(1) || points.getInt(2) == 0) null else points.getDouble(1))
                    }
                }
                listOf("sessions", "import_stats").forEach { table ->
                    db.execSQL("UPDATE $table SET ascentM=?, descentM=? WHERE pathId=?", arrayOf(totals.ascentM, totals.descentM, path))
                }
            } }
        }
    }
    /** 只添加字段和表，阶段 1 的记录/分段/计时原样保留，绝不清库“修复”。 */
    val FROM_1_TO_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE documents ADD COLUMN publicationState TEXT NOT NULL DEFAULT 'READY'")
            listOf("originalFilePath", "originalSha256", "gpxVersion", "creator").forEach {
                db.execSQL("ALTER TABLE documents ADD COLUMN $it TEXT")
            }
            listOf("timeLexical", "name", "symbol").forEach { db.execSQL("ALTER TABLE points ADD COLUMN $it TEXT") }
            db.execSQL("ALTER TABLE points ADD COLUMN extraXml TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE TABLE IF NOT EXISTS waypoints (id TEXT NOT NULL PRIMARY KEY, documentId TEXT NOT NULL, ordinal INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, elevationM REAL, timeUtcMs INTEGER, timeLexical TEXT, name TEXT, symbol TEXT, extraXml TEXT NOT NULL, FOREIGN KEY(documentId) REFERENCES documents(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_waypoints_documentId_ordinal ON waypoints(documentId, ordinal)")
            db.execSQL("CREATE TABLE IF NOT EXISTS import_stats (pathId TEXT NOT NULL PRIMARY KEY, distanceM REAL NOT NULL, elapsedMs INTEGER, pointCount INTEGER NOT NULL, segmentCount INTEGER NOT NULL, warnings TEXT NOT NULL, FOREIGN KEY(pathId) REFERENCES paths(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE TABLE IF NOT EXISTS gpx_elements (id TEXT NOT NULL PRIMARY KEY, documentId TEXT NOT NULL, ownerType TEXT NOT NULL, ownerKey TEXT NOT NULL, ordinal INTEGER NOT NULL, xml TEXT NOT NULL, FOREIGN KEY(documentId) REFERENCES documents(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_gpx_elements_documentId ON gpx_elements(documentId)")
        }
    }
}
