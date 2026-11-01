"""Serve this design proposal on loopback, with a fixed asset allowlist and no API."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit
import mimetypes

root = Path(__file__).resolve().parent
allowed = {"index.html", "preview.css", "preview.js", "assets/brand.svg", "product-glyphs.js"}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        name = urlsplit(self.path).path.lstrip("/") or "index.html"
        if name not in allowed:
            self.send_error(404)
            return
        payload = (root / name).read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", (mimetypes.guess_type(name)[0] or "application/octet-stream") + "; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(payload)


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 19138), Handler).serve_forever()
