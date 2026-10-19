#!/usr/bin/env python3
"""Map Escape to existing native pause/back input outside menus."""
from pathlib import Path
import sys
p=Path(sys.argv[1]);s=p.read_text(encoding='utf-8')
marker='/* FIX116_ESCAPE_NATIVE_BACK */'
js="""<script>
/* FIX116_ESCAPE_NATIVE_BACK */
(() => {
  let translated = false;
  function nativeMenuVisible() {
    try { return !!globalThis.LCEWebIggy?.debugState?.()?.menuVisible; }
    catch (_) { return true; }
  }
  function forward(type, from) {
    const mapped = new KeyboardEvent(type, {
      key:String.fromCharCode(96), code:'Backquote', bubbles:true, cancelable:true,
      ctrlKey:from.ctrlKey, altKey:from.altKey, shiftKey:from.shiftKey,
      metaKey:from.metaKey, repeat:from.repeat
    });
    window.dispatchEvent(mapped);
  }
  for (const type of ['keydown','keyup']) {
    window.addEventListener(type, e => {
      if (e.code !== 'Escape' || e.target instanceof HTMLInputElement ||
          e.target instanceof HTMLTextAreaElement || e.target?.isContentEditable) return;
      if (type==='keydown' && !e.repeat && !nativeMenuVisible()) translated = true;
      if (!translated) return;
      e.preventDefault();
      e.stopImmediatePropagation();
      forward(type,e);
      if (type==='keyup') translated=false;
    },true);
  }
})();
</script>
"""
if marker not in s:
    assert '</head>' in s, 'HTML head missing'
    s=s.replace('</head>',js+'</head>',1)
p.write_text(s,encoding='utf-8')
print('FIX116_ESCAPE_BROWSER_NATIVE_KEY_PATCHED')
