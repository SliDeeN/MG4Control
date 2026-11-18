package com.mg4.control.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaicHvacPanelTest {
    private val visible = """
          Window #0 Window{cd742c u0 hvac page}:
            mDisplayId=0 mSession=Session{fd831c 1634:1000} mClient=android.os.BinderProxy@1d52c
            mOwnerUid=1000 mShowToOwnerOnly=false package=com.android.systemui appop=SYSTEM_ALERT_WINDOW
            mViewVisibility=0x0 mHaveFrame=true mObscured=false
            mHasSurface=true isReadyForDisplay()=true mWindowRemovalAllowed=false
            mFrame=[0,-10][1920,422] last=[0,-30][1920,402]
    """.trimIndent()

    @Test fun `le panneau SystemUI a une surface a l ecran`() {
        assertTrue(SaicHvacPanel.estVisible(visible))
    }

    @Test fun `une vue attachee hors ecran ou masquee ne suffit pas`() {
        assertFalse(SaicHvacPanel.estVisible(visible.replace("[0,-10][1920,422]", "[0,-432][1920,0]")))
        assertFalse(SaicHvacPanel.estVisible(visible.replace("mViewVisibility=0x0", "mViewVisibility=0x8")))
        assertFalse(SaicHvacPanel.estVisible(visible.replace("mObscured=false", "mObscured=true")))
        assertFalse(SaicHvacPanel.estVisible(visible.replace("mHasSurface=true", "mHasSurface=false")))
    }

    @Test fun `le titre doit correspondre au panneau sur l ecran principal`() {
        assertFalse(SaicHvacPanel.estVisible(visible.replace("hvac page", "mini hvac page")))
        assertFalse(SaicHvacPanel.estVisible(visible.replace("package=com.android.systemui", "package=autre.app")))
        assertFalse(SaicHvacPanel.estVisible(visible.replace("mDisplayId=0", "mDisplayId=1")))
    }

    @Test fun `un autre bloc ne fournit pas les champs manquants du panneau`() {
        val sansSurface = visible.replace("mHasSurface=true", "mHasSurface=false")
        val autre = visible.replace("Window #0", "Window #1").replace("hvac page", "StatusBar")
        assertFalse(SaicHvacPanel.estVisible("$sansSurface\n$autre"))
        assertFalse(SaicHvacPanel.estVisible("Permission Denial"))
    }

    @Test fun `une tentative par ouverture meme si une lecture echoue`() {
        val ouvertures = SaicHvacPanel.Ouvertures()
        assertFalse(ouvertures.nouvelle(false))
        assertTrue(ouvertures.nouvelle(true))
        assertFalse(ouvertures.nouvelle(null))
        assertFalse(ouvertures.nouvelle(true))
        assertFalse(ouvertures.nouvelle(false))
        assertTrue(ouvertures.nouvelle(true))
    }
}
