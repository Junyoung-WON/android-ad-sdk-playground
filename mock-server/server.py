#!/usr/bin/env python3
"""Local banner fixtures only; no external ad service or tracking."""

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import struct
import time
from urllib.parse import parse_qs, urlparse
from uuid import uuid4
import zlib


def banner_png():
    """Deterministic 640x200 RGB test pattern, generated without image dependencies."""
    width, height = 640, 200
    rows = bytearray()
    for y in range(height):
        rows.append(0)  # PNG filter: None
        for x in range(width):
            if 32 <= x < 168 and 32 <= y < 168:
                color = (110, 231, 183)
            elif 208 <= x < 580 and (50 <= y < 78 or 98 <= y < 118):
                color = (239, 246, 255)
            elif 208 <= x < 410 and 142 <= y < 166:
                color = (147, 197, 253)
            else:
                color = (30, 64 + x * 30 // width, 130 + y * 30 // height)
            rows.extend(color)

    def chunk(name, data):
        return struct.pack("!I", len(data)) + name + data + struct.pack("!I", zlib.crc32(name + data))

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack("!2I5B", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(rows)))
            + chunk(b"IEND", b""))


BANNER = banner_png()


class Handler(BaseHTTPRequestHandler):
    def send_body(self, status, body=b"", content_type="application/json; charset=utf-8"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass  # Cancellation/timeout on the client is an expected scenario.

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/banner.png":
            self.send_body(200, BANNER, "image/png")
            return
        if url.path == "/broken.png":
            self.send_body(200, b"not a PNG image", "image/png")
            return
        if url.path != "/ads":
            self.send_body(404)
            return
        query = parse_qs(url.query)
        if query.get("placementId", [""])[-1] != "demo-banner":
            self.send_body(204)
            return
        scenario = query.get("scenario", ["normal"])[-1]
        try:
            delay_ms = int(query.get("delayMs", ["0"])[-1])
        except ValueError:
            self.send_body(400, b'{"error":"delayMs must be an integer"}')
            return
        if not 0 <= delay_ms <= 4000:
            self.send_body(400, b'{"error":"delayMs must be between 0 and 4000"}')
            return
        # 실험 지연은 SDK가 아니라 서버에서 제어한다.
        if scenario != "slow":
            time.sleep(delay_ms / 1000)
        if scenario == "no-fill":
            self.send_body(204)
            return
        if scenario == "http-error":
            self.send_body(503)
            return
        if scenario == "invalid-response":
            self.send_body(200, b'{"adId": "missing-image-url"}')
            return
        if scenario == "slow":
            time.sleep(8)  # Longer than the SDK's 5-second per-call timeout.
        elif scenario not in {"normal", "image-failure"}:
            self.send_body(400)
            return
        image = "broken.png" if scenario == "image-failure" else "banner.png"
        # 같은 이미지라도 새로 제공한 광고 응답은 다른 adId로 구분한다.
        payload = {
            "adId": str(uuid4()),
            "imageUrl": f"http://{self.headers['Host']}/{image}",
        }
        self.send_body(200, json.dumps(payload).encode("utf-8"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Mock ad server: http://127.0.0.1:{args.port}/ads", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
