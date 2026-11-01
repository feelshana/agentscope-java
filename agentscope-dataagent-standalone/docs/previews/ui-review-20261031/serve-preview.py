"""Serve only this offline review artifact on loopback; no directory listing or APIs."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit
import mimetypes

root = Path(__file__).resolve().parent
entry = root.parent / "dataagent-ui-review-20261031.html"
assets = {
    "review.css", "review.js", "brand-mark.svg", "scheduled-tasks-calendar.png",
    "01-home.png", "02-chat.png", "03-knowledge.png", "04-modeling.png",
}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        route = urlsplit(self.path).path
        if route in ("/", "/dataagent-ui-review-20261031.html"):
            target = entry
        elif route.startswith("/ui-review-20261031/") and route.rsplit("/", 1)[1] in assets:
            name = route.removeprefix("/ui-review-20261031/")
            if name not in assets:
                self.send_error(404)
                return
            target = root / name
        else:
            self.send_error(404)
            return
        payload = target.read_bytes()
        content_type = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
        if target.suffix in (".html", ".css", ".js", ".svg"):
            content_type += "; charset=utf-8"
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(payload)


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 19134), Handler).serve_forever()
