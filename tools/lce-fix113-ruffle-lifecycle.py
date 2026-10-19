#!/usr/bin/env python3
"""Fix the Ruffle 0.6 global event listener retention in compiled WebIggy JS.

Ruffle InnerPlayer adds pointerdown on the document root and visibilitychange
on document, both closing over the player. Its disconnectedCallback destroys
WASM but leaves global listeners. An AbortController owns both subscriptions.
Abort on removal, not on normal movie reload.
"""
from pathlib import Path
import argparse

def once(text, old, new, label):
    if text.count(old) != 1:
        raise ValueError(f'{label}: expected one anchor; found {text.count(old)}')
    return text.replace(old, new, 1)

def patch_runtime(bundle: str) -> str:
    if 'LCE_FIX113_GLOBAL_LISTENERS' in bundle:
        return bundle
    bundle = once(bundle,
        'document.documentElement.addEventListener("pointerdown",this.checkIfTouch.bind(this))',
        'this.__lceFix113Events=new AbortController,document.documentElement.addEventListener("pointerdown",this.checkIfTouch.bind(this),{signal:this.__lceFix113Events.signal})',
        'document pointerdown')
    bundle = once(bundle,
        'this.instance.audio_context()?.resume())})}startBackgroundTick(){',
        'this.instance.audio_context()?.resume())},{signal:this.__lceFix113Events.signal})}startBackgroundTick(){',
        'document visibilitychange')
    bundle = once(bundle,
        'destroy(){this.instance&&(this.stopBackgroundTick(),this.instance.destroy()',
        'destroy(){/*LCE_FIX113_GLOBAL_LISTENERS*/this.element.isConnected||this.__lceFix113Events?.abort(),this.instance&&(this.stopBackgroundTick(),this.instance.destroy()',
        'disconnect destroy')
    return bundle

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('bundle',type=Path)
    args=parser.parse_args()
    src=args.bundle.read_text('utf-8')
    dst=patch_runtime(src)
    args.bundle.write_text(dst,encoding='utf-8')
    print('LCE Fix113 Ruffle global listener cleanup applied',len(dst))

if __name__=='__main__':
    main()
