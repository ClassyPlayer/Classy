package com.themoon.y1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FeatureVisibilityTest {
    @Test
    fun jellyfinLibrariesMapToTheirFeature() {
        assertEquals(FeatureVisibility.VIDEOS, FeatureVisibility.featureForLibrary("movies"))
        assertEquals(FeatureVisibility.VIDEOS, FeatureVisibility.featureForLibrary("TvShows"))
        assertEquals(FeatureVisibility.AUDIOBOOKS, FeatureVisibility.featureForLibrary("books"))
        assertEquals(FeatureVisibility.AUDIOBOOKS, FeatureVisibility.featureForLibrary("audiobooks"))
        assertNull(FeatureVisibility.featureForLibrary("music"))
        assertNull(FeatureVisibility.featureForLibrary(null))
    }

    @Test
    fun missingContextMeansVisible() {
        assertFalse(FeatureVisibility.isHidden(null, FeatureVisibility.VIDEOS))
        assertFalse(FeatureVisibility.skipsJellyfinLibrary(null, "movies"))
    }
}
