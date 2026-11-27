package com.mg4.control.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.model.DriveMode
import com.mg4.control.model.DrivingProfile
import com.mg4.control.model.RegenLevel
import com.mg4.control.profile.ProfileManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** API Profils (issue #288) : qui passe, ce qu'on lit, ce qu'on refuse. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProfileApiTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun `the decision follows the stored answer and the signature`() {
        val ok = ProfileApiAccess.Entry("a.b", true, "cert1", "A", 0L)
        assertEquals(ProfileApiAccess.Status.UNKNOWN, ProfileApiAccess.decide(null, "cert1"))
        assertEquals(ProfileApiAccess.Status.ALLOWED, ProfileApiAccess.decide(ok, "cert1"))
        assertEquals(ProfileApiAccess.Status.CHANGED, ProfileApiAccess.decide(ok, "other"))
        assertEquals(ProfileApiAccess.Status.CHANGED, ProfileApiAccess.decide(ok, null))
        assertEquals(ProfileApiAccess.Status.DENIED, ProfileApiAccess.decide(ok.copy(allowed = false), "cert1"))
    }

    private fun provider() = Robolectric.setupContentProvider(ProfilesProvider::class.java, ctx.packageName + ".profiles")

    @Test fun `an unknown or denied caller reads nothing and cannot apply`() {
        ProfileManager(ctx).save(DrivingProfile(id = "p1", name = "Ville", driveMode = DriveMode.ECO, regenLevel = RegenLevel.MEDIUM))
        val p = provider()
        assertNull(p.query(ProfilesProvider.uri(ctx), null, null, null, null))
        assertEquals("pending", p.call("status", null, null)!!.getString("status"))
        ProfileApiAccess.record(ctx, ctx.packageName, false)
        assertNull(p.query(ProfilesProvider.uri(ctx), null, null, null, null))
        val r = p.call("apply", "Ville", null)!!
        assertFalse(r.getBoolean("ok"))
        assertEquals("not_allowed", r.getString("error"))
    }

    @Test fun `an allowed caller reads every profile and gets clear errors`() {
        val pm = ProfileManager(ctx)
        pm.save(DrivingProfile(id = "p1", name = "Ville", driveMode = DriveMode.ECO, regenLevel = RegenLevel.MEDIUM))
        pm.save(DrivingProfile(id = "p2", name = "Route", driveMode = DriveMode.SPORT, regenLevel = RegenLevel.MEDIUM))
        ProfileApiAccess.record(ctx, ctx.packageName, true)
        val p = provider()
        assertEquals("allowed", p.call("status", null, null)!!.getString("status"))
        val c = p.query(ProfilesProvider.uri(ctx), null, null, null, null)!!
        assertEquals(2, c.count)
        assertTrue(c.moveToFirst())
        assertEquals("p1", c.getString(c.getColumnIndexOrThrow("id")))
        assertEquals("Ville", c.getString(c.getColumnIndexOrThrow("name")))
        assertEquals("ECO", c.getString(c.getColumnIndexOrThrow("drive_mode")))
        assertEquals(0, c.getInt(c.getColumnIndexOrThrow("position")))
        assertEquals("unknown_profile", p.call("apply", "Montagne", null)!!.getString("error"))
        // Retirée de la liste : elle redemandera.
        ProfileApiAccess.remove(ctx, ctx.packageName)
        assertEquals(ProfileApiAccess.Status.UNKNOWN, ProfileApiAccess.status(ctx, ctx.packageName))
    }
}
