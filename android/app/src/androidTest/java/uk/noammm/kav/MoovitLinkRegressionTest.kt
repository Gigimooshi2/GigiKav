package uk.noammm.kav

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.MoovitLink
import uk.noammm.kav.ui.exactTrip
import uk.noammm.kav.ui.sameLines

@RunWith(AndroidJUnit4::class)
class MoovitLinkRegressionTest {

    @Test
    fun sharedLinkRoundTrips() {
        val url = MoovitLink.share(
            "דיזנגוף סנטר", 32.075123, 34.775456,
            "Jerusalem Central + gate 3", 31.789012, 35.203456,
            departMs = 1_800_000_000_000L,
        )
        assertTrue(url.startsWith("https://moovitapp.com/directions?"))
        val plan = MoovitLink.parse(url)!!
        assertEquals("דיזנגוף סנטר", plan.fromName)
        assertEquals(32.075123, plan.fromLat!!, 1e-5)
        assertEquals(34.775456, plan.fromLon!!, 1e-5)
        assertEquals("Jerusalem Central + gate 3", plan.toName)
        assertEquals(31.789012, plan.toLat!!, 1e-5)
        assertEquals(35.203456, plan.toLon!!, 1e-5)
        assertEquals(1_800_000_000_000L, plan.departMs)
        assertTrue(plan.autoRun)
    }

    @Test
    fun originIsOptionalBothWays() {
        val url = MoovitLink.share(null, null, null, "עזריאלי", 32.074, 34.792)
        assertFalse(url.contains("orig_"))
        assertFalse(url.contains("date="))
        val plan = MoovitLink.parse(url)!!
        assertNull(plan.fromLat)
        assertNull(plan.fromName)
        assertEquals(32.074, plan.toLat!!, 1e-5)
        assertEquals(0L, plan.departMs)
    }

    @Test
    fun moovitSchemeAndTheirDefaultsParse() {
        val plan = MoovitLink.parse(
            "moovit://directions?dest_lat=32.06&dest_lon=34.77&dest_name=Home%20Base&auto_run=false&date=notanumber",
        )!!
        assertEquals("Home Base", plan.toName)
        assertFalse(plan.autoRun)
        assertEquals(0L, plan.departMs)
        assertNull(plan.fromLat)

        assertTrue(MoovitLink.parse("moovit://directions?dest_lat=1&dest_lon=2&auto_run=yes")!!.autoRun)
        assertFalse(MoovitLink.parse("moovit://directions?dest_lat=1&dest_lon=2&auto_run=0")!!.autoRun)
    }

    @Test
    fun aNamedButUnplacedEndKeepsItsNameOnly() {
        val plan = MoovitLink.parse("https://moovitapp.com/directions?dest_name=Haifa&dest_lat=32.8")!!
        assertEquals("Haifa", plan.toName)
        assertNull(plan.toLat)
        assertNull(plan.toLon)
    }

    @Test
    fun whatIsNotADirectionsLinkIsNotParsed() {
        assertNull(MoovitLink.parse(null))
        assertNull(MoovitLink.parse(""))
        assertNull(MoovitLink.parse("https://example.com/directions?dest_lat=1&dest_lon=2"))
        assertNull(MoovitLink.parse("https://moovitapp.com/index?dest_lat=1&dest_lon=2"))
        assertNull(MoovitLink.parse("moovit://line?id=15043"))
        assertNull(MoovitLink.parse("moovit://directions?orig_lat=1&orig_lon=2"))
    }

    @Test
    fun namedRidesRoundTrip() {
        val url = MoovitLink.share(
            "A", 32.0, 34.7, "B", 31.7, 35.2, departMs = 1_800_000_000_000L,
            rides = listOf(
                MoovitLink.Ride(15043, 43_210_987L, 1_800_000_600L),
                MoovitLink.Ride(2210, 0L, 1_800_003_000L),
            ),
        )
        assertTrue("kav_trip=15043.43210987.1800000600~2210.0.1800003000" in url)
        val plan = MoovitLink.parse(url)!!
        assertEquals(2, plan.rides.size)
        assertEquals(15043, plan.rides[0].lineId)
        assertEquals(43_210_987L, plan.rides[0].tripId)
        assertEquals(1_800_000_600L, plan.rides[0].depSec)
        assertEquals(2210, plan.rides[1].lineId)
        assertFalse(MoovitLink.share(null, null, null, "X", 1.0, 2.0).contains("kav_trip"))
    }

    @Test
    fun halfARideNamesNoTripAtAll() {
        assertTrue(MoovitLink.parse("moovit://directions?dest_lat=1&dest_lon=2")!!.rides.isEmpty())
        val garbled = MoovitLink.parse(
            "moovit://directions?dest_lat=1&dest_lon=2&kav_trip=15043.7.1800~oops",
        )!!
        assertTrue(garbled.rides.isEmpty())
        val short = MoovitLink.parse("moovit://directions?dest_lat=1&dest_lon=2&kav_trip=15043.7")!!
        assertTrue(short.rides.isEmpty())
    }

    private fun ride(lineId: Int, tripId: Long, dep: Long) = Moovit.Leg(
        kind = Moovit.LegKind.RIDE, lineId = lineId, tripId = tripId, dep = dep, arr = dep + 600,
    )

    private fun trip(vararg legs: Moovit.Leg) = Moovit.Itinerary(
        guid = "", group = 1, legs = legs.toList(),
        dep = legs.first().dep, arr = legs.last().arr,
    )

    @Test
    fun theNamedTripIsTheOneItsIdsName() {
        val named = listOf(MoovitLink.Ride(15043, 777L, 1_800_000_600L))
        assertTrue(exactTrip(trip(ride(15043, 777L, 1_800_009_999L)), named))
        val timed = listOf(MoovitLink.Ride(15043, 0L, 1_800_000_600L))
        assertTrue(exactTrip(trip(ride(15043, 0L, 1_800_000_630L)), timed))
        assertFalse(exactTrip(trip(ride(15043, 0L, 1_800_000_900L)), timed))
        assertFalse(exactTrip(trip(ride(2210, 777L, 1_800_000_600L)), named))
        assertFalse(exactTrip(trip(ride(15043, 777L, 1_800_000_600L), ride(1, 2L, 3L)), named))
        assertFalse(exactTrip(trip(ride(15043, 777L, 1_800_000_600L)), emptyList()))
        val sailed = trip(ride(15043, 888L, 1_800_090_000L))
        assertFalse(exactTrip(sailed, named))
        assertTrue(sameLines(sailed, named))
        assertFalse(sameLines(trip(ride(2210, 888L, 1_800_090_000L)), named))
    }
}
