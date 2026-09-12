"""격리된 스모크 알림만 받아 알림명과 상태를 메모리에 보관한다."""

import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer


class Receiver(BaseHTTPRequestHandler):
    events = []
    rejections = []
    attempts = {}
    failure_statuses = tuple(int(value) for value in os.environ.get("ALERT_TEST_FAILURES", "").split(",") if value)

    def reject_for_test(self, channel, title=None):
        if not self.failure_statuses:
            return False
        key = (channel, title)
        attempt = self.attempts.get(key, 0)
        self.attempts[key] = attempt + 1
        if attempt >= len(self.failure_statuses):
            return False
        status = self.failure_statuses[attempt]
        self.rejections.append({"channel": channel, "title": title, "status": status})
        self.send_response(status)
        self.send_header("Retry-After", "1")
        self.end_headers()
        self.wfile.write(b"temporary test failure")
        return True

    def do_POST(self):
        payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.path.startswith("/healthchecks/"):
            # 외부에는 고정된 정상 신호만 보낸다. 내부 주소·알림 내용은 포함하지 않는다.
            assert payload == {"service": "baton-cal", "status": "alive"}, payload
            if self.reject_for_test("healthchecks"):
                return
            self.events.append({"channel": "healthchecks"})
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"OK")
            return
        if self.path.startswith(("/slack/", "/discord/")):
            channel = self.path.split("/")[1]
            message = payload["attachments" if channel == "slack" else "embeds"][0]
            if self.reject_for_test(channel, message["title"]):
                return
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
        body = json.dumps(self.rejections if self.path == "/rejections" else self.events).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass


HTTPServer(("0.0.0.0", 8080), Receiver).serve_forever()
