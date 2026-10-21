package com.drivealert.app.data.osm

import com.drivealert.app.domain.geo.GeoMath
import com.drivealert.app.domain.model.Camera
import com.drivealert.app.domain.model.CameraType
import com.drivealert.app.domain.model.DataSource
import com.drivealert.app.domain.model.Directionality
import com.drivealert.app.domain.model.VerificationStatus
import com.drivealert.app.domain.relevance.CameraRelevanceEngine
import com.drivealert.app.domain.reporting.ReportType
import com.drivealert.app.reporting.OsmNoteText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmCameraParserTest {
    private val now = 1_790_000_000_000L // 2026

    /** Small hand-written Overpass-style document (values are illustrative, not real cameras). */
    private val fixture = """
        {"osm_base":"2026-10-21T08:00:00Z","elements":[
          {"type":"area","id":3600000001,"tags":{"ISO3166-2":"IN-KA","name":"ಕರ್ನಾಟಕ","name:en":"Karnataka"}},
          {"type":"node","id":1,"lat":12.90,"lon":77.50,"timestamp":"2025-01-01T00:00:00Z","tags":{"highway":"speed_camera","maxspeed":"60","direction":"90"}},
          {"type":"node","id":2,"lat":12.91,"lon":77.51,"tags":{"highway":"speed_camera","direction":"N;S","maxspeed":"40 mph"}},
          {"type":"node","id":3,"lat":12.92,"lon":77.52,"tags":{"highway":"speed_camera","direction":"forward"}},
          {"type":"node","id":4,"lat":12.93,"lon":77.53,"tags":{"surveillance:type":"ALPR","surveillance":"traffic"}},
          {"type":"node","id":5,"lat":12.94,"lon":77.54,"tags":{"amenity":"bench"}},
          {"type":"node","id":30,"lat":12.945,"lon":77.545,"tags":{"highway":"speed_camera","direction":"-120"}},
          {"type":"node","id":45,"lat":12.99,"lon":77.58,"tags":{"highway":"speed_camera","direction":"0"}},
          {"type":"relation","id":90,"tags":{"type":"enforcement","enforcement":"traffic_signals"},
           "members":[{"type":"node","ref":10,"role":"from"},{"type":"node","ref":11,"role":"device"}]},
          {"type":"relation","id":91,"tags":{"type":"enforcement","enforcement":"maxspeed","maxspeed":"50"},
           "members":[{"type":"node","ref":3,"role":"device"},{"type":"node","ref":12,"role":"from"}]},
          {"type":"relation","id":92,"tags":{"type":"enforcement","enforcement":"traffic_signals"},
           "members":[{"type":"node","ref":41,"role":"from"},{"type":"node","ref":40,"role":"device"}]},
          {"type":"relation","id":93,"tags":{"type":"enforcement","enforcement":"traffic_signals"},
           "members":[{"type":"node","ref":43,"role":"from"},{"type":"node","ref":42,"role":"device"},{"type":"node","ref":44,"role":"to"}]},
          {"type":"relation","id":94,"tags":{"type":"enforcement","enforcement":"maxspeed"},
           "members":[{"type":"node","ref":46,"role":"from"},{"type":"node","ref":45,"role":"device"}]},
          {"type":"node","id":10,"lat":12.95,"lon":77.55},
          {"type":"node","id":11,"lat":12.96,"lon":77.55},
          {"type":"node","id":12,"lat":12.91,"lon":77.52},
          {"type":"node","id":40,"lat":12.97,"lon":77.56},
          {"type":"node","id":41,"lat":12.97002,"lon":77.56},
          {"type":"node","id":42,"lat":12.98,"lon":77.57},
          {"type":"node","id":43,"lat":12.979,"lon":77.57},
          {"type":"node","id":44,"lat":12.979,"lon":77.5701},
          {"type":"node","id":46,"lat":12.989,"lon":77.58},
          {"type":"area","id":3600000002,"tags":{"ISO3166-2":"IN-KL","name:en":"Kerala"}},
          {"type":"node","id":20,"lat":10.0,"lon":76.3,"tags":{"highway":"speed_camera"}},
          {"type":"node","id":1,"lat":12.90,"lon":77.50,"tags":{"highway":"speed_camera","maxspeed":"60","direction":"90"}}
        ]}
    """.trimIndent()

    private val snapshot = OsmCameraParser.parse(fixture, now)
    private val karnataka = snapshot.camerasByRegion.getValue("IN-KA").associateBy { it.id }

    private val bundled: Map<String, Camera> by lazy {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream(OsmConfig.BUNDLED_ASSET)) {
            "bundled snapshot not on the unit-test classpath"
        }.bufferedReader().use { it.readText() }
        OsmCameraParser.parse(text, now).camerasByRegion.values.flatten().associateBy { it.id }
    }

    @Test
    fun `regions come from the area markers with English names`() {
        assertEquals(listOf("IN-KA", "IN-KL"), snapshot.regions.map { it.code })
        assertEquals("Karnataka", snapshot.regions.first().name)
        assertEquals(java.time.Instant.parse("2026-10-21T08:00:00Z").toEpochMilli(), snapshot.osmBaseMillis)
    }

    @Test
    fun `direction tag is where the camera faces, so travel bearing is reversed`() {
        val camera = karnataka.getValue("osm-node-1")
        assertEquals(CameraType.FIXED_SPEED, camera.type)
        assertEquals(270.0, camera.bearingDeg!!, 1e-9) // faces east (90) -> enforces westbound traffic
        assertEquals(Directionality.ONE_DIRECTION, camera.directionality)
        assertEquals(60, camera.speedLimitKmh)
        assertEquals(DataSource.OPENSTREETMAP, camera.dataSource)
        assertEquals(VerificationStatus.COMMUNITY_CONFIRMED, camera.verificationStatus)
        assertTrue(camera.sourceReference!!.contains("OpenStreetMap contributors"))
        assertEquals("osm-IN-KA", camera.packageId)
        assertEquals("Karnataka", camera.state)
    }

    @Test
    fun `negative directions are normalised before reversing`() {
        assertEquals(60.0, karnataka.getValue("osm-node-30").bearingDeg!!, 1e-9) // -120 = 240 -> travel 60
    }

    @Test
    fun `two opposite directions mean both directions and mph is converted`() {
        val camera = karnataka.getValue("osm-node-2")
        assertEquals(Directionality.BOTH_DIRECTIONS, camera.directionality)
        assertEquals(65, camera.speedLimitKmh) // 40 mph = 64.4 km/h -> nearest 5
    }

    @Test
    fun `relative directions stay unknown unless a relation gives reliable geometry`() {
        val camera = karnataka.getValue("osm-node-3")
        assertEquals(Directionality.ONE_DIRECTION, camera.directionality)
        val expected = GeoMath.initialBearingDeg(12.91, 77.52, 12.92, 77.52)
        assertEquals(expected, camera.bearingDeg!!, 1e-6)
        assertEquals(50, camera.speedLimitKmh)
    }

    @Test
    fun `relation geometry overrides a node tag that contradicts it`() {
        // Node 45 faces north (-> travel south 180) but relation 94 runs from the south to it (travel north).
        assertEquals(0.0, karnataka.getValue("osm-node-45").bearingDeg!!, 0.5)
    }

    @Test
    fun `relation references closer than 20 m or contradicting each other give no direction`() {
        assertEquals(Directionality.UNKNOWN, karnataka.getValue("osm-node-40").directionality) // from 2 m away
        assertEquals(Directionality.UNKNOWN, karnataka.getValue("osm-node-42").directionality) // in 0, out 90+ apart
    }

    @Test
    fun `ALPR nodes become ANPR cameras and unrelated nodes are ignored`() {
        assertEquals(CameraType.ANPR, karnataka.getValue("osm-node-4").type)
        assertNull(karnataka["osm-node-5"])
    }

    @Test
    fun `traffic-signal enforcement relation creates a red-light camera at the device`() {
        val camera = karnataka.getValue("osm-node-11")
        assertEquals(CameraType.RED_LIGHT, camera.type)
        assertEquals(12.96, camera.latitude, 1e-9)
        assertEquals(0.0, camera.bearingDeg!!, 0.5) // from (12.95) north to device (12.96), 1.1 km
        assertTrue(camera.sourceReference!!.endsWith("relation/90"))
    }

    @Test
    fun `an element listed in two regions belongs to the first`() {
        assertTrue(snapshot.camerasByRegion.getValue("IN-KL").none { it.id == "osm-node-1" })
        assertEquals(listOf("osm-node-20"), snapshot.camerasByRegion.getValue("IN-KL").map { it.id })
    }

    @Test
    fun `value parsers`() {
        assertEquals(45.0 to Directionality.ONE_DIRECTION, OsmCameraParser.parseDirection("NE"))
        assertEquals(240.0 to Directionality.ONE_DIRECTION, OsmCameraParser.parseDirection("-120"))
        assertEquals(null to Directionality.UNKNOWN, OsmCameraParser.parseDirection("45-90"))
        assertEquals(null to Directionality.UNKNOWN, OsmCameraParser.parseDirection("90;100"))
        assertEquals(80, OsmCameraParser.parseMaxspeed("80 km/h"))
        assertNull(OsmCameraParser.parseMaxspeed("IN:urban"))
        assertNull(OsmCameraParser.parseMaxspeed("none"))
        assertEquals("IN-KA", OsmCameraParser.regionCodeOf("osm-IN-KA"))
        assertNull(OsmCameraParser.regionCodeOf("osm-../x"))
    }

    @Test
    fun `bundled India snapshot parses into usable cameras`() {
        val all = bundled.values.toList()
        assertTrue("only ${all.size} cameras", all.size >= 500)
        assertTrue(all.all { it.dataSource == DataSource.OPENSTREETMAP && it.packageId.startsWith("osm-IN-") })
        assertTrue(all.all { it.latitude in 6.0..37.5 && it.longitude in 68.0..98.0 })
        val engine = CameraRelevanceEngine()
        val sample = all.first()
        val fix = com.drivealert.app.testutil.Fixtures.fix(com.drivealert.app.domain.geo.GeoPoint(sample.latitude - 0.004, sample.longitude), 0L)
        engine.evaluate(fix, null, all.take(50), now, includeUnverified = false)
    }

    @Test
    fun `real data cross-check - node tags agree with their enforcement relations after the reversal`() {
        // Each camera below has both a `direction` tag and an enforcement relation (from -> device).
        assertEquals(342.0, bundled.getValue("osm-node-11959592200").bearingDeg!!, 10.0) // tag 165, relation 342
        assertEquals(265.0, bundled.getValue("osm-node-12961358380").bearingDeg!!, 10.0) // tag 90, relation 265
        assertEquals(85.0, bundled.getValue("osm-node-12961358381").bearingDeg!!, 10.0) // tag 270, relation 85
    }

    @Test
    fun `real data - noisy junction relations leave devices without a direction`() {
        assertEquals(Directionality.UNKNOWN, bundled.getValue("osm-node-6789818465").directionality) // from 3 m away
        assertEquals(Directionality.UNKNOWN, bundled.getValue("osm-node-12107777007").directionality) // 10 devices, from 2 m away
        assertEquals(Directionality.UNKNOWN, bundled.getValue("osm-node-9223861162").directionality) // in 175, out 355
    }

    @Test
    fun `OSM note text links the camera, carries the reference and no driver coordinates`() {
        val text = OsmNoteText.build(ReportType.CAMERA_REMOVED, "osm-node-123", null, null, "Pole is empty", OsmNoteText.reference(7, 99))
        assertTrue(text.contains("https://www.openstreetmap.org/node/123"))
        assertTrue(text.contains("camera no longer present"))
        assertTrue(text.contains("Pole is empty"))
        assertTrue(text.endsWith("Ref: DriveAlert-r7-99"))
        assertTrue(!Regex("\\d+\\.\\d{4,}").containsMatchIn(text))
        assertNull(OsmNoteText.osmLink("TEST-001"))
    }
}
