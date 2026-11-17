package com.example.engine.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.example.core.database.AppLogDao
import com.example.core.database.AppLogEntity
import com.example.core.database.DatabaseInstanceDao
import com.example.core.database.DatabaseInstanceEntity
import com.example.core.model.DbEngineType
import com.example.core.model.EngineInfo
import com.example.core.model.EngineStatus
import com.example.core.model.EngineType
import com.example.core.model.SqlQueryResult
import com.example.core.model.SqliteColumnInfo
import com.example.core.model.SqliteTableInfo
import com.example.core.security.SecurityHelper
import com.example.engine.core.ProcessProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import java.util.zip.ZipInputStream

class DatabaseManager(
    private val context: Context,
    private val dbDao: DatabaseInstanceDao,
    private val logDao: AppLogDao
) {
    private val dbRootDir = File(context.filesDir, "databases").apply { mkdirs() }

    fun getSqliteEngineStatus(): EngineInfo {
        val dbCount = dbRootDir.listFiles { f -> f.extension == "db" }?.size ?: 0
        return EngineInfo(
            type = EngineType.SQLITE,
            status = EngineStatus.RUNNING,
            activeVersion = "SQLite ${readSqliteVersion()}",
            availableVersions = listOf(readSqliteVersion()),
            port = 0,
            binaryPath = "مدمج في Android SDK (android.database.sqlite)",
            architecture = ProcessProbe.getDeviceAbi(),
            verificationDetails = "نشط ومفعل — يدير حالياً $dbCount ملف قاعدة بيانات محلية معزولة.",
            requirementNotice = "محرك SQLite مدمج بالكامل، يدعم إنشاء الجداول، استعلامات SQL، الاستيراد والتصدير بصيغة .sql.",
            lastCheckedAt = System.currentTimeMillis()
        )
    }

    fun probePhpMyAdminStatus(): EngineInfo {
        val pmaDir = File(context.filesDir, "runtimes/phpmyadmin")
        val indexPhp = File(pmaDir, "index.php")
        val hasFiles = indexPhp.exists()
        val hasPhp = ProcessProbe.findBinary(context, listOf("php-cgi", "php"), "php") != null
        val hasMysql = ProcessProbe.isTcpPortListening("127.0.0.1", 3306, 200)

        val status = when {
            hasFiles && hasPhp && hasMysql -> EngineStatus.RUNNING
            hasFiles -> EngineStatus.STOPPED
            else -> EngineStatus.NOT_INSTALLED
        }

        val details = buildString {
            append(if (hasFiles) "ملفات phpMyAdmin مثبتة. " else "أرشيف phpMyAdmin غير مستورد. ")
            append(if (hasPhp) "PHP متوفر. " else "يتطلب محرك PHP. ")
            append(if (hasMysql) "خادم MySQL/MariaDB متصل على 3306." else "يتطلب تشغيل خادم MySQL/MariaDB.")
        }

        return EngineInfo(
            type = EngineType.PHPMYADMIN,
            status = status,
            activeVersion = if (hasFiles) "5.2.x (Local Archive)" else "غير مثبت",
            availableVersions = listOf("5.2.1"),
            port = 8090,
            binaryPath = if (hasFiles) pmaDir.absolutePath else null,
            architecture = ProcessProbe.getDeviceAbi(),
            verificationDetails = details,
            requirementNotice = "تتطلب واجهة phpMyAdmin استيراد أرشيف phpMyAdmin الرسمي (.zip) وربطه بمحركي PHP و MariaDB/MySQL.",
            lastCheckedAt = System.currentTimeMillis()
        )
    }

    private fun readSqliteVersion(): String {
        return try {
            val db = SQLiteDatabase.create(null)
            val cursor = db.rawQuery("SELECT sqlite_version()", null)
            val v = if (cursor.moveToFirst()) cursor.getString(0) else "3.x"
            cursor.close()
            db.close()
            v
        } catch (_: Exception) {
            "3.x"
        }
    }

    suspend fun createDatabase(
        name: String,
        engineType: DbEngineType,
        username: String,
        password: String,
        privileges: String
    ): Result<DatabaseInstanceEntity> = withContext(Dispatchers.IO) {
        val cleanName = SecurityHelper.sanitizeSlug(name).replace('-', '_')
        val id = "${cleanName}_${System.currentTimeMillis() % 100000}"
        val passwordHash = SecurityHelper.hashSecret(password)

        return@withContext try {
            val target = if (engineType == DbEngineType.SQLITE) {
                val dbFile = File(dbRootDir, "$id.db")
                val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
                sqlite.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS _abotalal_meta (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                val cv = ContentValues().apply {
                    put("key", "db_name")
                    put("value", cleanName)
                }
                sqlite.insertWithOnConflict("_abotalal_meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                sqlite.close()
                dbFile.absolutePath
            } else {
                "127.0.0.1:${engineType.defaultPort}/$cleanName"
            }

            val entity = DatabaseInstanceEntity(
                id = id,
                name = cleanName,
                engineType = engineType.name,
                storageTarget = target,
                username = username.ifBlank { "db_admin" },
                passwordHash = passwordHash,
                privileges = privileges
            )
            dbDao.upsert(entity)
            logDao.insert(
                AppLogEntity(
                    level = "INFO",
                    source = "DATABASE",
                    message = "تم إنشاء قاعدة البيانات '$cleanName' (${engineType.titleAr}) للمستخدم ${entity.username}"
                )
            )
            Result.success(entity)
        } catch (e: Exception) {
            logDao.insert(
                AppLogEntity(
                    level = "ERROR",
                    source = "DATABASE",
                    message = "فشل إنشاء قاعدة البيانات $cleanName: ${e.message}"
                )
            )
            Result.failure(e)
        }
    }

    suspend fun deleteDatabase(db: DatabaseInstanceEntity) = withContext(Dispatchers.IO) {
        if (db.engineType == DbEngineType.SQLITE.name) {
            File(db.storageTarget).delete()
            File("${db.storageTarget}-journal").delete()
            File("${db.storageTarget}-wal").delete()
            File("${db.storageTarget}-shm").delete()
        }
        dbDao.deleteById(db.id)
        logDao.insert(
            AppLogEntity(
                level = "INFO",
                source = "DATABASE",
                message = "تم حذف قاعدة البيانات '${db.name}'"
            )
        )
    }

    suspend fun updateUserPrivileges(
        db: DatabaseInstanceEntity,
        newUsername: String,
        newPassword: String?,
        newPrivileges: String
    ) = withContext(Dispatchers.IO) {
        val updatedHash = if (!newPassword.isNullOrBlank()) {
            SecurityHelper.hashSecret(newPassword)
        } else {
            db.passwordHash
        }
        dbDao.upsert(
            db.copy(
                username = newUsername.ifBlank { db.username },
                passwordHash = updatedHash,
                privileges = newPrivileges
            )
        )
        logDao.insert(
            AppLogEntity(
                level = "SECURITY",
                source = "DATABASE",
                message = "تم تحديث صلاحيات المستخدم '${newUsername}' على قاعدة '${db.name}' إلى $newPrivileges"
            )
        )
    }

    suspend fun inspectSqliteTables(dbFilePath: String): List<SqliteTableInfo> = withContext(Dispatchers.IO) {
        val file = File(dbFilePath)
        if (!file.exists()) return@withContext emptyList()
        val sqlite = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        val tables = mutableListOf<SqliteTableInfo>()
        try {
            val tableCursor = sqlite.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' ORDER BY name",
                null
            )
            while (tableCursor.moveToNext()) {
                val tableName = tableCursor.getString(0)
                val cols = mutableListOf<SqliteColumnInfo>()
                val pragmaCursor = sqlite.rawQuery("PRAGMA table_info(\"$tableName\")", null)
                while (pragmaCursor.moveToNext()) {
                    cols.add(
                        SqliteColumnInfo(
                            name = pragmaCursor.getString(1),
                            type = pragmaCursor.getString(2).ifBlank { "TEXT" },
                            notNull = pragmaCursor.getInt(3) == 1,
                            isPrimaryKey = pragmaCursor.getInt(5) > 0
                        )
                    )
                }
                pragmaCursor.close()

                val countCursor = sqlite.rawQuery("SELECT COUNT(*) FROM \"$tableName\"", null)
                val rowCount = if (countCursor.moveToFirst()) countCursor.getLong(0) else 0L
                countCursor.close()

                tables.add(SqliteTableInfo(name = tableName, rowCount = rowCount, columns = cols))
            }
            tableCursor.close()
        } finally {
            sqlite.close()
        }
        tables
    }

    suspend fun executeSqliteQuery(
        db: DatabaseInstanceEntity,
        sql: String
    ): SqlQueryResult = withContext(Dispatchers.IO) {
        if (db.engineType != DbEngineType.SQLITE.name) {
            return@withContext SqlQueryResult(
                isError = true,
                message = "تنفيذ SQL المباشر مفعّل لقواعد SQLite المحلية؛ أما ${db.engineType} فيتطلب تشغيل خادم TCP الخارجي المرتبط به."
            )
        }
        val trimmed = sql.trim()
        if (trimmed.isBlank()) {
            return@withContext SqlQueryResult(isError = true, message = "يرجى إدخال استعلام SQL صالح.")
        }
        if (db.privileges == "READ_ONLY") {
            val upper = trimmed.uppercase()
            if (!upper.startsWith("SELECT") && !upper.startsWith("PRAGMA") && !upper.startsWith("EXPLAIN")) {
                return@withContext SqlQueryResult(
                    isError = true,
                    message = "صلاحيات المستخدم (${db.username}) مقيدة بـ READ_ONLY ولا تسمح بتعديل البيانات."
                )
            }
        }

        val start = System.currentTimeMillis()
        val sqlite = SQLiteDatabase.openOrCreateDatabase(File(db.storageTarget), null)
        return@withContext try {
            val statements = splitSqlStatements(trimmed)
            if (statements.isEmpty()) {
                return@withContext SqlQueryResult(isError = true, message = "يرجى إدخال استعلام SQL صالح.")
            }
            val lastUpper = statements.last().uppercase()
            val lastIsQuery = lastUpper.startsWith("SELECT") || lastUpper.startsWith("PRAGMA") || lastUpper.startsWith("EXPLAIN")
            val nonQueryStatements = if (lastIsQuery) statements.dropLast(1) else statements

            if (nonQueryStatements.isNotEmpty()) {
                sqlite.beginTransaction()
                try {
                    nonQueryStatements.forEach { stmt -> sqlite.execSQL(stmt) }
                    sqlite.setTransactionSuccessful()
                } finally {
                    sqlite.endTransaction()
                }
            }

            if (lastIsQuery) {
                val cursor = sqlite.rawQuery(statements.last(), null)
                val cols = cursor.columnNames.toList()
                val rows = mutableListOf<List<String>>()
                var limit = 0
                while (cursor.moveToNext() && limit < 200) {
                    val row = cols.indices.map { idx ->
                        when (cursor.getType(idx)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> "NULL"
                            android.database.Cursor.FIELD_TYPE_BLOB -> "<BLOB ${cursor.getBlob(idx).size}B>"
                            else -> cursor.getString(idx) ?: ""
                        }
                    }
                    rows.add(row)
                    limit++
                }
                cursor.close()
                SqlQueryResult(
                    columns = cols,
                    rows = rows,
                    message = "تم إرجاع ${rows.size} صف",
                    executionTimeMs = System.currentTimeMillis() - start
                )
            } else {
                val changesCursor = sqlite.rawQuery("SELECT total_changes()", null)
                val affected = if (changesCursor.moveToFirst()) changesCursor.getInt(0) else 0
                changesCursor.close()
                SqlQueryResult(
                    affectedRows = affected,
                    message = "تم تنفيذ ${nonQueryStatements.size} أمر SQL بنجاح",
                    executionTimeMs = System.currentTimeMillis() - start
                )
            }
        } catch (e: Exception) {
            SqlQueryResult(
                isError = true,
                message = "خطأ SQL: ${e.message}",
                executionTimeMs = System.currentTimeMillis() - start
            )
        } finally {
            sqlite.close()
        }
    }

    suspend fun importSqlFile(db: DatabaseInstanceEntity, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        if (db.engineType != DbEngineType.SQLITE.name) {
            return@withContext Result.failure(IllegalStateException("الاستيراد المباشر متاح لقواعد SQLite المحلية حالياً."))
        }
        return@withContext try {
            val sqlText = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: return@withContext Result.failure(IllegalStateException("تعذر قراءة ملف SQL"))
            val statements = splitSqlStatements(sqlText).filterNot { stmt ->
                val u = stmt.trim().uppercase()
                u == "BEGIN" || u == "BEGIN TRANSACTION" || u == "COMMIT" || u == "END" || u == "END TRANSACTION"
            }
            val sqlite = SQLiteDatabase.openOrCreateDatabase(File(db.storageTarget), null)
            sqlite.beginTransaction()
            try {
                statements.forEach { sqlite.execSQL(it) }
                sqlite.setTransactionSuccessful()
            } finally {
                sqlite.endTransaction()
                sqlite.close()
            }
            logDao.insert(
                AppLogEntity(
                    level = "INFO",
                    source = "DATABASE",
                    message = "تم استيراد ${statements.size} أمر SQL إلى قاعدة '${db.name}'"
                )
            )
            Result.success("تم استيراد وتنفيذ ${statements.size} أمر SQL بنجاح")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun exportSqlDump(db: DatabaseInstanceEntity, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        if (db.engineType != DbEngineType.SQLITE.name) {
            return@withContext Result.failure(IllegalStateException("التصدير المباشر متاح لقواعد SQLite المحلية حالياً."))
        }
        return@withContext try {
            val sqlite = SQLiteDatabase.openDatabase(db.storageTarget, null, SQLiteDatabase.OPEN_READONLY)
            val dump = StringBuilder()
            dump.append("-- Abotalal Hosting SQLite Export\n")
            dump.append("-- Database: ${db.name}\n\n")
            dump.append("BEGIN TRANSACTION;\n")
            try {
                val schemaCursor = sqlite.rawQuery(
                    "SELECT name, sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND name != '_abotalal_meta'",
                    null
                )
                val tables = mutableListOf<String>()
                while (schemaCursor.moveToNext()) {
                    val tName = schemaCursor.getString(0)
                    val createSql = schemaCursor.getString(1)
                    if (!createSql.isNullOrBlank()) {
                        dump.append(createSql).append(";\n")
                        tables.add(tName)
                    }
                }
                schemaCursor.close()

                for (table in tables) {
                    val rowsCursor = sqlite.rawQuery("SELECT * FROM \"$table\"", null)
                    val cols = rowsCursor.columnNames
                    while (rowsCursor.moveToNext()) {
                        val values = cols.indices.joinToString(", ") { idx ->
                            when (rowsCursor.getType(idx)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> "NULL"
                                android.database.Cursor.FIELD_TYPE_INTEGER -> rowsCursor.getLong(idx).toString()
                                android.database.Cursor.FIELD_TYPE_FLOAT -> rowsCursor.getDouble(idx).toString()
                                else -> "'${(rowsCursor.getString(idx) ?: "").replace("'", "''")}'"
                            }
                        }
                        dump.append("INSERT INTO \"$table\" VALUES($values);\n")
                    }
                    rowsCursor.close()
                }
                dump.append("COMMIT;\n")
            } finally {
                sqlite.close()
            }

            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(dump.toString().toByteArray(Charsets.UTF_8))
            } ?: return@withContext Result.failure(IllegalStateException("تعذر الكتابة إلى الملف المحدد"))

            logDao.insert(
                AppLogEntity(
                    level = "INFO",
                    source = "DATABASE",
                    message = "تم تصدير نسخة SQL احتياطية من '${db.name}'"
                )
            )
            Result.success("تم تصدير ملف SQL بنجاح")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importPhpMyAdminArchive(uri: Uri, mysqlHost: String = "127.0.0.1", mysqlPort: Int = 3306): Result<String> =
        withContext(Dispatchers.IO) {
            val targetDir = File(context.filesDir, "runtimes/phpmyadmin").apply {
                deleteRecursively()
                mkdirs()
            }
            return@withContext try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    ZipInputStream(stream).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val cleanName = entry.name.substringAfter('/', entry.name)
                            if (cleanName.isNotBlank()) {
                                val outFile = SecurityHelper.resolveSafeChild(targetDir, cleanName)
                                    ?: throw SecurityException("مسار غير آمن داخل أرشيف phpMyAdmin")
                                if (entry.isDirectory) {
                                    outFile.mkdirs()
                                } else {
                                    outFile.parentFile?.mkdirs()
                                    outFile.outputStream().use { zis.copyTo(it) }
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                } ?: return@withContext Result.failure(IllegalStateException("تعذر فتح ملف phpMyAdmin"))

                val blowfish = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                val configPhp = """
                    <?php
                    ${'$'}cfg['blowfish_secret'] = '$blowfish';
                    ${'$'}i = 0;
                    ${'$'}i++;
                    ${'$'}cfg['Servers'][${'$'}i]['auth_type'] = 'cookie';
                    ${'$'}cfg['Servers'][${'$'}i]['host'] = '$mysqlHost';
                    ${'$'}cfg['Servers'][${'$'}i]['port'] = '$mysqlPort';
                    ${'$'}cfg['Servers'][${'$'}i]['AllowNoPassword'] = false;
                """.trimIndent()
                File(targetDir, "config.inc.php").writeText(configPhp)

                logDao.insert(
                    AppLogEntity(
                        level = "INFO",
                        source = "PHPMYADMIN",
                        message = "تم فك أرشيف phpMyAdmin وتوليد config.inc.php مرتبطاً بالمنفذ $mysqlHost:$mysqlPort"
                    )
                )
                Result.success("تم إعداد ملفات phpMyAdmin وربطه بـ $mysqlHost:$mysqlPort")
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun splitSqlStatements(sql: String): List<String> {
        return sql.lines()
            .filter { !it.trim().startsWith("--") }
            .joinToString("\n")
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
