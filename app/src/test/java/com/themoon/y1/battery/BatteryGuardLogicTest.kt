package com.themoon.y1.battery

import com.themoon.y1.battery.BatteryGuardLogic.Load
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryGuardLogicTest {
    private val screen = Load(screenOn = true, cpuPct = 25, wifi = true)
    private val min = 60_000L

    /** Feeds one sample each 30 s from [from] to [to] (exclusive) with the voltage [mV]. Returns the next time. */
    private fun run(g: BatteryGuardLogic, from: Long, to: Long, mV: Int, gauge: Int = 80, load: Load = screen): Long {
        var t = from
        while (t < to) {
            g.onSample(t, mV, gauge, false, load)
            t += 30_000L
        }
        return t
    }

    @Test
    fun theTableGoesFromTheCutOffToFull() {
        assertEquals(0f, BatteryGuardLogic.percentAt(3400f), 0f)
        assertEquals(10f, BatteryGuardLogic.percentAt(3512f), 0f)
        assertEquals(100f, BatteryGuardLogic.percentAt(4250f), 0f)
        var last = -1f
        for (mV in 3300..4300 step 5) {
            val p = BatteryGuardLogic.percentAt(mV.toFloat())
            assertTrue("not monotonic at $mV", p >= last)
            last = p
        }
    }

    @Test
    fun theLoadAllowanceIsZeroAtTheLoadOfTheTable() {
        assertEquals(BatteryGuardLogic.REFERENCE_SAG_MV, BatteryGuardLogic.sagMv(screen))
        // Screen off, music, no Wi-Fi: a light load, so the same voltage means less charge.
        assertTrue(BatteryGuardLogic.sagMv(Load(screenOn = false, playing = true)) < BatteryGuardLogic.REFERENCE_SAG_MV)
        // A Wi-Fi sync with the CPU at 100 %: the sag is added back.
        assertTrue(BatteryGuardLogic.sagMv(Load(screenOn = true, cpuPct = 100, wifi = true)) > BatteryGuardLogic.REFERENCE_SAG_MV)
    }

    @Test
    fun chargingShowsTheGauge() {
        val g = BatteryGuardLogic()
        g.onSample(0, 4100, 57, true, screen)
        assertEquals(57, g.shown)
        g.onSample(30_000, 3500, 58, true, screen)
        assertEquals(58, g.shown)
    }

    @Test
    fun theUnplugAndTheFirstStartStepDownFromTheGauge() {
        // The first start of the app while discharging (no saved level) starts from the gauge.
        val first = BatteryGuardLogic()
        first.onSample(0, 3700, 96, false, screen)
        assertEquals(96, first.shown)
        val g = BatteryGuardLogic()
        g.onSample(0, 4200, 90, true, screen)
        // Unplugged: the kernel's mean still has the charging voltage in it, so the level stays at the gauge.
        var t = run(g, 30_000, 30_000 + BatteryGuardLogic.LOAD_MEAN_MS, 4200, gauge = 90)
        assertEquals(90, g.shown)
        // Then the level falls by 1 % each 30 s towards the estimate (~32 %): no jump.
        var prev = g.shown
        while (t < 90 * min) {
            g.onSample(t, 3700, 90, false, screen)
            assertTrue("jump at ${t / 1000} s", prev - g.shown in 0..1)
            prev = g.shown
            t += 30_000L
        }
        assertEquals(Math.round(BatteryGuardLogic.percentAt(3700f)), g.shown)
    }

    @Test
    fun theLevelNeverGoesUpWhileDischarging() {
        val g = BatteryGuardLogic()
        g.restore(40)
        var t = run(g, 0, 30 * min, 3720)
        val low = g.shown
        // The load stops and the voltage comes back up: no change.
        t = run(g, t, t + 30 * min, 3900, load = Load(screenOn = false))
        assertEquals(low, g.shown)
        // The gauge falls below the shown level: the voltage model still decides.
        run(g, t, t + 10 * min, 3900, gauge = 5)
        assertEquals(low, g.shown)
    }

    @Test
    fun aShortSagDoesNotLowerTheLevel() {
        val g = BatteryGuardLogic()
        g.restore(40)
        var t = run(g, 0, 10 * min, 3770)
        assertEquals(40, g.shown)
        // A Wi-Fi burst: 90 s at a lower voltage.
        t = run(g, t, t + 90_000, 3600)
        t = run(g, t, t + 10 * min, 3770)
        assertEquals(40, g.shown)
    }

    @Test
    fun oneSampleAfterALongSleepWaitsForTheHoldTime() {
        val g = BatteryGuardLogic()
        g.restore(40)
        g.onSample(0, 3770, 80, false, screen)
        g.onSample(60 * min, 3600, 80, false, screen)
        assertEquals(40, g.shown)
        g.onSample(60 * min + BatteryGuardLogic.HOLD_MS, 3600, 80, false, screen)
        // 2 min since the sample before (the CPU slept): 4 steps of 30 s.
        assertEquals(36, g.shown)
    }

    /**
     * 2 hours at a steady drain (3.90 → 3.80 V at the load of the curve), the screen on for [onMin] min and off (music)
     * for [offMin] min, again and again. The kernel's voltage moves to the voltage of the new load in 5 min. The level
     * must fall smoothly and as much as the curve: not more because of the screen changes, and with no freeze.
     */
    private fun assertFollowsTheCurve(onMin: Long, offMin: Long, maxError: Float) {
        val on = Load(screenOn = true, cpuPct = 25, wifi = true)
        val off = Load(screenOn = false, playing = true, cpuPct = 10, wifi = true)
        // At a steady load the equivalent voltage is the voltage of the curve's load.
        val offRise = BatteryGuardLogic.REFERENCE_SAG_MV - BatteryGuardLogic.sagMv(off)
        fun ref(t: Long) = 3900f - 100f * t / (120 * min)
        val g = BatteryGuardLogic()
        val start = Math.round(BatteryGuardLogic.percentAt(ref(0)))
        g.restore(start)
        var offset = 0f
        var prev = start
        var t = 0L
        while (t <= 120 * min) {
            val screenOn = (t / min) % (onMin + offMin) < onMin
            val target = if (screenOn) 0f else offRise.toFloat()
            // The kernel's mean: 1/10 of the way each 30 s step, so ~5 min to the new value.
            val step = offRise / 10f
            offset = if (offset < target) minOf(target, offset + step) else maxOf(target, offset - step)
            g.onSample(t, Math.round(ref(t) + offset), 90, false, if (screenOn) on else off)
            assertTrue("went up at ${t / min} min", g.shown <= prev)
            assertTrue("jump at ${t / min} min", prev - g.shown <= 1)
            prev = g.shown
            t += 30_000L
        }
        val modelFall = BatteryGuardLogic.percentAt(ref(0)) - BatteryGuardLogic.percentAt(ref(120 * min))
        val fall = start - g.shown
        assertTrue("fall $fall, model $modelFall", Math.abs(fall - modelFall) <= maxError)
    }

    @Test
    fun screenOnOffChangesDoNotPushTheLevelDown() = assertFollowsTheCurve(onMin = 8, offMin = 12, maxError = 2f)

    /** The load changes faster than the kernel's mean settles: the level still falls (it must not freeze). */
    @Test
    fun fastLoadChangesDoNotFreezeTheLevel() {
        assertFollowsTheCurve(onMin = 2, offMin = 2, maxError = 6f)
        // The CPU crosses 60 % each 2 min: a small load change, so a small error.
        val g = BatteryGuardLogic()
        g.restore(Math.round(BatteryGuardLogic.percentAt(3900f)))
        var t = 0L
        while (t <= 120 * min) {
            val cpu = if ((t / (2 * min)) % 2 == 0L) 70 else 30
            g.onSample(t, Math.round(3900f - 100f * t / (120 * min)), 90, false, Load(screenOn = true, cpuPct = cpu, wifi = true))
            t += 30_000L
        }
        val expected = BatteryGuardLogic.percentAt(3800f)
        assertTrue("shown ${g.shown}, curve $expected", Math.abs(g.shown - expected) <= 3f)
    }

    /**
     * The bug of a sample at a load change, after a sleep: the screen goes off with the voltage low from the screen-on
     * load, and the CPU sleeps, so the next samples have the old voltage at the light load. The level must not fall.
     */
    @Test
    fun anOldVoltageAtALighterLoadDoesNotPushTheLevelDown() {
        val light = Load(screenOn = false, playing = true, cpuPct = 10, wifi = true)
        val g = BatteryGuardLogic()
        g.restore(Math.round(BatteryGuardLogic.percentAt(3800f)))
        val start = g.shown
        var t = run(g, 0, 20 * min, 3800)
        t += 31 * min
        g.onSample(t, 3800, 90, false, light)
        t += 20 * min
        g.onSample(t, 3800, 90, false, light)
        assertEquals(start, g.shown)
    }

    @Test
    fun badVoltagesAreIgnored() {
        val g = BatteryGuardLogic()
        g.restore(50)
        run(g, 0, 20 * min, 0)
        assertEquals(50, g.shown)
    }
}
