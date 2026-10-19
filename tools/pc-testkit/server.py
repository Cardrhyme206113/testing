#!/usr/bin/env python3
"""Local-only server for the REAL Emscripten LCE playable and original UI assets."""
import argparse
import mimetypes
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import threading
import webbrowser

mimetypes.add_type("application/wasm", ".wasm")
mimetypes.add_type("application/octet-stream", ".data")
mimetypes.add_type("application/javascript", ".mjs")
mimetypes.add_type("application/javascript", ".js")
mimetypes.add_type("application/octet-stream", ".swf")

class LocalLCEHandler(SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header("Cross-Origin-Opener-Policy", "same-origin")
        self.send_header("Cross-Origin-Embedder-Policy", "require-corp")
        self.send_header("Cross-Origin-Resource-Policy", "same-origin")
        self.send_header("Cache-Control", "no-store, max-age=0")
        super().end_headers()
    def guess_type(self, path):
        if path.endswith(".wasm"):
            return "application/wasm"
        if path.endswith(".data"):
            return "application/octet-stream"
        return super().guess_type(path)
    def log_message(self, format, *args):
        if "GET" not in format or "404" in format:
            super().log_message(format, *args)

def main():
    parser = argparse.ArgumentParser(description="Run LCE locally with WASM threads isolation headers")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--no-browser", action="store_true")
    opts = parser.parse_args()
    import os
    os.chdir(Path(__file__).resolve().parent)
    url = f"http://127.0.0.1:{opts.port}/lce_web_demo.html?lce_ui_test=1"
    try:
        server = ThreadingHTTPServer(("127.0.0.1", opts.port), LocalLCEHandler)
    except OSError as error:
        print("Server could not start:", error)
        print("Close any other instance of the test kit, then retry.")
        raise
    print("LCE local-only server:", url, flush=True)
    print("Stop with Ctrl+C. This server does NOT expose a public port.", flush=True)
    if not opts.no_browser:
        threading.Timer(1, lambda: webbrowser.open(url)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()

if __name__ == "__main__":
    main()
