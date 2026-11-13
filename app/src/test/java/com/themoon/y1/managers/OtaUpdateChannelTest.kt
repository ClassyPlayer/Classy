package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Test

class OtaUpdateChannelTest {

    @Test
    fun android44_readsTheY2Metadata() {
        // The Y2 players with older versions of Classy read update.json: it must stay the Y2 build
        assertEquals(OtaUpdateManager.UPDATE_JSON_URL, OtaUpdateManager.updateJsonUrl(19))
        assertEquals(OtaUpdateManager.UPDATE_JSON_URL, OtaUpdateManager.updateJsonUrl(21))
    }

    @Test
    fun belowAndroid44_readsTheY1Metadata() {
        assertEquals(OtaUpdateManager.UPDATE_JSON_URL_Y1, OtaUpdateManager.updateJsonUrl(17))
        assertEquals(OtaUpdateManager.UPDATE_JSON_URL_Y1, OtaUpdateManager.updateJsonUrl(18))
    }

    @Test
    fun betaChannel_looksForTheMetadataFileOfThePlayer() {
        assertEquals("update.json", OtaUpdateManager.manifestName(19))
        assertEquals("update-y1.json", OtaUpdateManager.manifestName(17))
        // The Stable URL and the Beta file name agree
        for (sdk in listOf(17, 19)) assertEquals(true, OtaUpdateManager.updateJsonUrl(sdk).endsWith("/" + OtaUpdateManager.manifestName(sdk)))
    }
}
