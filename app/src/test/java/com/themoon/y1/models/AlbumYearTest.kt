package com.themoon.y1.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumYearTest {
    @Test fun fourDigits() = assertEquals("2004", AlbumYear.of("2004"))
    @Test fun fullDateGivesTheYear() = assertEquals("2004", AlbumYear.of("2004-05-03"))
    @Test fun unknownHasNoYear() = assertNull(AlbumYear.of("Unknown Year"))
    @Test fun zeroYearIsNoYear() = assertNull(AlbumYear.of("0000")) // a tag with no year: last, and no "0000 - " label
    @Test fun labelHasTheYearFirst() = assertEquals("1999 - Album", AlbumYear.label("1999", "Album"))
    @Test fun labelWithoutYearIsTheName() = assertEquals("Album", AlbumYear.label("Unknown Year", "Album"))
}
