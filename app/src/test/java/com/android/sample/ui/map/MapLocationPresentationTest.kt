package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import com.android.sample.model.*
import org.junit.Assert.*
import org.junit.Test

class MapLocationPresentationTest {
  private val fix = DeviceLocation(46.521, 6.566, 150f, 1000)

  @Test
  fun campusPositionRejectsOffCampusAndInvalidCoordinates() {
    assertNotNull(fix.campusPosition())
    assertNull(fix.copy(latitude = 47.0).campusPosition())
    assertNull(fix.copy(longitude = Double.NaN).campusPosition())
  }

  @Test
  fun viewportRejectsAnchorsOutsideItsBounds() {
    assertTrue(MapPosition(0.0, 1.0).inViewport)
    assertFalse(MapPosition(-.01, .5).inViewport)
    assertFalse(MapPosition(.5, 1.01).inViewport)
  }

  @Test
  fun cameraCentersTheFixAndPreservesRelativeMarkerGeometry() {
    val point = fix.campusPosition()!!
    val camera = MapCamera.centeredAt(point, LOCATION_CAMERA_ZOOM)
    assertEquals(MapPosition(.5, .5), camera.project(point))
    val projected = camera.project(MapPosition(point.x + .1, point.y - .1))
    assertEquals(.7, projected.x, .000001)
    assertEquals(.3, projected.y, .000001)
    assertEquals(MapPosition(.3, .4), MapCamera().project(MapPosition(.3, .4)))
  }

  @Test
  fun cameraPanAndZoomAreBounded() {
    assertEquals(MapCamera.centeredAt(MapPosition(.4, .6), 2f), MapCamera().moved(.1, -.1, 2f))
    assertEquals(
        MapCamera.centeredAt(MapPosition(1.0 / 6, 5.0 / 6), 3f),
        MapCamera().moved(4.0, -4.0, 20f),
    )
    assertEquals(1f, MapCamera.centeredAt(MapPosition(.5, .5), 2f).moved(0.0, 0.0, .1f).zoom)
  }

  @Test
  fun fullMapCannotBePannedBeyondViewport() {
    assertEquals(MapCamera(), MapCamera().moved(4.0, -4.0, 1f))
  }

  @Test
  fun edgeRecenteringKeepsDrawingAcrossViewport() {
    val camera = MapCamera.centeredAt(MapPosition(0.0, 1.0), 2f)
    assertEquals(MapPosition(.25, .75), camera.center)
    assertEquals(0.0, camera.project(MapPosition(0.0, 0.0)).x, .000001)
    assertEquals(1.0, camera.project(MapPosition(1.0, 1.0)).y, .000001)
  }

  @Test
  fun zoomingOutReboundsAnOffCenterCamera() {
    val camera = MapCamera.centeredAt(MapPosition(.25, .75), 2f).moved(0.0, 0.0, .5f)
    assertEquals(MapCamera(), camera)
  }
}
