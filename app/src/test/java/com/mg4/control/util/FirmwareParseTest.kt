package com.mg4.control.util

import com.mg4.control.util.FirmwareInfo.Gen
import org.junit.Assert.assertEquals
import org.junit.Test

/** Génération d'après la chaîne de version : préfixes SAIC et distributeurs (fork sensoyg, DBI68). */
class FirmwareParseTest {

    @Test
    fun `les firmwares SAIC sont reconnus comme avant`() {
        assertEquals(Gen.SWI133, FirmwareInfo.parseGeneration("SWI133-29176-1300R32"))
        assertEquals(Gen.SWI132, FirmwareInfo.parseGeneration("SWI132-29176-1300R32"))
        assertEquals(Gen.SWI131, FirmwareInfo.parseGeneration("SWI131-29176-1300R32"))
        assertEquals(Gen.SWI165, FirmwareInfo.parseGeneration("SWI165-29176-1300R32"))
        assertEquals(Gen.SWI68, FirmwareInfo.parseGeneration("SWI68-29176-1300R32"))
        assertEquals(Gen.SWI69, FirmwareInfo.parseGeneration("SWI69-29176-1300R32"))
    }

    @Test
    fun `le firmware turc DBI68 est un SWI68`() {
        assertEquals(Gen.SWI68, FirmwareInfo.parseGeneration("DBI68-29558-1X00RXX-20250530"))
        assertEquals(Gen.SWI133, FirmwareInfo.parseGeneration("DBI133-12345"))
        assertEquals(Gen.SWI69, FirmwareInfo.parseGeneration("dbi69-12345"))
    }

    @Test
    fun `un numero ou un prefixe inconnu reste inconnu`() {
        assertEquals(Gen.UNKNOWN, FirmwareInfo.parseGeneration(null))
        assertEquals(Gen.UNKNOWN, FirmwareInfo.parseGeneration(""))
        assertEquals(Gen.UNKNOWN, FirmwareInfo.parseGeneration("SWI1330-1"))   // pas un SWI133
        assertEquals(Gen.UNKNOWN, FirmwareInfo.parseGeneration("SWI70-1"))
        assertEquals(Gen.UNKNOWN, FirmwareInfo.parseGeneration("THI68-12345")) // préfixe non listé
        assertEquals(Gen.UNKNOWN, FirmwareInfo.parseGeneration("PPR1.180610.011"))
    }
}
