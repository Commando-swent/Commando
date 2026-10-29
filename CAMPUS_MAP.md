# Campus map

Bundled 2048 px lossless WebP OSM snapshot (393,618 bytes, down from 751,092-byte PNG); no SDK, API key, network tiles or device GPS permission.
Source: https://api.openstreetmap.org/api/0.6/map?bbox=6.559,46.516,6.573,46.526
© OpenStreetMap contributors, ODbL: https://www.openstreetmap.org/copyright
Bounds: south 46.516, north 46.526, west 6.559, east 6.573; north is up.
Drawing and pins share the equirectangular projection, full viewport and geographic anchors.
Edge labels shift inside expanded touch bounds; hidden/off-map trips have a scrollable selector.
Published trips share the list's repository and delivery filter; entry/resume/actions reload them.
Figma layout/icons: https://www.figma.com/design/WPmz3r0efh7jF2BQmUTfRt?node-id=421-2073
Distance uses the selected delivery point, otherwise “—”; Locate explains GPS availability.
AI assistance: OpenAI Codex (implementation, rasterization, Figma alignment and tests).

The viewport deliberately uses FillBounds for both the bundled image and marker projection.
This preserves pixel alignment but stretches the drawing in landscape. Letterboxing would
require applying the same inset rectangle to both layers; it is a separate visual change.
Legacy mock screens, state and fixtures live only in src/debug for previews and debug tests;
the main destination is TripMap. Shared production components remain in src/main.
