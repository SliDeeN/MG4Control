package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Raccourci « Android Auto / CarPlay » (2026-10-23). */
class ProjectionTest {

    @Test fun `types AllGo 1 CarPlay 2 Android Auto`() {
        assertEquals(ProjectionKind.CARPLAY, ProjectionKind.fromType(1))
        assertEquals(ProjectionKind.ANDROID_AUTO, ProjectionKind.fromType(2))
        assertNull(ProjectionKind.fromType(0))
        assertNull(ProjectionKind.fromType(null))
    }

    @Test fun `un appui ouvre la projection du telephone connecte`() {
        assertEquals(ProjectionStep.Open(ProjectionKind.ANDROID_AUTO),
            ProjectionRule.step(ProjectionKind.ANDROID_AUTO, "com.saicmotor.hmi.launcher"))
        assertEquals(ProjectionStep.Open(ProjectionKind.CARPLAY), ProjectionRule.step(ProjectionKind.CARPLAY, null))
        assertEquals(ProjectionStep.NoPhone, ProjectionRule.step(null, "com.mg4.control"))
    }

    @Test fun `projection deja a l ecran on revient`() {
        assertEquals(ProjectionStep.Return, ProjectionRule.step(ProjectionKind.ANDROID_AUTO, "com.allgo.app.androidauto"))
        // Même si AllGo ne voit plus de session : l'écran est encore là.
        assertEquals(ProjectionStep.Return, ProjectionRule.step(null, "com.allgo.carplay.service"))
    }

    @Test fun `retour sur la tache d avant la projection`() {
        val taches = listOf(7 to "com.allgo.app.androidauto", 3 to "com.spotify.music", 1 to "com.saicmotor.hmi.launcher")
        assertEquals(3, ProjectionRule.returnTask(taches))
        assertEquals(1, ProjectionRule.returnTask(listOf(7 to "com.allgo.app.androidauto", 5 to null, 1 to "launcher")))
        assertNull(ProjectionRule.returnTask(listOf(7 to "com.allgo.carplay.service")))
    }
}
