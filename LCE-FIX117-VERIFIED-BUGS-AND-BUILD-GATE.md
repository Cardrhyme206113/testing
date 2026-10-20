# LCE Fix117 verified native defects and release gate

**Do not deploy this branch.** This branch is diagnostics only, not a release.

## Native UI slider reproduction
Actual compiled C++ game running on GitHub-Pages-style static origin, original Flash/Ruffle SettingsAudioMenu720.swf. Test evidence: workflow run https://github.com/Cardrhyme206113/testing/actions/runs/39369861740

1. Open native Audio Settings (test hook scene ID 4).
2. Selected **Music 100%** initially.
3. Hold physical `ArrowLeft` for 230 ms and release -> **Music 99%**. Native setting and Flash display respond.
4. Mouse-down at x=774,y=320, drag to x=548,y=320, mouse-up -> **Music 99% unchanged**; focus highlight disappeared.

The correct fix must update the native `UIControl_Slider` value via native mouse event handling and its existing C++ callback, not insert a new HTML slider or update only the SWF text. Inspect `Minecraft.Client/Web/Web_Input.cpp`, `Minecraft.Client/Common/UI/UIControl_Slider.cpp`, and `UIScene.cpp`.

Other original native slider scenes:
- ID 4 `SettingsAudioMenu720.swf`: Music and Sound volume sliders.
- ID 6 `SettingsGraphicsMenu720.swf`: Gamma and Interface Opacity sliders.

## World storage reproduction
Runtime audit: https://github.com/Cardrhyme206113/testing/actions/runs/39369111682

- Emscripten `FS` is exposed and uses `MEMFS`.
- The compiled JS explicitly says `IDBFS is no longer included by default; build with -lidbfs.js`.
- No `/saves`, `/save`, `/minecraft`, `/persistent`, or `/IDBFS` mount exists.
- Filesystem root contains `/tmp`, `/home`, `/dev`, `/proc`, `/Common`.
- No verified persistent native world file exists. Do not trigger an automatic browser download containing an empty or synthetic save.

The native `Web_Storage.cpp` and platform `C4JStorage` pathways must be implemented and rebuilt before a real world-exit download or drag/drop import can be accepted. Need a round-trip test: native create -> modify -> native save -> exported bytes -> clean browser -> import bytes -> exact world metadata/chunks/player location.

## Escape/pause
The current Fix116 browser key mapping is a candidate only. The Fix117 exploratory world test did not reach a verified `HUD720.swf` gameplay state (the only visible scenes were `PressStartToPlay720.swf` and `Panorama720.swf`), so neither Escape nor Backquote opening a pause screen can be claimed verified from that sample. Require actual gameplay state.

## Menu latency
Fix116 removed the explicit `fetch(..., {cache:'no-store'})` for corrected Controls and CreateWorld Flash assets and the Chromium smoke test confirmed Controls movie fetch count 1. Full native transition latency and Ruffle instance startup remain unmeasured; do not claim the entire menu delay fixed.

## Build/blocker
- Current tested site is original Fix110 C++ WebAssembly binary with Fix113 Ruffle listener cleanup and Fix116 JS patches; *not* new native C++ compilation.
- Complete original native source archive supplied previously is in the file Library, about 196 MB ZIP, exceeding current file materialization limit (100 MB). Container/Python executor also fails in this session.
- GitHub repository does not include entire source; Fix104 CI source retrieval explicitly requires separately hosted `LCE_SOURCE_URL`, unavailable. Thus native changes cannot honestly be compiled/validated in this branch.
- Provide smaller source-only archive excluding build-web, .o/.a, node_modules and other generated outputs, or accessible private source in a runner. Do not upload proprietary LCE source to a public repository without authorization.

**Release gate:** sliders respond to actual mouse drag; Escape toggles correct native pause/back in real world; after save and exit a real file downloads and re-import loads same world; menus demonstrate measured responsiveness; 20-cycle native UI stress no unbounded memory growth; native build compiled without debug tools. Only then deploy.
