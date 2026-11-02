NEEV.KOR 260902 (Ioniq 5), from Leafia 1.0.2 `patches/neev_kr`.

`stock` records the owner's stock app/odex and Android Auto library hashes. HMG-API
uses runtime hooks for the Leafia DiPO, GPM, Iap2 and navigation app deltas, so
there are no rebuilt app files in this set and no app `expect` hashes. The shared
`aa-tbt-48ee793a` recipe fits this set's Android Auto library. The shared
CarPlay audio recipe fits the Leafia stock audio library; Core and Utils have
native hashes for the built-in Korean Wi-Fi session and AAC/Opus converters.

An owner's on-car mapper report supplied `maps/hyundai/ioniq5/kor/260902`:
324/325 hook points, status `mapped`, not verified. The one missing point is a
ThemeSettings split-screen item method, unused by this mod. Until `engine
verify-map` passes on the car, HMG-API applies the projection flags and hotspot
file but leaves Java and native hook features off. The mod has not yet been
confirmed working with HMG-API on a car. The report's Mods section was empty,
so it does not establish that the owner-installed mod applied successfully.
