package com.trail.app.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import org.json.JSONObject
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*

class MigrationTest {
    @Test fun versionTwoBackfillsImportedElevationWithoutChangingRawPoints() {
        val name = "elevation-migration-${java.util.UUID.randomUUID()}"
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val schema = instrumentation.context.assets.open("com.trail.app.data.TrailDatabase/2.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO documents(id,name,createdAt,source,metadataJson,publicationState) VALUES ('d','old',1,'IMPORTED','{}','READY')")
            db.execSQL("INSERT INTO paths VALUES ('p','d','old','HIKE',1,0,'TRACK','{}')")
            db.execSQL("INSERT INTO segments VALUES ('s','p',0,'IMPORT')")
            listOf(100,110,105).forEachIndexed { i, h ->
                db.execSQL("INSERT INTO points(segmentId,ordinal,latitude,longitude,elevationM,elapsedMs,accepted) VALUES ('s',?,1,103,?,0,1)", arrayOf(i,h))
            }
            db.execSQL("INSERT INTO import_stats VALUES ('p',0,NULL,3,1,'')")
            db.version = 2
        }
        val room = Room.databaseBuilder(context, TrailDatabase::class.java, name).addMigrations(Migrations.FROM_2_TO_3).build()
        try {
            room.openHelper.writableDatabase.query("SELECT ascentM, descentM FROM import_stats WHERE pathId='p'").use {
                assertTrue(it.moveToFirst()); assertEquals(10.0,it.getDouble(0),0.001); assertEquals(5.0,it.getDouble(1),0.001)
            }
            room.openHelper.writableDatabase.query("SELECT COUNT(*), SUM(elevationM) FROM points").use {
                assertTrue(it.moveToFirst()); assertEquals(3,it.getInt(0)); assertEquals(315.0,it.getDouble(1),0.001)
            }
        } finally { room.close(); context.deleteDatabase(name) }
    }
    @Test fun migratesVersionOneWithoutLosingRecording() {
        val name = "migration-${java.util.UUID.randomUUID()}"
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val schema = instrumentation.context.assets.open("com.trail.app.data.TrailDatabase/1.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        // 从已提交的 v1 schema 建库，再由真实 Room 打开并校验所有表，避免手写旧结构漂移。
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).apply {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) {
                    execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) execSQL(setup.getString(i))
            execSQL("INSERT INTO documents VALUES ('doc', '老记录', 123, 'RECORDED', '{}')")
            execSQL("INSERT INTO paths VALUES ('path', 'doc', '老记录', 'HIKE', 123, 0, 'TRACK', '{}')")
            execSQL("INSERT INTO segments VALUES ('seg', 'path', 0, 'START')")
            execSQL("INSERT INTO points VALUES ('seg', 0, 1.0, 103.0, 123, NULL, 5.0, NULL, 1000, 1)")
            version = 1
            close()
        }
        val room = Room.databaseBuilder(context, TrailDatabase::class.java, name)
            .addMigrations(Migrations.FROM_1_TO_2, Migrations.FROM_2_TO_3).build()
        try {
        val migrated = room.openHelper.writableDatabase
        migrated.query("SELECT publicationState, name FROM documents WHERE id = 'doc'").use {
            assertTrue(it.moveToFirst()); assertEquals("READY", it.getString(0)); assertEquals("老记录", it.getString(1))
        }
        migrated.query("SELECT latitude, extraXml, timeLexical FROM points").use {
            assertTrue(it.moveToFirst()); assertEquals(1.0, it.getDouble(0), 0.0); assertEquals("", it.getString(1)); assertTrue(it.isNull(2))
        }
        } finally {
            room.close()
            context.deleteDatabase(name)
        }
    }
}
