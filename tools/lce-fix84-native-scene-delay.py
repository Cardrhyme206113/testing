#!/usr/bin/env python3
"""Fix original C++ Playwright UI-test bridge's scene-close race.

Apply to the rooted LCE source before the Emscripten C++ rebuild.
This is a development-only hook gated by ?lce_ui_test=1.
"""
from pathlib import Path
import sys
p=Path(sys.argv[1] if len(sys.argv)>1 else "Minecraft.Client/Web/Web_DemoMain.cpp")
s=p.read_text()
if "g_uiTestNextFrame" in s:
    print("Native test sequence patch already installed")
    sys.exit(0)
patches=[
("""int g_uiTestPending = -1;
int g_uiTestLastResult = -99;""",
"""int g_uiTestPending = -1;
int g_uiTestLastResult = -99;
int g_uiTestPhase = 0;
unsigned long long g_uiTestNextFrame = 0;"""),
("""    ui.CloseUIScenes(0);
    if (kind < 20) {""",
"""    // Closing was queued in an earlier frame and its 10-tick countdown expired.
    if (kind < 20) {"""),
("""    const int kind = g_uiTestPending;
    g_uiTestPending = -1;
    g_uiTestLastResult = executeUiTestRequest(kind);
    std::fprintf(stderr, "[LCE-Web][ui-test] scene=%d result=%d\\n",kind,g_uiTestLastResult);""",
"""    if (g_uiTestPhase == 0) {
        ui.CloseUIScenes(0);
        g_uiTestPhase = 1;
        g_uiTestNextFrame = g_frames + 13ull;
        return;
    }
    if (g_frames < g_uiTestNextFrame) return;
    const int kind = g_uiTestPending;
    g_uiTestPending = -1;
    g_uiTestPhase = 0;
    g_uiTestLastResult = executeUiTestRequest(kind);
    std::fprintf(stderr, "[LCE-Web][ui-test] scene=%d result=%d after close countdown\\n",kind,g_uiTestLastResult);""")
]
for a,b in patches:
    n=s.count(a)
    if n!=1:raise SystemExit(f"Patch context appears {n} times; refusing unsafe modification")
    s=s.replace(a,b,1)
p.write_text(s)
print("C++ original menu test hook: delayed 13 frames after CloseUIScenes()")
