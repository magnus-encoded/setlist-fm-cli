package io.github.magnusencoded.stationtostation

import io.github.magnusencoded.stationtostation.ui.flyover.CoverZ
import io.github.magnusencoded.stationtostation.ui.flyover.FarCull
import io.github.magnusencoded.stationtostation.ui.flyover.FlyoverItem
import io.github.magnusencoded.stationtostation.ui.flyover.FlankX
import io.github.magnusencoded.stationtostation.ui.flyover.PassTilt
import io.github.magnusencoded.stationtostation.ui.flyover.RestTilt
import io.github.magnusencoded.stationtostation.ui.flyover.SlideIn
import io.github.magnusencoded.stationtostation.ui.flyover.SlideSpread
import io.github.magnusencoded.stationtostation.ui.flyover.TurnEnd
import io.github.magnusencoded.stationtostation.ui.flyover.TurnSpread
import io.github.magnusencoded.stationtostation.ui.flyover.flankOffset
import io.github.magnusencoded.stationtostation.ui.flyover.flankOpacity
import io.github.magnusencoded.stationtostation.ui.flyover.flankVisible
import io.github.magnusencoded.stationtostation.ui.flyover.flankStep
import io.github.magnusencoded.stationtostation.ui.flyover.flankTilt
import io.github.magnusencoded.stationtostation.ui.flyover.FocalLength
import io.github.magnusencoded.stationtostation.ui.flyover.FocalPlane
import io.github.magnusencoded.stationtostation.ui.flyover.MinGap
import io.github.magnusencoded.stationtostation.ui.flyover.NearCull
import io.github.magnusencoded.stationtostation.ui.flyover.PlacedItem
import io.github.magnusencoded.stationtostation.ui.flyover.WallStopMax
import io.github.magnusencoded.stationtostation.ui.flyover.WallStopMin
import io.github.magnusencoded.stationtostation.ui.flyover.contentEnd
import io.github.magnusencoded.stationtostation.ui.flyover.floorLineX
import io.github.magnusencoded.stationtostation.ui.flyover.focalPick
import io.github.magnusencoded.stationtostation.ui.flyover.net
import io.github.magnusencoded.stationtostation.ui.flyover.opacity
import io.github.magnusencoded.stationtostation.ui.flyover.placeMedia
import io.github.magnusencoded.stationtostation.ui.flyover.projectedScale
import io.github.magnusencoded.stationtostation.ui.flyover.travelGain
import io.github.magnusencoded.stationtostation.ui.flyover.travelRange
import io.github.magnusencoded.stationtostation.ui.flyover.visible
import io.github.magnusencoded.stationtostation.ui.flyover.wallStop
import io.github.magnusencoded.stationtostation.ui.flyover.wallZ
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The **Flyover**'s arithmetic (#278) — everything about the landscape walk that can
 * be settled without a device, which is everything except how it looks.
 *
 * The two that earn their keep: **chronology beating density**, which the prototype's
 * own data got wrong until the second constraint was added, and the **Wall**'s stop
 * distance, which is the open question about a long note answered as a function
 * rather than as a constant.
 */
class FlyoverGeometryTest {

    private fun mine(id: String, at: Long?) = FlyoverItem(id, mine = true, capturedAt = at)
    private fun theirs(id: String, at: Long?) = FlyoverItem(id, mine = false, capturedAt = at)

    private fun zOf(placed: List<PlacedItem>, id: String) = placed.first { it.id == id }.z

    // --- Placement ---------------------------------------------------------

    /**
     * The range is the night's own, so a night whose first photograph is an hour in
     * doesn't leave an hour of empty spine ahead of it.
     */
    @Test
    fun `the first photograph stands near the start whatever time it was taken`() {
        val early = placeMedia(listOf(mine("a", 0L), mine("b", 1_000L)), songCount = 12)
        val late = placeMedia(listOf(mine("a", 9_000_000L), mine("b", 9_001_000L)), songCount = 12)
        assertEquals(zOf(early, "a"), zOf(late, "a"), 0.001)
        assertEquals(zOf(early, "b"), zOf(late, "b"), 0.001)
    }

    /** Two photographs on the same flank never stand closer than a full gap. */
    @Test
    fun `a burst on one flank stretches instead of stacking`() {
        val placed = placeMedia(
            listOf(mine("a", 0L), mine("b", 900L), mine("c", 901L), mine("d", 902L)),
            songCount = 12,
        )
        val zs = placed.map { it.z }
        zs.zipWithNext { lower, upper ->
            assertTrue("same flank must keep a full gap", upper - lower >= MinGap - 0.001)
        }
    }

    /**
     * The regression the prototype measured: spacing per flank alone lets a one-sided
     * burst push its neighbour past photographs taken *after* it. Opposite flanks do
     * not occlude, so they need order — but not distance.
     */
    @Test
    fun `a burst never pushes a later photograph on the other flank in front of it`() {
        val placed = placeMedia(
            listOf(
                mine("first", 0L),
                mine("burst1", 900L),
                mine("burst2", 901L),
                mine("burst3", 902L),
                theirs("after", 903L),
            ),
            songCount = 12,
        )
        assertTrue(
            "a photograph taken last must not stand in front of the burst before it",
            zOf(placed, "after") >= zOf(placed, "burst3"),
        )
    }

    /** Opposite flanks need order, not distance: they may stand level. */
    @Test
    fun `opposite flanks may stand level`() {
        val placed = placeMedia(listOf(mine("a", 500L), theirs("b", 500L)), songCount = 12)
        val gap = zOf(placed, "b") - zOf(placed, "a")
        assertTrue(gap >= 0.0)
        assertTrue("opposite flanks do not occlude, so they need no gap", gap < MinGap)
    }

    /**
     * A capture time is nullable because it is sometimes unknowable, and nothing may
     * invent one. An undated photograph goes after everything the record does know
     * the time of — the same answer `bandsOf` gives when it sorts received media.
     */
    @Test
    fun `a photograph with no capture time sorts last and never displaces a dated one`() {
        val placed = placeMedia(
            listOf(mine("undated", null), mine("early", 0L), mine("late", 5_000L)),
            songCount = 12,
        )
        assertTrue(zOf(placed, "early") < zOf(placed, "late"))
        assertTrue(zOf(placed, "undated") > zOf(placed, "late"))
    }

    /** Two undated photographs keep the order they were stored in. */
    @Test
    fun `undated photographs keep their stored order`() {
        val placed = placeMedia(
            listOf(mine("second", null), mine("first", 0L), mine("third", null)),
            songCount = 12,
        )
        assertTrue(zOf(placed, "second") < zOf(placed, "third"))
    }

    @Test
    fun `a night with one photograph places it, and an empty one places nothing`() {
        assertEquals(1, placeMedia(listOf(mine("only", 42L)), songCount = 12).size)
        assertTrue(placeMedia(emptyList(), songCount = 12).isEmpty())
    }

    /** A night with no setlist at all still has somewhere to put its photographs. */
    @Test
    fun `no songs is not a division by zero`() {
        val placed = placeMedia(listOf(mine("a", 0L), mine("b", 10L)), songCount = 0)
        assertTrue(placed.all { it.z.isFinite() })
        assertTrue(zOf(placed, "b") > zOf(placed, "a"))
    }

    // --- The wall ----------------------------------------------------------

    /**
     * One terminus per ending, and you never reach it early: the wall has to stand
     * clear of the night by more than the cull distance, or the last photographs are
     * still streaming past you when you arrive — and the encore burst lands exactly
     * there.
     */
    @Test
    fun `the wall stands clear of the last photograph by more than the cull distance`() {
        val placed = placeMedia(
            listOf(mine("a", 0L), mine("b", 900L), mine("c", 901L), theirs("d", 902L)),
            songCount = 12,
        )
        val clear = wallZ(placed, 12) - contentEnd(placed, 12)
        assertTrue("the wall must be further than $NearCull away", clear > NearCull)
    }

    /** A burst past the last song moves the wall out with it. */
    @Test
    fun `content that runs past the last song pushes the wall back`() {
        val quiet = placeMedia(listOf(mine("a", 0L)), songCount = 12)
        val busy = placeMedia(
            (0..20).map { mine("m$it", 900L + it) },
            songCount = 12,
        )
        assertTrue(wallZ(busy, 12) > wallZ(quiet, 12))
    }

    /** A night nobody wrote about is met at billboard distance. */
    @Test
    fun `a short wall is met from billboard distance`() {
        assertEquals(WallStopMin, wallStop(wallHeight = 200.0, frameHeight = 400.0), 0.001)
    }

    /** A long note grows the wall upward, so the stop distance steps back to fit it. */
    @Test
    fun `a taller wall is met from further back`() {
        val short = wallStop(wallHeight = 300.0, frameHeight = 400.0)
        val tall = wallStop(wallHeight = 450.0, frameHeight = 400.0)
        assertTrue(tall > short)
        // And having stepped back, the whole wall is actually in frame.
        assertTrue(450.0 * projectedScale(-tall) <= 400.0 * 0.87)
    }

    /** Stepping back is what makes the text small, so it stops. */
    @Test
    fun `an essay does not walk you off the horizon`() {
        assertEquals(WallStopMax, wallStop(wallHeight = 4_000.0, frameHeight = 400.0), 0.001)
    }

    @Test
    fun `an unmeasured wall falls back to billboard distance`() {
        assertEquals(WallStopMin, wallStop(wallHeight = 0.0, frameHeight = 400.0), 0.001)
        assertEquals(WallStopMin, wallStop(wallHeight = 300.0, frameHeight = 0.0), 0.001)
    }

    // --- The camera --------------------------------------------------------

    @Test
    fun `the screen plane draws at authored size and the focal plane half again`() {
        assertEquals(1.0, projectedScale(0.0), 0.001)
        assertEquals(1.5, projectedScale(FocalPlane), 0.001)
    }

    @Test
    fun `nothing drawn ever reaches the lens`() {
        assertTrue(projectedScale(NearCull).isFinite())
        // Even asked for something it should never be asked for.
        assertTrue(projectedScale(FocalLength).isFinite())
        assertTrue(projectedScale(FocalLength + 500.0).isFinite())
    }

    @Test
    fun `what you have gone through and what you have not reached are not drawn`() {
        assertTrue(visible(0.0))
        assertFalse(visible(NearCull + 1))
        assertFalse(visible(-FarCull - 1))
        assertEquals(0f, opacity(NearCull + 1), 0.001f)
        assertEquals(0f, opacity(-FarCull - 1), 0.001f)
    }

    @Test
    fun `both ends taper rather than popping`() {
        assertEquals(1f, opacity(0.0), 0.001f)
        assertEquals(0f, opacity(NearCull), 0.001f)
        assertEquals(0f, opacity(-FarCull), 0.001f)
        assertTrue(opacity(NearCull - 100.0) in 0.01f..0.99f)
        assertTrue(opacity(-FarCull + 300.0) in 0.01f..0.99f)
    }

    // --- Travel and selection ---------------------------------------------

    @Test
    fun `the walk begins at the cover and stops short of the wall`() {
        val placed = placeMedia(listOf(mine("a", 0L)), songCount = 12)
        val wall = wallZ(placed, 12)
        val range = travelRange(wall, WallStopMin)
        assertEquals(CoverZ - WallStopMin, range.start, 0.001)
        assertEquals(wall - WallStopMin, range.endInclusive, 0.001)
        assertTrue("you never pass the wall", range.endInclusive < wall)
    }

    /** Travel already implies selection: the lit photograph is the one you are at. */
    @Test
    fun `each half takes whatever stands nearest the focal plane on that flank`() {
        val placed = placeMedia(
            listOf(mine("m1", 0L), theirs("t1", 10L), mine("m2", 5_000L), theirs("t2", 5_010L)),
            songCount = 12,
        )
        val atFirst = zOf(placed, "m1") + FocalPlane
        assertEquals("m1", focalPick(placed, atFirst, mine = true))
        assertEquals("t1", focalPick(placed, atFirst, mine = false))

        val atLast = zOf(placed, "m2") + FocalPlane
        assertEquals("m2", focalPick(placed, atLast, mine = true))
    }

    /** A tap must never open a photograph that is not on screen. */
    @Test
    fun `a flank with nothing in view gives nothing`() {
        val placed = placeMedia(listOf(mine("m1", 0L)), songCount = 12)
        assertNull("the other flank is empty", focalPick(placed, 0.0, mine = false))
        // Far past the only photograph there is: it has been culled, so it is not
        // something a thumb can still reach for.
        assertNull(focalPick(placed, zOf(placed, "m1") + NearCull + 10, mine = true))
    }

    /** Which flank is the one bit travel cannot resolve, so the flanks never mix. */
    @Test
    fun `one flank never picks the other's photograph`() {
        val placed = placeMedia(
            listOf(mine("m", 0L), theirs("t", 1L), mine("later", 100L)),
            songCount = 12,
        )
        // Standing where theirs is exactly at the plane: the other flank still answers
        // with its own nearest, and not with the one under the thumb.
        val at = zOf(placed, "t") + FocalPlane
        assertEquals("t", focalPick(placed, at, mine = false))
        assertEquals("m", focalPick(placed, at, mine = true))
    }

    // --- The step and the turn ----------------------------------------------

    /** Fully out of the rank exactly where a tap would take it. The step *is* the
     *  affordance: nearest the spine means yours. */
    @Test
    fun `the photograph at the plane stands furthest into the aisle`() {
        assertEquals(1.0, flankStep(FocalPlane), 0.001)
        assertEquals(FlankX - SlideIn, flankOffset(FocalPlane), 0.001)
    }

    /** Out at the wall while it is still someone else's turn, at either end. */
    @Test
    fun `a photograph the walk has not reached stands at the wall`() {
        assertEquals(0.0, flankStep(FocalPlane - SlideSpread), 0.001)
        assertEquals(FlankX, flankOffset(FocalPlane - SlideSpread), 0.001)
        assertEquals("and back at it once past", 0.0, flankStep(FocalPlane + TurnSpread), 0.001)
        assertEquals(FlankX, flankOffset(FocalPlane + TurnSpread), 0.001)
    }

    /** The step reads as an approach, not as a card snapping sideways: it is under way
     *  a couple of gaps out and never reverses on the way in. */
    @Test
    fun `the step comes on gradually`() {
        var previous = -1.0
        var n = FocalPlane - SlideSpread
        while (n <= FocalPlane) {
            val step = flankStep(n)
            assertTrue("never backwards on the way in", step >= previous)
            previous = step
            n += MinGap / 2
        }
        assertTrue("already begun two gaps out", flankStep(FocalPlane - 2 * MinGap) > 0.2)
        assertTrue("and well under way one gap out", flankStep(FocalPlane - MinGap) > 0.5)
    }

    /**
     * **Slide first, turn after.** All the way in it is at rest — turning on approach
     * as well would make the two halves of the movement say the same thing twice.
     */
    @Test
    fun `nothing turns before the plane`() {
        assertEquals(RestTilt, flankTilt(FocalPlane), 0.001)
        assertEquals(RestTilt, flankTilt(FocalPlane - MinGap), 0.001)
        assertEquals(RestTilt, flankTilt(-FarCull), 0.001)
    }

    /**
     * **The rank stands square to the walker.** Nothing is skewed until it has been
     * passed, so the whole approach is a picket of face-on photographs receding, and
     * the only thing out of line is the one a tap would take.
     */
    @Test
    fun `nothing in the rank is turned at all`() {
        assertEquals(0.0, RestTilt, 0.001)
    }

    /**
     * **Exactly one photograph is ever mid-turn.** The card between the walker and the
     * pick is the one that can bury it — it is nearer, so larger, and drawn on top —
     * and a turn spread over two gaps leaves two of them large, half-round and solid
     * in front of the very thing the step exists to expose.
     */
    @Test
    fun `only one photograph is caught mid-turn`() {
        assertTrue("no wider than the tightest packing", TurnSpread <= MinGap)
        assertEquals("so the next one along is already flat", PassTilt, flankTilt(FocalPlane + MinGap), 0.001)
    }

    /** Past the plane it turns parallel to the walk and goes by as a panel. */
    @Test
    fun `the turn is the departure`() {
        assertEquals(PassTilt, flankTilt(FocalPlane + TurnSpread), 0.001)
        val half = RestTilt + (PassTilt - RestTilt) / 2
        assertEquals(half, flankTilt(FocalPlane + TurnSpread / 2), 0.001)
    }

    /**
     * **Parallel is the exit.** A photograph is gone the instant it reaches [PassTilt],
     * because carrying on past it is a card turning inside out — showing its back edge
     * and reading as swinging through the walker.
     */
    @Test
    fun `a photograph is gone the moment it turns parallel`() {
        assertEquals(PassTilt, flankTilt(TurnEnd), 0.001)
        assertEquals(0f, flankOpacity(TurnEnd), 0.001f)
        assertTrue(!flankVisible(TurnEnd + 1))
    }

    /** And solid up to the plane, so the fade is the departure and not a haze over the
     *  whole walk. */
    @Test
    fun `a photograph is solid until it has been passed`() {
        assertEquals(opacity(FocalPlane), flankOpacity(FocalPlane), 0.001f)
        assertTrue("still worth looking at halfway round", flankOpacity(FocalPlane + TurnSpread / 2) > 0.5f)
    }

    /**
     * A tap must never open a photograph that is not on screen, and the flank's window
     * closes long before the shared one does.
     */
    @Test
    fun `the pick cannot be something that has already gone`() {
        val past = PlacedItem("past", mine = true, z = 0.0)
        val ahead = PlacedItem("ahead", mine = true, z = 1000.0)
        // Travel that puts `past` beyond the turn and `ahead` still approaching.
        val at = TurnEnd + MinGap
        assertEquals("ahead", focalPick(listOf(past, ahead), at, mine = true))
    }

    /**
     * The one the walk buries is the one it must not. With a fixed angle and offset the
     * photographs between the walker and the plane are nearer, so larger, and drawn on
     * top. Stepping in makes the pick the one closest to the spine, and everything in
     * front of it is both further out and further round.
     */
    @Test
    fun `nothing in front of the pick stands closer in or straighter`() {
        val pickOffset = flankOffset(FocalPlane)
        val pickTilt = flankTilt(FocalPlane)
        var n = FocalPlane + MinGap
        while (n <= TurnEnd) {
            assertTrue("further out than the pick", flankOffset(n) > pickOffset)
            assertTrue("and further round", flankTilt(n) > pickTilt)
            n += MinGap
        }
    }

    /**
     * Gotcha 9: a long night stays crossable without shrinking the gap that keeps a
     * burst pickable, and without capping the night at some number of photographs.
     */
    @Test
    fun `a longer night walks faster, up to a point`() {
        val ordinary = travelGain(3_600.0)
        val long = travelGain(30_000.0)
        assertTrue(long > ordinary)
        assertEquals("and never faster than that", long, travelGain(300_000.0), 0.001)
        assertEquals("a short night is not slowed down", ordinary, travelGain(500.0), 0.001)
    }

    // --- The floor ---------------------------------------------------------

    @Test
    fun `floor lines tighten rather than spreading off the phone`() {
        val three = (0..2).map { floorLineX(it, 3) }
        val twelve = (0..11).map { floorLineX(it, 12) }
        assertTrue("three fit at full spacing", three[1] - three[0] > twelve[1] - twelve[0])
        assertTrue("and twelve still fit on the phone", twelve.last() - twelve.first() <= 184.0)
    }

    @Test
    fun `net is what you have gone past`() {
        assertEquals(0.0, net(z = 100.0, travel = 100.0), 0.001)
        assertTrue("still ahead of you", net(z = 100.0, travel = 0.0) < 0)
        assertTrue("behind you", net(z = 0.0, travel = 100.0) > 0)
    }
}
