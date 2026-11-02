<img src="docs/img/hmg-api.png" width="120" align="right" alt="HMG-API logo">

# HMG-API

A mod manager for Hyundai, Kia and Genesis **Gen5W** navigation head units.

Mods add features to the head unit (wireless CarPlay and Android Auto with
turn-by-turn on the cluster and HUD, buttons that do more, side panel pages, and
more) without patching its apps. HMG-API hooks the car's own software while it runs,
gives every mod a switch, settings and a log, and puts back exactly what a mod
changed when it's removed.

![The HMG-API app on a Kia EV6](docs/img/screenshots/mods.png)

| Settings | Car Report |
|---|---|
| ![Settings](docs/img/screenshots/settings.png) | ![Car Report](docs/img/screenshots/report.png) |

## Install

Download the zip for your route from [Releases](https://github.com/VysseCodes/HMG-API/releases):
a **USB stick** (no computer), **NEMESIS**, or **adb**. Step by step:
[Installing HMG-API](https://github.com/VysseCodes/HMG-API/wiki/Installing-HMG-API).

HMG-API runs on any Gen5W navigation head unit. Cars with a hook map get everything;
on others, mods still install and run, and the app's **Car Report** sends what's
needed to add your car ([Supported cars](https://github.com/VysseCodes/HMG-API/wiki/Supported-cars)).

| Car | Region | Software | Hook map |
|---|---|---|---|
| Kia EV6 | USA | 260507 | checked on a car |
| Kia EV6, Hyundai Ioniq 5 | Canada | 260206 | waiting for a Car Report |
| Hyundai Ioniq 5 | USA | 260507 | waiting for a Car Report |
| Hyundai Ioniq 5 | Korea | NEEV.KOR 260902 | 324/325 hook points mapped from an on-car report; map unverified, wireless mod unconfirmed with HMG-API |

## Help and requests

[Open an issue](https://github.com/VysseCodes/HMG-API/issues/new/choose): something
doesn't work, car or firmware support (the Car Report's QR code fills it in), a new
hook point, or an idea. Guides are in the [wiki](https://github.com/VysseCodes/HMG-API/wiki).

> **Status: 1.0.0 beta 5.** Tested on a US Kia EV6 (260507): the hook runtime,
> mods, the app, buttons, the side panel, Android Auto turn-by-turn and projection
> events, the Car Report. CarPlay on HMG-API, the installers on a car without adb, and
> other cars aren't tested yet. Leafia 1.x (HMG Leafia Plus) is the previous project.

---

## For developers

### Parts

| Folder | What |
|---|---|
| `core/hmgapid` | root daemon and client (static ARM, no libc) |
| `core/zygote` | `app_process` wrapper: preloads the hook runtime into the zygote, boot guard |
| `core/loader` | `libhmgapi_hook.so`: Dalvik method hooks, loads the runtime jar |
| `core/runtime` | `hmgapi-hook.jar`: hook API, mod loader, launcher and ★-key extension points |
| `core/app` | the HMG-API app (`hmgapi.app`) |
| `core/client` | `hmgapi-client.jar`: for mods' own apps and services (settings, services, USB folders, notices) |
| `install/` | installers: `adb/`, `nemesis/`, `usb/` (DSP OTA), shared `common/`; `dev/` has the scripts used while building the core |
| `core/hookpoints`, `maps/` | the hook point catalogue (stable names) and each car's map (`maps/coverage.json`: which points every car has) |
| `mods/` | `wireless-carplay-aa`, `adb`, `adbd`; `examples/`: `hello`, `hello-service`, `side-panel`, `car-check`, `nemesis-shortcut` and more |
| `docs/` | `mod-reference.md` (the `mod.json` reference; `tools/mod-draft.py` drafts one from an existing package), `api-plan.md` (what each community project uses), `vehicle-commands.md`, `micom-map.md`, `car-test.md` (the on-car test session) |
| `wiki/` | the owners' wiki (GitHub wiki pages: installing, mods, the app, troubleshooting) |
| `tools/` | `lpatch`, patch makers, build settings |

### Installers

`tools/mkinstallers.sh` makes three downloads in `out/dist/`, all carrying the same payload and ending in `install/common/install.sh`:

| Download | For |
|---|---|
| `hmgapi-<v>-usb.zip` | a USB stick. Nothing installed yet: Engineering Mode > USB Copy > DSP > Update OTA. NEMESIS installed: its USB install. HMG-API installed: the app's updates (`HMGAPI/updates`, with the mods). An empty `HMGAPI/UNINSTALL` file uninstalls through the DSP route. |
| `hmgapi-<v>-nemesis.zip` | for NEMESIS: unzipped onto a stick, like NEMESIS's own packages (`NEMESIS.data` installs `packages/hmgapi.zip`; an update keeps HMG-API and its mods) |
| `hmgapi-<v>-nemesis-package.zip` | the NEMESIS package on its own, for NEMESIS's Package Store and `nemesis install` |
| `hmgapi-<v>-adb.zip` | `hmgapi-adb.sh install \| uninstall \| status` over adb (first install: root from NEMESIS) |

Uninstalling puts every file and setting HMG-API or a mod changed back to the stock bytes.

### Build and test

```
./build.sh
```

Everything goes to `out/`. Needs clang + lld, a JDK and the Android SDK (build-tools, a platform `android.jar`). No manufacturer files: the loader links against stand-ins for the head unit's libraries (`core/loader/syslib-stubs.sh`, the same bytes as linking the unit's own). Without HMG-API's signing key (`HMGAPI_KEY`) the app is signed with a throwaway key.

GitHub Actions: `ci.yml` builds and runs every off-car test on each push and pull request; `release.yml` turns a version tag (`v1.0.0-beta.1`) into a signed draft release (keys in repository secrets; bundled mods in `tools/release-mods.txt`); `wiki.yml` publishes `wiki/` to the GitHub wiki.

```
tools/test-all.sh
```

Runs the off-car tests under qemu-arm. With stock trees listed in `tools/hook-trees.local`
(private), it also tests every hook point on every car (`tools/test-hooks.py`) and the
on-car mapper against `tools/map-car.py` (`tools/probe-test.py`).

## Licence

AGPL-3.0 (see `LICENSE` and `NOTICE.md`).
