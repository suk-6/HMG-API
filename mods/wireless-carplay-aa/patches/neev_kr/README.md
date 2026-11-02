NEEV.KOR 260902 (Ioniq 5), from Leafia 1.0.2 `patches/neev_kr`.

`stock` records the owner's stock app/odex and Android Auto library hashes. HMG-API
uses runtime hooks for the Leafia DiPO, GPM, Iap2 and navigation app deltas, so
there are no rebuilt app files in this set and no app `expect` hashes. The shared
`aa-tbt-48ee793a` recipe fits this set's Android Auto library. The shared
CarPlay audio recipe fits the Leafia stock audio library; Core and Utils have
native hashes for the built-in Korean Wi-Fi session and AAC/Opus converters.

These hooks require a verified NEEV.KOR hook map. The stock app files needed to
generate and verify that map are not in this repo or available on this machine.
Until an on-car map is built and verified, HMG-API applies the projection flags
and hotspot file but leaves the Java and native hook features off. A Car Report
from a Wi-Fi-equipped unit is required to finish and validate this port.
