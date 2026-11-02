# Supported cars

HMG-API installs and runs on any Gen5W navigation head unit. What depends on your car
is its **hook map**: where each place HMG-API and mods hook is in your car's exact
software. With a map, everything works. Without one, mods still install and run, but
the parts that change the car's own apps stay off, and the app says which.

| Car | Region | Head unit software | Hook map |
|---|---|---|---|
| Kia EV6 | USA | 260507 | ✅ checked on a car (325 of 325 hook points) |
| Kia EV6 | Canada | 260206 | 259 hook points found in the stock files we have; waiting for a Car Report |
| Hyundai Ioniq 5 | Canada | 260206 | 259 hook points found in the stock files we have; waiting for a Car Report |
| Hyundai Ioniq 5 | USA | 260507 | found in stock files, waiting for a Car Report |
| Hyundai Ioniq 5 | Korea | NEEV.KOR 260902 | Ported from Leafia 1.0.2; not yet tested on a car with HMG-API. Hook map needed. |

**Your car isn't listed, or says "waiting"?** Install HMG-API and make a
[Car Report](Car-Report): HMG-API maps your car from its own software on the spot,
and the report is everything needed to add it. You can also try that map yourself
(Settings → Hook map → "Map this car from its files").

**Only the newest software for each car and region is supported.** If your head unit
has an older version, update it first (the manufacturer's update site or the dealer).

**Not supported:** head units without navigation ("Display Audio") are a different
system. Some Korean Ioniq 5 units have no Wi-Fi chip (Engineering Mode shows the
Wi-Fi module as NONE or MAC 00:00:00:00:00:00); wireless projection cannot work on
those units. Leafia 1.0.2 owners report it working on units with a chip. A Korean
HMG-API Car Report is still needed to map and verify the hook points.
