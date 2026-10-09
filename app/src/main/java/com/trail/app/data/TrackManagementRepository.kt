package com.trail.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TrackManagementRepository(private val db: TrailDatabase, private val originals: File) {
    private val dao = db.dao()
    suspend fun renamePath(id: String, raw: String) = db.withTransaction {
        val name = raw.trim()
        require(name.isNotEmpty() && name.length <= 100) { "名称须为 1–100 个字符" }
        require(dao.exportPath(id) != null) { "路线已不存在" }
        dao.renamePath(id, name)
    }
    suspend fun renameWaypointDocument(id: String, raw: String) = db.withTransaction {
        val name = raw.trim()
        require(name.isNotEmpty() && name.length <= 100) { "名称须为 1–100 个字符" }
        require(dao.exportDocument(id) != null && dao.pathCount(id) == 0) { "标记文件已不存在" }
        dao.renameDocument(id, name)
    }
    suspend fun deletePath(id: String) = withContext(Dispatchers.IO) {
        val original = db.withTransaction {
            val path = dao.exportPath(id) ?: return@withTransaction null
            require(dao.activeSlot(id) != 1) { "请先结束当前记录，再删除轨迹" }
            val document = dao.exportDocument(path.documentId) ?: return@withTransaction null
            // 扩展字段按 document 外键保存，删路径前需显式清理其 ownerKey，否则会留孤儿片段。
            dao.deletePathElements(id)
            dao.deletePath(id)
            if (dao.pathCount(document.id) == 0 && dao.waypointCount(document.id) == 0) {
                dao.deleteDocument(document.id)
                document.originalFilePath
            } else null
        }
        cleanupOriginal(original)
    }
    suspend fun deleteWaypointDocument(id: String) = withContext(Dispatchers.IO) {
        val original = db.withTransaction {
            val document = dao.exportDocument(id) ?: return@withTransaction null
            require(dao.pathCount(id) == 0) { "此文件仍有路线，不能作为标记文件删除" }
            dao.deleteDocument(id)
            document.originalFilePath
        }
        cleanupOriginal(original)
    }
    private fun cleanupOriginal(path: String?) {
        if (path == null) return
        val file = File(path)
        // 路径只由本应用创建，但删除前仍固定在私有 originals 子目录，避免损坏外部文件。
        if (file.parentFile?.canonicalFile == originals.canonicalFile) file.delete()
    }
}
