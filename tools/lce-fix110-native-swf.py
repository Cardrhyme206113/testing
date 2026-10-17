#!/usr/bin/env python3
"""Fix original LCE Flash artwork in its SWF display list. No DOM overlays."""
import argparse,struct,zlib,base64
from pathlib import Path
from PIL import Image

class Bits:
 def __init__(self):self.parts=[]
 def u(self,n,v):
  if n:self.parts.append(format(v&((1<<n)-1),f'0{n}b'))
 s=u
 def value(self):
  b=''.join(self.parts);b+='0'*(-len(b)%8)
  return bytes(int(b[i:i+8],2) for i in range(0,len(b),8))
def signed_n(vals):
 n=1
 for v in vals:
  while v < -(1<<(n-1)) or v >= (1<<(n-1)):n+=1
 return n
def rect(x0,x1,y0,y1):
 b=Bits();v=(x0,x1,y0,y1);n=signed_n(v);b.u(5,n)
 for x in v:b.s(n,x)
 return b.value()
def matrix(x=0,y=0):
 b=Bits();b.u(1,0);b.u(1,0);v=(round(x*20),round(y*20));n=signed_n(v);b.u(5,n)
 for x in v:b.s(n,x)
 return b.value()
def tag(code,p):
 n=len(p)
 return struct.pack('<H',(code<<6)|(n if n<63 else 63))+(struct.pack('<I',n) if n>=63 else b'')+p
def tags(raw,off):
 while off<len(raw):
  h=struct.unpack_from('<H',raw,off)[0];off+=2;c=h>>6;n=h&63
  if n==63:n=struct.unpack_from('<I',raw,off)[0];off+=4
  p=raw[off:off+n];off+=n
  yield c,p
  if c==0:break
def read(path):
 data=path.read_bytes()
 assert data[:3] in (b'CWS',b'FWS'),path
 b=b'FWS'+data[3:8]+zlib.decompress(data[8:]) if data[:3]==b'CWS' else data
 assert len(b)==struct.unpack_from('<I',b,4)[0],path
 bits=''.join(f'{x:08b}' for x in b[8:16]);n=int(bits[:5],2)
 off=8+(5+4*n+7)//8+4
 return b[:off],list(tags(b,off)),data[3]
def write(path,head,tl,version):
 b=head+b''.join(tag(c,p) for c,p in tl)
 b=b[:4]+struct.pack('<I',len(b))+b[8:]
 path.write_bytes(b'CWS'+bytes([version])+b[4:8]+zlib.compress(b[8:],9))
def shape(cid,w,h,color):
 w=int(w*20);h=int(h*20);b=Bits()
 b.u(4,1);b.u(4,0) # one fill style, zero line styles
 for k,v in [(1,0),(1,0),(1,0),(1,1),(1,0),(1,1),(5,1),(1,0),(1,0),(1,1)]:b.u(k,v)
 for v,vertical in ((w,0),(h,1),(-w,0),(-h,1)):
  n=max(2,signed_n((v,)))
  for k,x in ((1,1),(1,1),(4,n-2),(1,0),(1,vertical)):b.u(k,x)
  b.s(n,v)
 b.u(1,0);b.u(5,0)
 return 32,struct.pack('<H',cid)+rect(0,w,0,h)+bytes((1,0,*color,0))+b.value()
def place(cid,depth,x,y,name=None):
 flags=0x06|(0x20 if name else 0)
 p=bytes((flags,))+struct.pack('<HH',depth,cid)+matrix(x,y)
 if name:p+=name.encode()+b'\0'
 return 26,p
def panel(cid,w,h,recess=False):
 edge=3 if recess else 4
 outer='#202020' if recess else '#171717'
 inside='#777777' if recess else '#c6c6c6'
 shine='#b2b2b2' if recess else '#f7f7f7'
 shadow='#373737' if recess else '#555555'
 blocks=[(0,0,w,h,outer),(edge,edge,w-2*edge,h-2*edge,inside),
         (edge,edge,w-edge,2,shine),(edge,edge,2,h-edge,shine),
         (0,h-edge,w,edge,shadow),(w-edge,0,edge,h,shadow)]
 defs=[];timeline=[]
 for d,(x,y,sw,sh,c) in enumerate(blocks,1):
  definitions=shape(cid+d,sw,sh,(*bytes.fromhex(c[1:]),255))
  defs.append(definitions);timeline.append(place(cid+d,d,x,y))
 payload=struct.pack('<HH',cid,1)+b''.join(tag(c,p) for c,p in timeline)+tag(1,b'')+tag(0,b'')
 return defs+[(39,payload)]
def prepend_definitions(tl,defs):
 i=next(i for i,(c,_) in enumerate(tl) if c in (39,26,70))
 tl[i:i]=defs
def change_createworld(path):
 head,tl,v=read(path);out=[];found=False
 for code,p in tl:
  if code==39 and int.from_bytes(p[:2],'little')==5:
   sid,frames=struct.unpack_from('<HH',p)
   inside=[]
   for c,q in tags(p,4):
    if c==70 and len(q)>=4 and int.from_bytes(q[2:4],'little')==2:
     inside.append(place(6100,2,26,264));found=True
    else:inside.append((c,q))
   p=struct.pack('<HH',sid,frames)+b''.join(tag(c,q) for c,q in inside)
  out.append((code,p))
 assert found,'Create World recess not found'
 prepend_definitions(out,panel(6100,440,64,True));write(path,head,out,v)
def change_controls(path):
 head,tl,v=read(path);out=[];found=False
 for code,p in tl:
  if code==70 and len(p)>=4 and int.from_bytes(p[2:4],'little')==1 and not found:
   out.append(place(6200,1,400,194,'BackgroundPanel'));found=True
  else:out.append((code,p))
 assert found,'Controls panel not found'
 prepend_definitions(out,panel(6200,480,128));write(path,head,out,v)
def change_logo(path,ids,logo):
 head,tl,v=read(path);out=[];found=set()
 for code,p in tl:
  if code==36 and int.from_bytes(p[:2],'little') in ids:
   cid,fmt,w,h=struct.unpack_from('<HBHH',p);assert fmt==5
   scale=min(w/logo.width,h/logo.height)
   dw=max(1,round(logo.width*scale));dh=max(1,round(logo.height*scale))
   resized=logo.resize((dw,dh),Image.Resampling.LANCZOS)
   im=Image.new('RGBA',(w,h),(0,0,0,0))
   im.paste(resized,((w-dw)//2,(h-dh)//2))
   pixeldata=bytes(z for rr,gg,bb,aa in im.getdata()
      for z in (aa,(rr*aa+127)//255,(gg*aa+127)//255,(bb*aa+127)//255))
   p=struct.pack('<HBHH',cid,5,w,h)+zlib.compress(pixeldata,9);found.add(cid)
  out.append((code,p))
 assert found==set(ids),('unmatched logo IDs',ids,found)
 write(path,head,out,v)
def main():
 pa=argparse.ArgumentParser();pa.add_argument('site',type=Path);a=pa.parse_args()
 folder=a.site/'ui-assets/runtime';assert folder.is_dir()
 raw=base64.b64decode(Path(__file__).with_name('lce-fix110-transparent-logo.b64').read_text().strip())
 import io
 logo=Image.open(io.BytesIO(raw)).convert('RGBA')
 assert logo.width>200 and logo.height>40,logo.size
 assert logo.getpixel((0,0))[3]==0, 'Original alpha lost'
 assert logo.getchannel('A').getextrema()==(0,255), 'Opaque/matte image supplied'
 change_createworld(folder/'CreateWorldMenu720.swf')
 change_controls(folder/'Controls720.swf')
 change_logo(folder/'platformskin.swf',(107,),logo)
 change_logo(folder/'platformskinHD.swf',(180,100),logo)
 print('FIX110_SWF_PATCH_DONE original transparent artwork in native Flash bitmaps')
if __name__=='__main__':main()
