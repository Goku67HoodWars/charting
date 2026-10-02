#!/usr/bin/env python3
"""One-click launcher for the Beebeelex sea chart.

Double-click this (or run `python sail.py`). It serves the map locally AND
proxies WikiSync so the "Look up live" button works with a single click —
no copy/paste. Close the window to stop.
"""
import http.server, socketserver, urllib.request, urllib.parse, urllib.error
import json, os, sys, threading, webbrowser

PORT = 8731
DIRECTORY = os.path.dirname(os.path.abspath(__file__))
PAGE = "index.html"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k):
        super().__init__(*a, directory=DIRECTORY, **k)

    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/wikisync") or self.path.startswith("/api/wikisync"):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            user = (q.get("u", [""])[0]).strip()
            if not user:
                return self._json(400, {"error": "missing username"})
            url = ("https://sync.runescape.wiki/runelite/player/"
                   + urllib.parse.quote(user) + "/STANDARD")
            try:
                req = urllib.request.Request(url, headers={"User-Agent": UA})
                with urllib.request.urlopen(req, timeout=15) as r:
                    data = r.read()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            except urllib.error.HTTPError as e:
                self._json(e.code, {"error": "WikiSync HTTP %d (is the name right, and has that account uploaded via the RuneLite WikiSync plugin?)" % e.code})
            except Exception as e:
                self._json(502, {"error": str(e)})
            return
        return super().do_GET()

    def log_message(self, *a):
        pass


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True  # one slow WikiSync call must never block page loads


def main():
    url = "http://localhost:%d/%s" % (PORT, PAGE)
    try:
        httpd = Server(("127.0.0.1", PORT), Handler)
    except OSError:
        print("Port %d is busy. Close the other server (or whatever is using it) and retry." % PORT)
        sys.exit(1)
    threading.Timer(0.6, lambda: webbrowser.open(url)).start()
    print("Sea chart running at", url)
    print("Live WikiSync lookup is enabled. Close this window to stop.")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
