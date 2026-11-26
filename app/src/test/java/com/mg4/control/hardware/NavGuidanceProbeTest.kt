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
}
