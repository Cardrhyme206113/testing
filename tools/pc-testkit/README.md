# Minecraft Legacy Console Edition — Fix 113 Windows PC Test Kit

This folder contains the **actual playable LCE WebAssembly game**, the original SWF UI, the patched Ruffle runtime from Fix 113, and local test helpers. It does not create fake menus, fake screenshots, or HTML replacements for in-game screens.

The confusing term **"Ruffle player" means an instance of the Flash runtime hosting a SWF menu/movie**. These are not Minecraft multiplayer clients. A single-player game can have many SWF instances.

## Start the actual game

1. Extract the ENTIRE downloaded ZIP into one folder. Keep `vendor/`, `ui-assets/`, `lce_web_demo.wasm`, all .data files and the launcher together.
2. On Windows, double-click **START-GAME.cmd**. Requires Python 3 installed.
3. The script opens Edge/Chrome at `http://127.0.0.1:8765/lce_web_demo.html?lce_ui_test=1`.
4. Play single-player normally. Do not open the HTML file via `file://`; the WASM/WebGL/browser-isolation setup requires HTTP and COOP/COEP headers.
5. Close the command window (or press Ctrl+C) to stop hosting. Only `127.0.0.1` is used: there is no public server, admin requirement or remote tunnel.

If `START-GAME.cmd` cannot find Python, install the latest Python 3 and use `py -3 server.py` manually. If port 8765 is busy, stop the older copy first.

## Measure the UI leak on YOUR GPU

1. Have **Python 3, Node.js 18+**, and Microsoft Edge or Google Chrome installed.
2. Double-click **RUN-PROFILER.cmd**. The first launch installs the small `playwright-core` dependency locally with npm; this does not download its own browser because it uses your installed Edge or Chrome.
3. The actual game opens in a browser window. **Manually load or create a world**, then use the profiler terminal and press Enter once the world has settled, the camera is stationary and all menus are closed.
4. It records a **15-second baseline**. Keep the game window visible and don't minimize it.
5. Manually open/close **Debug, Inventory, Settings, Controls** repeatedly (15–30 cycles); close everything and press Enter in the profiler terminal.
6. It records an equivalent **15-second post-test** window and offers a second repetition to check whether performance/memory plateaus.
7. Share the resulting `reports/lce-pc-*.json` file back in chat for analysis. The `.txt` is a short human-readable summary.

This test does **not** silently automate the world or substitute synthetic UI. That was unreliable in GitHub's software-rendered runner. It observes what you actually do on your PC.

### Metrics in the report

- Independent frame-time samples: **p50/p95/p99** per phase, *not* a cumulative median covering loading or different scenes.
- `tickCalls`, `hiddenTickCalls`, `hiddenTickCpuMs`: native WebIggy/Ruffle UI callback activity. These are Flash menu/movie instances.
- `players` and `hiddenPlayers`: registered **SWF movie instances**, not network players.
- `mem`: separately tracked exported WebAssembly memories. An increase in a memory's allocated capacity is **not sufficient evidence of a leak**; WebAssembly memory generally does not shrink automatically.
- `cdp.nodes`, `domElements`: browser DOM counts, with measurements before and after forced GC at selected boundaries.
- `taskDurationSeconds`: Chromium process task time for each phase. It includes more than game-script CPU.

### What counts as a proper fix?

Compare the **same world, same camera, no menus visible, warmed-up hardware-accelerated browser**, before and after test cycles. The post-close median frame-time should remain within measurement variability of baseline, with no increasing post-close CPU load, DOM retention trend, or steadily accumulating WASM memory.

## Source and version

- Native game runtime and SWF scenes: restored from the user-tested **Fix 110 original-alpha playable** artifact.
- Ruffle runtime: **Fix 113 global-listener teardown patch** (`LCE_FIX113_GLOBAL_LISTENERS` marker).
- Fix 111 native C++ allocator changes are **not** in this playable. Treat them as a separate future build.
- Test kit source: [GitHub branch](https://github.com/Cardrhyme206113/testing/tree/lce-fix113-pc-testkit-20261019).
- This package has local test helpers only; it does not assert that the entire slowdown is fixed.

## Troubleshooting

- Black screen or missing UI: run through `START-GAME.cmd`, not `file://`; check that `vendor/` and `ui-assets/` exist; ensure the browser allows WebAssembly and WebGL.
- Profiler cannot find a browser: use Edge/Chrome desktop; Chromium-based browser integration looks for named `msedge` or `chrome` installations.
- Browser needs keyboard/mouse: click the real game window to focus. Switch to the terminal only when asked; the profiler brings the browser to the foreground for measurement.
- Windows security prompts: the local Python HTTP server binds loopback only. No port forwarding or administrator rights are needed.
- Slow first load: the full original game and SWFs are large. Use the same world and allow loading to finish before recording.
