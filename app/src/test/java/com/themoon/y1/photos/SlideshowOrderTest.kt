package com.themoon.y1.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class SlideshowOrderTest {

    @Test
    fun inOrderFromTheStartPhoto() {
        val s = SlideshowOrder(5, 2, shuffle = false, repeat = false)
        assertEquals(2, s.current)
        assertEquals(3, s.next())
        assertEquals(4, s.next())
        assertEquals(-1, s.next())
        // The wheel back
        assertEquals(3, s.step(-1))
    }

    @Test
    fun peekGivesTheNextSlideWithoutMovingOn() {
        val s = SlideshowOrder(3, 1, shuffle = false, repeat = true)
        assertEquals(2, s.peek())
        assertEquals(1, s.current)
        s.next()
        assertEquals(0, s.peek())
        assertEquals(-1, SlideshowOrder(3, 2, shuffle = false, repeat = false).peek())
    }

    @Test
    fun repeatStartsAgain() {
        val s = SlideshowOrder(3, 2, shuffle = false, repeat = true)
        assertEquals(0, s.next())
        assertEquals(2, s.step(-1))
    }

    @Test
    fun backFromTheFirstPhotoStaysWithoutRepeat() {
        val s = SlideshowOrder(3, 0, shuffle = false, repeat = false)
        assertEquals(0, s.step(-1))
    }

    @Test
    fun shuffleShowsEachPhotoOnceAndStartsWithTheChosenOne() {
        val s = SlideshowOrder(10, 7, shuffle = true, repeat = false, random = Random(1))
        val seen = mutableListOf(s.current)
        while (true) {
            val n = s.next()
            if (n < 0) break
            seen += n
        }
        assertEquals(7, seen.first())
        assertEquals((0 until 10).toSet(), seen.toSet())
        assertEquals(10, seen.size)
    }

    @Test
    fun aNewShuffledRoundDoesNotRepeatTheLastPhoto() {
        repeat(20) { seed ->
            val s = SlideshowOrder(4, 0, shuffle = true, repeat = true, random = Random(seed.toLong()))
            repeat(3) { s.next() }
            val last = s.current
            assertFalse(s.next() == last)
        }
    }

    @Test
    fun noPhotos() {
        val s = SlideshowOrder(0, 0, shuffle = true, repeat = true)
        assertEquals(-1, s.current)
        assertEquals(-1, s.next())
        assertTrue(SlideshowOrder(1, 0, shuffle = true, repeat = true).next() == 0)
    }
}
