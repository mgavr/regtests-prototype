"""The live view: reveals real steps one at a time, and streams them to the page and the terminal."""
import json
import pathlib
import queue
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from stages import STAGES, Demo, recordings_on_disk

STATIC = pathlib.Path(__file__).parent / "static"
CONTENT_TYPES = {".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8",
                 ".css": "text/css; charset=utf-8"}
STEP_SECONDS = 1.0


class Director(threading.Thread):
    """Holds real events back and shows them one per tick. Real actions run only when their turn comes."""

    def __init__(self):
        super().__init__(daemon=True)
        self.demo = Demo()
        self.lock = threading.Condition()
        self.subscribers = []
        self.playing = False
        self.interval = STEP_SECONDS
        self.want_one = False
        self.want_restart = False
        self.reset()

    def reset(self):
        self.steps = []
        self.waiting = []
        self.actions = []
        self.stage = STAGES[0]["id"]
        self.busy = "Starting the database, partner and service"
        self.error = None
        self.finished = False

    def command(self, name, interval=None):
        with self.lock:
            if name == "run":
                self.playing = True
                self.interval = interval or STEP_SECONDS
            elif name == "pause":
                self.playing = False
            elif name == "next":
                self.playing = False
                self.want_one = True
            elif name == "restart":
                self.playing = False
                self.want_restart = True
            self.lock.notify_all()
        self.broadcast()

    def state(self):
        return {"stages": STAGES, "stage": self.stage, "steps": self.steps, "playing": self.playing,
                "busy": self.busy, "finished": self.finished, "error": self.error,
                "processes": self.demo.processes.snapshot(), "recordings": recordings_on_disk()}

    def subscribe(self):
        subscriber = queue.Queue()
        self.subscribers.append(subscriber)
        return subscriber

    def unsubscribe(self, subscriber):
        self.subscribers.remove(subscriber)

    def broadcast(self):
        message = json.dumps(self.state())
        for subscriber in list(self.subscribers):
            subscriber.put(message)

    def run(self):
        self.prepare()
        while True:
            with self.lock:
                while not (self.want_restart or self.want_one or (self.playing and not self.finished)):
                    self.lock.wait()
                restart, self.want_restart = self.want_restart, False
                self.want_one = False
            if restart:
                self.prepare()
                continue
            self.advance()
            with self.lock:
                if self.playing and not self.finished and not self.want_restart:
                    self.lock.wait(self.interval)

    def prepare(self):
        self.reset()
        self.broadcast()
        try:
            self.demo.prepare()
            self.actions = self.demo.actions()
            print("Ready. Open http://localhost:8080 and press Run or Next.", flush=True)
        except Exception as error:  # A failed start must reach the page, not vanish in a thread.
            self.fail(error)
        self.busy = None
        self.broadcast()

    def advance(self):
        if self.error:
            return
        while not self.waiting and self.actions:
            stage, busy, action = self.actions.pop(0)
            self.stage = stage
            self.busy = busy
            self.broadcast()
            try:
                action()
            except Exception as error:
                self.fail(error)
            self.busy = None
            self.waiting = [{**step, "stage": stage} for step in self.demo.take_pending()]
        if self.waiting:
            self.reveal(self.waiting.pop(0))
        if not self.waiting and not self.actions:
            self.finished = True
            self.playing = False
        self.broadcast()

    def reveal(self, step):
        step["n"] = len(self.steps) + 1
        self.steps.append(step)
        label = f" [{step['label']}]" if step.get("label") else ""
        print(f"[{step['stage']:6}] {step['reg']:6} {step['title']}{label}\n"
              f"         {step['detail']}\n         {step['file']}", flush=True)
        for found in (step.get("report") or {}).get("differences", []):
            print(f"         DIFF {found['where']} {found['path']}: expected {found['expected']}, got {found['actual']}", flush=True)

    def fail(self, error):
        self.error = str(error)
        self.playing = False
        print(f"ERROR: {error}", flush=True)


def make_handler(director):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            path = urlparse(self.path).path
            if path == "/api/state":
                self.send(200, "application/json", json.dumps(director.state()).encode())
            elif path == "/api/events":
                self.stream()
            else:
                self.serve_static("index.html" if path == "/" else path.lstrip("/"))

        def do_POST(self):
            url = urlparse(self.path)
            name = url.path.removeprefix("/api/")
            if name not in ("run", "pause", "next", "restart"):
                self.send(404, "text/plain", b"not found")
                return
            interval = parse_qs(url.query).get("interval")
            director.command(name, float(interval[0]) if interval else None)
            self.send(200, "application/json", b'{"ok":true}')

        def serve_static(self, name):
            file = STATIC / name
            if file.parent != STATIC or not file.is_file():
                self.send(404, "text/plain", b"not found")
                return
            self.send(200, CONTENT_TYPES.get(file.suffix, "application/octet-stream"), file.read_bytes())

        def send(self, status, content_type, body):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def stream(self):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            subscriber = director.subscribe()
            try:
                self.push(json.dumps(director.state()))
                while True:
                    try:
                        self.push(subscriber.get(timeout=2))
                    except queue.Empty:
                        self.push(json.dumps(director.state()))  # Keeps the live process list fresh.
            except OSError:
                pass
            finally:
                director.unsubscribe(subscriber)

        def push(self, message):
            self.wfile.write(f"data: {message}\n\n".encode())
            self.wfile.flush()

    return Handler


def serve(port=8080):
    director = Director()
    server = ThreadingHTTPServer(("0.0.0.0", port), make_handler(director))
    server.daemon_threads = True
    director.start()
    print(f"Live view on http://localhost:{port}. Preparing the database, partner and service...", flush=True)
    try:
        server.serve_forever()
    finally:
        director.demo.processes.stop_all()


def wait_forever():
    while True:
        time.sleep(3600)
