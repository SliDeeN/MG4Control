package com.mg4.control.hardware

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sonde du guidage (issue #285) : ce que le journal dit d'une mise à jour AllGo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NavGuidanceProbeTest {

    @Test
    fun `an Android Auto update is written with its text, type and arrow size`() {
        val b = Bundle().apply {
            putString("com.allgo.extra.AANAV_message", "Tournez à droite\nrue Victor Hugo")
            putInt("com.allgo.extra.AANAV_message_type", 3)
            putParcelable("com.allgo.extra.AANAV_icon", Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888))
        }
        assertEquals("AANAV_icon=image 64×48 · AANAV_message=Tournez à droite rue Victor Hugo · AANAV_message_type=3",
            NavGuidanceProbe.decrire(b))
    }

    @Test
    fun `a CarPlay update survives the trip through a parcel`() {
        val b = Bundle().apply {
            putString("DIST_TO_NEXT_JUNCTION", "300")
            putString("ROAD_AFTER_JUNCTION", "A6")
            putBoolean("IS_TBT_ACTIVE", true)
        }
        val p = Parcel.obtain()
        p.writeBundle(b)
        p.setDataPosition(0)
        val lu = p.readBundle()
        p.recycle()
        val t = NavGuidanceProbe.decrire(lu)
        assertTrue(t, t.contains("DIST_TO_NEXT_JUNCTION=300"))
        assertTrue(t, t.contains("ROAD_AFTER_JUNCTION=A6"))
        assertEquals("aucune donnée", NavGuidanceProbe.decrire(null))
    }

    @Test
    fun `the window state keeps the arrow, the texts per type, merged CarPlay keys and the counters`() {
        NavGuidanceProbe.oublier()
        val fleche = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        NavGuidanceProbe.memoriser(NavGuidanceProbe.ACTION_AA, Bundle().apply {
            putString("com.allgo.extra.AANAV_message", "350 m")
            putInt("com.allgo.extra.AANAV_message_type", 1)
            putParcelable("com.allgo.extra.AANAV_icon", fleche)
        }, "…")
        NavGuidanceProbe.memoriser(NavGuidanceProbe.ACTION_AA, Bundle().apply {
            putString("com.allgo.extra.AANAV_message", "Tournez à droite")
            putInt("com.allgo.extra.AANAV_message_type", 3)
        }, "…")
        NavGuidanceProbe.memoriser("com.allgo.action.MEDIA", Bundle(), "brut média")
        var e = NavGuidanceProbe.etat()
        assertEquals(2, e.aa)
        assertEquals(1, e.autres)
        assertEquals(NavGuidanceProbe.Source.ANDROID_AUTO, e.source)
        assertEquals("Tournez à droite", e.resume?.grand)
        assertEquals("1 : 350 m", e.resume?.ligne1)
        assertEquals(128, e.image?.width)
        assertEquals("Android Auto · type 3 · image 128×128", e.detail)
        assertEquals("com.allgo.action.MEDIA : brut média", e.brut)

        // CarPlay : mises à jour partielles fusionnées, pas d'image.
        NavGuidanceProbe.memoriser(NavGuidanceProbe.ACTION_CARPLAY, Bundle().apply { putString("DIST_TO_NEXT_JUNCTION", "300") }, "…")
        NavGuidanceProbe.memoriser(NavGuidanceProbe.ACTION_CARPLAY, Bundle().apply { putString("ROAD_AFTER_JUNCTION", "A6") }, "…")
        e = NavGuidanceProbe.etat()
        assertEquals(2, e.carplay)
        assertEquals("300", e.resume?.grand)
        assertEquals("A6", e.resume?.ligne1)
        assertEquals(null, e.image)
        NavGuidanceProbe.oublier()
    }
}
