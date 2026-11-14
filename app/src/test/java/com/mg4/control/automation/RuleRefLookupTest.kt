package com.mg4.control.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.model.RefKind
import com.mg4.control.model.RulePlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Relier par le nom (2026-11-14) : seulement un nom porté par un seul objet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RuleRefLookupTest {

    @Test
    fun `a name shared by two objects links nothing`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        RulePlaces.save(ctx, listOf(RulePlace(id = "a", name = "Maison"), RulePlace(id = "b", name = " maison "),
            RulePlace(id = "c", name = "Bureau")))
        val l = RuleRefLookup(ctx)
        assertNull("deux « Maison » : à choisir", l.byName(RefKind.PLACE, "Maison"))
        assertEquals("c", l.byName(RefKind.PLACE, "bureau"))
    }
}
