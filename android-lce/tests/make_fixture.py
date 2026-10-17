from pathlib import Path
import struct,sys
names=['*TestMenu720.swf','languages.loc','Another.swf']
start=4+sum(2+len(s.encode())+8 for s in names)
items=[struct.pack('>I',len(names))]
for name in names:
  b=name.encode()
  items.append(struct.pack('>H',len(b))+b+struct.pack('>II',start,1))
  start+=1
Path(sys.argv[1]).write_bytes(b''.join(items)+b'abc')
