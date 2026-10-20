#!/usr/bin/env python3
"""Fix118 native input: Escape (Start/Back) and pointer-dragged Flash sliders.

Apply to the ROOTED Fix107 LCE C++ source tree, after any earlier native source
patches (notably Fix100); rebuild with Emscripten. This modifies NO DOM UI.
Run again only for an already-identical patch: missing/changed anchors fail fast.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("lce-web-work")

def apply(rel, changes):
    path = root / rel
    s = path.read_text(encoding="utf-8-sig")
    if "LCE_FIX118_NATIVE_INPUT" in s:
        print("ALREADY_PATCHED", rel)
        return
    for old, new in changes:
        n = s.count(old)
        if n != 1:
            raise RuntimeError(f"{rel}: expected one anchor, found {n}: {old[:95]!r}")
        s = s.replace(old, new, 1)
    # Mark all changed files so an accidental second application does not occur.
    s = "// LCE_FIX118_NATIVE_INPUT\n" + s
    path.write_text(s, encoding="utf-8")
    print("PATCHED", rel)

apply("Minecraft.Client/Web/Web_Input.cpp", [
    ("bool gMenuMouseValid = false;",
     "bool gMenuMouseValid = false;\n"
     "// Track mouse-down across frames while a native slider is dragged.\n"
     "bool gMenuMouseLeftHeld = false;"),
    ('if (!std::strcmp(code, "Escape")) return _360_JOY_BUTTON_START;',
     '// Escape is context-sensitive in Tick(): Start in world, B inside menus.\n'
     'if (!std::strcmp(code, "Escape")) return 0;'),
    ("    gMenuMouseValid = false;\n    gInputActivityPending = false;",
     "    gMenuMouseValid = false;\n    gMenuMouseLeftHeld = false;\n    gInputActivityPending = false;"),
    ("EM_JS(double, webInputNormalisedMouseX, (int clientX), {",
     "EM_JS(double, webInputNormalisedMouseX, (int clientX, int clampOutside), {"),
    ("    if (clientX < left || clientX > left + width) return -1.0;",
     "    if (!clampOutside && (clientX < left || clientX > left + width)) return -1.0;"),
    ("EM_JS(double, webInputNormalisedMouseY, (int clientY), {",
     "EM_JS(double, webInputNormalisedMouseY, (int clientY, int clampOutside), {"),
    ("    if (clientY < top || clientY > top + height) return -1.0;",
     "    if (!clampOutside && (clientY < top || clientY > top + height)) return -1.0;"),
    ("""static bool updateMenuMousePosition(const EmscriptenMouseEvent *ev, bool markMove) {
    if (!ev) return false;
    const double nx = webInputNormalisedMouseX(ev->clientX);
    const double ny = webInputNormalisedMouseY(ev->clientY);""",
     """static bool updateMenuMousePosition(const EmscriptenMouseEvent *ev, bool markMove, bool clampOutside = false) {
    if (!ev) return false;
    // Clamp cursor during drags, including when mouse-up occurs off-canvas.
    const double nx = webInputNormalisedMouseX(ev->clientX, clampOutside ? 1 : 0);
    const double ny = webInputNormalisedMouseY(ev->clientY, clampOutside ? 1 : 0);"""),
    ('if (!std::strcmp(ev->code, "Backquote")) {\n        const bool relock = eventType == EMSCRIPTEN_EVENT_KEYDOWN && backquoteClosesToGameplay();',
     'if (!std::strcmp(ev->code, "Backquote") || !std::strcmp(ev->code, "Escape")) {\n'
     '        const bool relock = eventType == EMSCRIPTEN_EVENT_KEYDOWN && backquoteClosesToGameplay();'),
    ('std::fprintf(stderr, "[LCE-Web][ui-back] Backquote %s menu=%d relock=%d\\n",',
     'std::fprintf(stderr, "[LCE-Web][ui-back] %s %s menu=%d relock=%d\\n",\n'
     '            ev->code,'),
    ('if (updateMenuMousePosition(ev, (ev->movementX != 0 || ev->movementY != 0))) {',
     'if (updateMenuMousePosition(ev, (ev->movementX != 0 || ev->movementY != 0), gMenuMouseLeftHeld)) {'),
    ("""        if (!updateMenuMousePosition(ev, true)) return EM_FALSE;
        if (ev->button == 0) gMenuMousePending |= down ? WEB_MENU_MOUSE_LEFT_DOWN : WEB_MENU_MOUSE_LEFT_UP;""",
     """        const bool wasDragging = gMenuMouseLeftHeld;
        const bool valid = updateMenuMousePosition(ev, true, wasDragging);
        if (ev->button == 0) {
            if (down && !valid) return EM_FALSE;
            gMenuMouseLeftHeld = down;
            // Always deliver mouse-up to release capture, even off stage.
            gMenuMousePending |= down ? WEB_MENU_MOUSE_LEFT_DOWN : WEB_MENU_MOUSE_LEFT_UP;
        }
        if (!valid && ev->button != 0) return EM_FALSE;"""),
    ('for (const char *code : codes) if (keyDown(code)) buttons |= buttonForKeyCode(code);',
     'for (const char *code : codes) if (keyDown(code)) buttons |= buttonForKeyCode(code);\n'
     '    if (keyDown("Escape"))\n'
     '        buttons |= p.menuDisplayed ? _360_JOY_BUTTON_B : _360_JOY_BUTTON_START;'),
])

apply("Minecraft.Client/Common/UI/UIScene.h", [
    ('\tvirtual bool usesWebNativePointerMouse() const { return false; }',
     '\tvirtual bool usesWebNativePointerMouse() const { return false; }\n'
     '#ifdef __EMSCRIPTEN__\n'
     '\t// Capture belongs to the native scene, not the input bridge.\n'
     '\tUIControl *m_webDragSlider = NULL;\n'
     '\tS32 m_webDragSliderX = 0;\n'
     '\tS32 m_webDragSliderWidth = 1;\n'
     '#endif')
])

apply("Minecraft.Client/Common/UI/UIScene.cpp", [
    ('\t\t\tUIControl *hitControl = NULL;',
     '\t\t\tUIControl *hitControl = NULL;\n'
     '\t\t\tS32 hitX = 0, hitWidth = 0;'),
    ('\t\t\t\t\thitControl = control;\n\t\t\t\t\tbreak;',
     '\t\t\t\t\thitControl = control;\n'
     '\t\t\t\t\thitX = x; hitWidth = w;\n'
     '\t\t\t\t\tbreak;'),
    ("""\t\t\tIggyEventResult result;
\t\t\tif(mouseBits & WEB_MENU_MOUSE_MOVE)""",
     """\t\t\t// Native control geometry already accounts for Flash transforms.
\t\t\t// Use the original SetRelativeSliderPos Flash control method.
\t\t\tif((mouseBits & WEB_MENU_MOUSE_LEFT_DOWN) && m_bCanHandleInput)
\t\t\t{
\t\t\t\tm_webDragSlider = (hitControl && hitControl->getControlType() == UIControl::eSlider)
\t\t\t\t\t? hitControl : NULL;
\t\t\t\tif(m_webDragSlider)
\t\t\t\t{
\t\t\t\t\tm_webDragSliderX = hitX;
\t\t\t\t\tm_webDragSliderWidth = std::max(1, hitWidth);
\t\t\t\t}
\t\t\t}
\t\t\tconst bool nativeSliderDrag = m_webDragSlider != NULL;
\t\t\tif(nativeSliderDrag && (mouseBits & (WEB_MENU_MOUSE_LEFT_DOWN | WEB_MENU_MOUSE_MOVE | WEB_MENU_MOUSE_LEFT_UP)))
\t\t\t{
\t\t\t\tconst float fraction = std::max(0.0f, std::min(1.0f,
\t\t\t\t\t(float)(stageX - m_webDragSliderX) / (float)std::max(1, m_webDragSliderWidth)));
\t\t\t\tstatic_cast<UIControl_Slider *>(m_webDragSlider)->SetSliderTouchPos(fraction);
\t\t\t}
\t\t\tIggyEventResult result;
\t\t\tif(mouseBits & WEB_MENU_MOUSE_MOVE)"""),
    ('if(hitControl && hitControl->getId() != m_iFocusControl)',
     'if(hitControl && !nativeSliderDrag && hitControl->getId() != m_iFocusControl)'),
    ('if(hitControl && (mouseBits & WEB_MENU_MOUSE_LEFT_UP) && m_bCanHandleInput)',
     'if(hitControl && !nativeSliderDrag && (mouseBits & WEB_MENU_MOUSE_LEFT_UP) && m_bCanHandleInput)'),
    ("""\t\t\t\t\tif(mouseBits & buttons[i].bit)
\t\t\t\t\t{
\t\t\t\t\t\tIggyEvent event;""",
     """\t\t\t\t\tif(mouseBits & buttons[i].bit)
\t\t\t\t\t{
\t\t\t\t\t\tif(nativeSliderDrag && (buttons[i].bit == WEB_MENU_MOUSE_LEFT_DOWN ||
\t\t\t\t\t\t    buttons[i].bit == WEB_MENU_MOUSE_LEFT_UP)) continue;
\t\t\t\t\t\tIggyEvent event;"""),
    ('\t\t\tlce_web_menu_mouse_consume(mouseBits);',
     '\t\t\tif(mouseBits & WEB_MENU_MOUSE_LEFT_UP) m_webDragSlider = NULL;\n'
     '\t\t\tlce_web_menu_mouse_consume(mouseBits);')
])
print("LCE_FIX118_NATIVE_INPUT_PATCH_COMPLETE")
