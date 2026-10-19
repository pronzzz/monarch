package com.antigravity.monarch

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.antigravity.monarch.data.local.MonarchDatabase
import com.antigravity.monarch.data.security.SecretCipher
import com.antigravity.monarch.data.strava.EncryptedStravaTokenStore
import com.antigravity.monarch.data.strava.StravaTokens
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationAndSecurityTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MonarchDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun migrate1To2KeepsWorkoutsAndAddsOrigin() {
        val name = "migration-test.db"
        helper.createDatabase(name, 1).use { db ->
            db.execSQL(
                "INSERT INTO workout (id, type, title, durationMinutes, intensity, epochDay, startedAt, source, externalId, notes, xpAwarded) " +
                    "VALUES (1, 'RUNNING', 'Old run', 30, 'MODERATE', 20000, 5, 'HEALTH_CONNECT', 'hc-1', '', 30)",
            )
        }
        helper.runMigrationsAndValidate(name, 2, true, *MonarchDatabase.MIGRATIONS).use { db ->
            db.query("SELECT title, xpAwarded, originApp FROM workout WHERE id = 1").use { c ->
                c.moveToFirst()
                assertEquals("Old run", c.getString(0))
                assertEquals(30, c.getInt(1))
                assertEquals(true, c.isNull(2))
            }
        }
    }

    @Test
    fun secretsRoundTripAndAreNotStoredInPlainText() = runBlocking {
        val cipher = SecretCipher()
        val sealed = cipher.encrypt("super-secret-token")
        assertFalse(sealed.contains("super-secret-token"))
        assertEquals("super-secret-token", cipher.decrypt(sealed))
        assertNull(cipher.decrypt("not-base64-ciphertext"))

        val store = EncryptedStravaTokenStore(context, cipher)
        store.clear()
        assertNull(store.load())
        val tokens = StravaTokens("acc-123", "ref-456", 1_800_000_000, "Sung Jinwoo")
        store.save(tokens)
        assertEquals(tokens, store.load())
        val raw = context.getSharedPreferences(EncryptedStravaTokenStore.PREFS, Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(raw.contains("acc-123"))
        assertFalse(raw.contains("ref-456"))
        store.clear()
        assertNull(store.load())
    }
}
