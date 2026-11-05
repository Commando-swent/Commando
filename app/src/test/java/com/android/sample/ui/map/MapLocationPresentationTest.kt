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
    val camera = MapCamera(point, LOCATION_CAMERA_ZOOM)
    assertEquals(MapPosition(.5, .5), camera.project(point))
    val projected = camera.project(MapPosition(point.x + .1, point.y - .1))
    assertEquals(.7, projected.x, .000001)
    assertEquals(.3, projected.y, .000001)
    assertEquals(MapPosition(.3, .4), MapCamera().project(MapPosition(.3, .4)))
  }

  @Test
  fun cameraPanAndZoomAreBounded() {
    assertEquals(MapCamera(MapPosition(.4, .6), 2f), MapCamera().moved(.1, -.1, 2f))
    assertEquals(MapCamera(MapPosition(0.0, 1.0), 3f), MapCamera().moved(4.0, -4.0, 20f))
    assertEquals(1f, MapCamera(zoom = 2f).moved(0.0, 0.0, .1f).zoom)
  }
}
