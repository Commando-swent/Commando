# Campus map

The requester map uses a bundled OpenStreetMap snapshot, with no SDK, API key or network tiles.
Source: https://api.openstreetmap.org/api/0.6/map?bbox=6.559,46.516,6.573,46.526
Map data © OpenStreetMap contributors, ODbL: https://www.openstreetmap.org/copyright
The on-screen attribution links to this license.

Bounds: south 46.516, north 46.526, west 6.559, east 6.573; north is up.
Drawing and markers share the equirectangular projection and full viewport, including rotation.
Pin tips retain their coordinates; edge labels shift and off-map trips remain selectable.
Published trips reload on entry/resume, through the Map control and state-card actions.
The map shares the trip list's delivery-location filter and the session's TripRepository.

Layout and bundled icons follow Figma frames 424:2112, 424:2408 and 424:2740–424:3917:
https://www.figma.com/design/WPmz3r0efh7jF2BQmUTfRt?node-id=421-2073
Dynamic geographic content replaces the design's illustrative map. Distance is measured from
an explicitly chosen delivery point; otherwise it displays “—”. Locate explains GPS availability.
No device location or fabricated user-position dot is shown. Confirm this static-map approach
with the team; deployed-backend validation remains separate from injected-trip tests.

AI assistance: OpenAI Codex (integration, tests, geographic drawing and Figma alignment).
