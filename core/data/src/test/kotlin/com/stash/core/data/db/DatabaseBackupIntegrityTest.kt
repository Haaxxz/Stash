package com.stash.core.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Guards the restore safety gate: importDatabase must verify a staged backup
 * with PRAGMA integrity_check before it ever replaces the live database, so a
 * truncated/corrupt backup can't destroy the user's only library.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DatabaseBackupIntegrityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var manager: DatabaseBackupManager
    private lateinit var tmpDir: File

    @Before fun setUp() {
        // The DB dependency is only touched on commit; integrity verification
        // never uses it, so a mock is fine here.
        manager = DatabaseBackupManager(context, mockk(relaxed = true))
        tmpDir = File(context.cacheDir, "backup-test").apply { mkdirs() }
    }

    @After fun tearDown() { tmpDir.deleteRecursively() }

    @Test fun `a valid sqlite database passes verification`() {
        val good = File(tmpDir, "good.db")
        SQLiteDatabase.openOrCreateDatabase(good, null).use {
            it.execSQL("CREATE TABLE t(x INTEGER)")
            it.execSQL("INSERT INTO t VALUES (1)")
        }

        manager.verifyDatabaseIntegrity(good) // must not throw
    }

    @Test fun `a truncated or garbage file is rejected`() {
        val garbage = File(tmpDir, "corrupt.db").apply {
            writeBytes(byteArrayOf(0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x00, 0x01)) // partial header
        }

        assertThrows(Exception::class.java) { manager.verifyDatabaseIntegrity(garbage) }
    }
}
