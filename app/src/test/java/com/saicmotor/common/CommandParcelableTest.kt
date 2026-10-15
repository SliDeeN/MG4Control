package com.saicmotor.common

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Le contrat avec l'appli Média SAIC des A9 : elle relit la commande avec sa propre classe, du
 * même nom, dans l'ordre int, String, int, int, Bundle (constructeur décompilé, SWI69).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommandParcelableTest {

    @Test
    fun `les champs partent dans l ordre relu par l appli Media`() {
        val p = Parcel.obtain()
        try {
            CommandParcelable(0x8001, 2, "com.saicmotor.launcher").writeToParcel(p, 0)
            p.setDataPosition(0)
            assertEquals(0x8001, p.readInt())                       // event
            assertEquals("com.saicmotor.launcher", p.readString())  // targetPackage
            assertEquals(2, p.readInt())                            // targetType
            assertEquals(-1, p.readInt())                           // targetState, par défaut
            assertNotNull(p.readBundle(javaClass.classLoader))      // param
            assertEquals(0, p.dataAvail())
        } finally {
            p.recycle()
        }
    }

    @Test
    fun `aller retour par le CREATOR`() {
        val p = Parcel.obtain()
        try {
            CommandParcelable(0x8006, 3, "x", 7).writeToParcel(p, 0)
            p.setDataPosition(0)
            val lu = CommandParcelable.CREATOR.createFromParcel(p)
            assertEquals(0x8006, lu.event)
            assertEquals(3, lu.targetType)
            assertEquals("x", lu.targetPackage)
            assertEquals(7, lu.targetState)
        } finally {
            p.recycle()
        }
    }

    @Test
    fun `le nom complet de la classe est celui de SAIC`() {
        assertEquals("com.saicmotor.common.CommandParcelable", CommandParcelable::class.java.name)
    }
}
