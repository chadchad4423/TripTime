package com.chad.triptime.model

import kotlin.math.abs
import kotlin.math.log10
import kotlin.random.Random

/**
 * A thing of known length, used by [DistanceUnit.SILLY] to express a trip in units no
 * reasonable person would choose — the running joke that Americans will measure anything in
 * anything rather than in kilometres.
 *
 * Every entry is measured **along its length**, never its height: "3,639 Empire State
 * Buildings" would be measuring a vertical thing, which reads as a different kind of claim.
 * Lengths are real (the one deliberate guess is the Walmart parking lot) but precision is
 * beside the point — being in the right order of magnitude is what makes the number land.
 */
data class SillyUnit(val name: String, val meters: Double)

object SillyUnits {

    /**
     * Ordered short to long. Nothing depends on the ordering — [pick] filters by the count each
     * one would produce — but keeping it sorted makes the coverage gaps obvious when editing.
     */
    val ALL: List<SillyUnit> = listOf(
        SillyUnit("Ram 2500s", 6.3),
        SillyUnit("school buses", 12.2),
        SillyUnit("T. rexes", 12.3),
        SillyUnit("bowling lanes", 18.3),
        SillyUnit("semi trucks", 22.0),
        SillyUnit("blue whales", 25.0),
        SillyUnit("basketball courts", 28.7),
        SillyUnit("Olympic pools", 50.0),
        SillyUnit("hockey rinks", 61.0),
        SillyUnit("Boeing 747s", 76.3),
        SillyUnit("football fields", 91.44),
        SillyUnit("soccer pitches", 105.0),
        SillyUnit("Walmart parking lots", 200.0),
        SillyUnit("Hindenburgs", 245.0),
        SillyUnit("Titanics", 269.0),
        SillyUnit("Great Lakes freighters", 305.0),
        SillyUnit("aircraft carriers", 333.0),
        SillyUnit("container ships", 400.0),
        SillyUnit("Brooklyn Bridges", 1_825.0),
        SillyUnit("Kentucky Derbys", 2_012.0),
        SillyUnit("Golden Gate Bridges", 2_737.0),
        SillyUnit("Las Vegas Strips", 6_800.0),
        SillyUnit("Seven Mile Bridges", 10_900.0),
        SillyUnit("Nürburgring laps", 20_832.0),
        SillyUnit("English Channels", 33_300.0),
        SillyUnit("marathons", 42_195.0),
        SillyUnit("Panama Canals", 82_000.0),
        SillyUnit("trips to space", 100_000.0),
        SillyUnit("Suez Canals", 193_000.0),
        SillyUnit("Grand Canyons", 446_000.0),
    )

    /** Counts below this read as too few to be funny ("3.6 Grand Canyons"). */
    private const val MIN_COUNT = 10.0

    /** Counts above this stop being a number and start being noise. */
    private const val MAX_COUNT = 100_000.0

    /**
     * Chooses a unit at random from those that would give a count between [MIN_COUNT] and
     * [MAX_COUNT] for [distanceMeters]. That band is what makes long trips land on long things
     * and short trips on shorter ones, without needing hand-maintained distance tiers.
     *
     * Callers should call this **once per calculated trip** and hold on to the result: re-rolling
     * on every recomposition would make the text change under the reader, and on e-ink every
     * such change is a visible repaint.
     */
    fun pick(distanceMeters: Double, random: Random = Random.Default): SillyUnit {
        val candidates = ALL.filter {
            val count = distanceMeters / it.meters
            count >= MIN_COUNT && count <= MAX_COUNT
        }
        if (candidates.isNotEmpty()) return candidates.random(random)

        // Nothing fits the band — a trip either shorter than ~63 m or longer than ~44,600 km.
        // Fall back to whichever unit lands closest to the middle of the band on a log scale,
        // so the result is still the least-bad choice rather than an arbitrary one.
        val target = log10(MIN_COUNT * MAX_COUNT) / 2.0
        return ALL.minBy { abs(log10(distanceMeters / it.meters) - target) }
    }
}
