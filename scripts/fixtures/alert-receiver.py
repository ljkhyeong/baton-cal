"""격리된 스모크 알림만 받아 알림명과 상태를 메모리에 보관한다."""

import json
from http.server import BaseHTTPRequestHandler, HTTPServer


class Receiver(BaseHTTPRequestHandler):
    events = []

    def do_POST(self):
        payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.path.startswith("/healthchecks/"):
            # 외부에는 고정된 정상 신호만 보낸다. 내부 주소·알림 내용은 포함하지 않는다.
            assert payload == {"service": "baton-cal", "status": "alive"}, payload
            self.events.append({"channel": "healthchecks"})
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"OK")
            return
        if self.path.startswith(("/slack/", "/discord/")):
            channel = self.path.split("/")[1]
            message = payload["attachments" if channel == "slack" else "embeds"][0]
            self.events.append({
                "channel": channel,
                "title": message["title"],
                "text": message["text" if channel == "slack" else "description"],
            })
            self.send_response(200 if channel == "slack" else 204)
            self.end_headers()
            if channel == "slack":
                self.wfile.write(b"ok")
            return
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
