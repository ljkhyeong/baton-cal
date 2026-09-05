"""격리된 스모크 알림만 받아 알림명과 상태를 메모리에 보관한다."""

import json
from http.server import BaseHTTPRequestHandler, HTTPServer


class Receiver(BaseHTTPRequestHandler):
    events = []

    def do_POST(self):
        payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        self.events.extend(
            {"alertname": alert["labels"]["alertname"], "status": alert["status"]}
            for alert in payload["alerts"]
        )
        self.send_response(200)
        self.end_headers()

    def do_GET(self):
        body = json.dumps(self.events).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass


HTTPServer(("0.0.0.0", 8080), Receiver).serve_forever()
