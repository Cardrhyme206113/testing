#!/usr/bin/env python3
"""Move original World Options checkbox sprites below their native section divider."""
from pathlib import Path
import sys
p=Path(sys.argv[1] if len(sys.argv)>1 else 'site/lce_web_demo.js')
s=p.read_text()
marker='LCE_WEB_FIX78_WORLD_OPTIONS_SPACING'
if marker in s:raise SystemExit('Already patched')
old='''["CheckboxHostPrivileges",306], ["CheckboxStructures",369],
      ["CheckboxFlatWorld",403], ["CheckboxBonusChest",437]'''
new='''["CheckboxHostPrivileges",306], ["CheckboxStructures",381],
      ["CheckboxFlatWorld",415], ["CheckboxBonusChest",449]'''
if s.count(old)!=1:raise SystemExit('Could not find original sprite position table')
s=s.replace(old,new,1)
old='''// authored y values were directly observed before init, and confirmed via'''
new='''// authored y values were directly observed before init, and confirmed via'''
# The lower three sprites move by 12px to prevent the SWF World Options
# separator from obscuring Generate Structures text; this is original Iggy
# property manipulation (not a duplicate DOM component).
s=s.replace('''  // LCE_WEB_FIX76_RESTORE_ORIGINAL_AVM1_CONTROLS''',
'''  // LCE_WEB_FIX78_WORLD_OPTIONS_SPACING: native section divider obscures
  // the first World Options row in patched Ruffle. Shift that row and the two
  // following authored Flash sprites down 12px, preserving their live events.
  // LCE_WEB_FIX76_RESTORE_ORIGINAL_AVM1_CONTROLS''',1)
p.write_text(s)
print('Fix78 restored original SWF option rows and corrected section spacing')
