#!/usr/bin/env python3
"""Inspect actual authored Flash SWF shape names rather than guessing geometry."""
from pathlib import Path
import zlib,re
ROOT=Path('site/ui-assets/runtime')
cases=['CreateWorldMenu720.swf','LaunchMoreOptionsMenu720.swf','OptionsMenu720.swf','SettingsOptions720.swf','SettingsGraphics720.swf','SettingsUI720.swf','MainMenu720.swf','HowToPlay720.swf','SkinSelect720.swf','Graphics720.swf','Settings720.swf']
for s in cases:
 p=ROOT/s
 if not p.exists():
  opts=list(ROOT.glob('*'+s.replace('Menu720.swf','')+'*'))
  print('MISSING',s,'other', [z.name for z in opts[:9]])
  continue
 raw=p.read_bytes()
 try:
  if raw[:3]==b'CWS':raw=raw[:8]+zlib.decompress(raw[8:])
  if raw[:3]==b'ZWS':print('ZWS_SKIP',s);continue
 except Exception as e: print('DECOMPRESS_ERROR',s,e);continue
 strings=re.findall(rb'[A-Za-z_][A-Za-z0-9_.$ /-]{3,}',raw)
 names=[]
 for x in strings:
  y=x.decode('latin1','ignore')
  if any(token in y.lower() for token in ['panel','frame','border','bg','background','scale9','9grid','checkbox','options','holder','header','window','container','mask','color','skin']):
   if len(y)<130 and y not in names:names.append(y)
 print('SWF',s,'bytes',len(raw),'NAMES',len(names),'\n ',repr(names[:125]))
