package com.mg4.control.profile

import androidx.test.core.app.ApplicationProvider
import com.mg4.hardware.model.DriveMode
import com.mg4.hardware.model.DrivingProfile
import com.mg4.hardware.model.RegenLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileManagerTest {

    private lateinit var manager: ProfileManager

    private fun profile(name: String, aebMode: Int = 2) = DrivingProfile(
        name = name,
        driveMode = DriveMode.NORMAL,
        regenLevel = RegenLevel.MEDIUM,
        aebMode = aebMode
    )

    @Before
    fun setUp() {
        manager = ProfileManager(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `liste vide au demarrage`() {
        assertTrue(manager.getAll().isEmpty())
    }

    @Test
    fun `save puis getById retourne le profil`() {
        val p = profile("Ville")
        assertTrue(manager.save(p))
        assertEquals("Ville", manager.getById(p.id)?.name)
    }

    @Test
    fun `save sur un id existant met a jour au lieu d ajouter`() {
        val p = profile("Ville")
        manager.save(p)
        manager.save(p.copy(name = "Autoroute"))
        assertEquals(1, manager.getAll().size)
        assertEquals("Autoroute", manager.getById(p.id)?.name)
    }

    @Test
    fun `save refuse au dela de MAX_PROFILES`() {
        repeat(ProfileManager.MAX_PROFILES) { assertTrue(manager.save(profile("P$it"))) }
        assertFalse(manager.save(profile("overflow")))
        assertEquals(ProfileManager.MAX_PROFILES, manager.getAll().size)
    }

    @Test
    fun `delete retire le profil`() {
        val p = profile("Ville")
        manager.save(p)
        manager.delete(p.id)
        assertTrue(manager.getAll().isEmpty())
    }

    @Test
    fun `delete du profil par defaut efface le defaut`() {
        val p = profile("Ville")
        manager.save(p)
        manager.setDefault(p.id)
        manager.delete(p.id)
        assertNull(manager.getDefaultId())
        assertNull(manager.getDefaultProfile())
    }

    @Test
    fun `migration aebMode zero force AEB actif en alerte plus freinage`() {
        // Profil créé avant l'ajout de l'AEB : aebMode=0 (défaut JVM), aebEnabled=false.
        manager.save(profile("Legacy", aebMode = 0).copy(aebEnabled = false))
        val migrated = manager.getAll().single()
        assertTrue(migrated.aebEnabled)
        assertEquals(2, migrated.aebMode)
    }

    @Test
    fun `migration ne touche pas les profils deja configures`() {
        manager.save(profile("Moderne", aebMode = 1).copy(aebEnabled = false))
        val kept = manager.getAll().single()
        assertFalse(kept.aebEnabled)
        assertEquals(1, kept.aebMode)
    }

    @Test
    fun `getProfileForBtDevice ignore la casse du MAC`() {
        manager.save(profile("BT").copy(btDeviceMac = "AA:BB:CC:DD:EE:FF"))
        assertEquals("BT", manager.getProfileForBtDevice("aa:bb:cc:dd:ee:ff")?.name)
        assertNull(manager.getProfileForBtDevice("11:22:33:44:55:66"))
    }
}
