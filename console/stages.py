"""The three stages of the demo: real actions, plus the steps that describe them."""
import json
import os
import pathlib
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

from processes import APP, DATA, DB_PORT, EVENTS_FILE, PARTNER_PORT, SERVICE_PORT, Processes, find_pids, port_open

RECORDINGS = DATA / "recordings"
THIS_FILE = "console/stages.py"
AUTOCONFIG = "regtests-capture-starter/src/main/java/example/regtests/RegtestsAutoConfiguration.java"
QUOTE_SERVICE = "demo-service/src/main/java/example/quotes/QuoteService.java"

# The console is the only place that knows how this particular service reaches its database.
# The library sees JDBC calls and nothing else, which is the point.
PROCEDURE_VIA = "REG-5 demo-service/src/main/resources/mappers/QuoteMapper.xml"
PLAIN_VIA = "REG-6 demo-service/src/main/java/example/quotes/AuditWriter.java"

STAGES = [
    {"id": "record", "title": "Record", "summary": "Database and partner up. One real request is captured."},
    {"id": "replay", "title": "Replay", "summary": "Database and partner stopped. A Python test replays the case."},
    {"id": "break", "title": "Break it", "summary": "One line changed. The same test fails and says why."},
]


def sample_requests():
    return json.loads((APP / "console" / "sample_requests.json").read_text())


def seed_config(recordings):
    """The service's config file sits with its recordings."""
    recordings.mkdir(parents=True, exist_ok=True)
    target = recordings / "regtests.json"
    if not target.exists():
        shutil.copy(APP / "recordings" / "regtests.json", target)


def post_quote(case_name, body):
    request = urllib.request.Request(f"http://localhost:{SERVICE_PORT}/quotes", method="POST",
                                     data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json", "X-Regtests-Case": case_name})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code


class EventTail:
    """Reads new lines from the file that the library and the test append to."""

    def __init__(self):
        self.offset = 0

    def reset(self):
        self.offset = 0

    def read(self):
        with open(EVENTS_FILE, "rb") as events:
            events.seek(self.offset)
            data = events.read()
        complete = data[:data.rfind(b"\n") + 1]
        self.offset += len(complete)
        return [json.loads(line) for line in complete.splitlines() if line.strip()]


class Demo:
    def __init__(self):
        self.processes = Processes()
        self.tail = EventTail()
        self.pending = []

    def prepare(self):
        """Gets stage 1 ready, so that pressing Run starts with everything already up."""
        self.processes.stop_all()
        self.processes.reset_data()
        self.tail.reset()
        self.pending = []
        shutil.rmtree(RECORDINGS, ignore_errors=True)
        seed_config(RECORDINGS)
        # REG-2: the real thing. MariaDB and the fake partner service each run as their own process, and the service connects to both.
        self.processes.start_database()
        self.processes.start_partner()
        self.processes.start_service("record", RECORDINGS)

    def actions(self):
        """Each action does something real. None runs until the steps before it have been shown."""
        return [
            ("record", None, self.show_what_is_up),
            ("record", "Sending a real request to the service", self.send_live_request),
            ("replay", "Stopping the database", self.stop_database),
            ("replay", "Stopping the partner", self.stop_partner),
            ("replay", None, self.prove_they_are_down),
            ("replay", "Starting the service with the database and partner down", self.start_replay_service),
            ("replay", "Running the Python test", self.run_test),
            ("break", "Restarting the service with DEMO_BREAK=true", self.start_broken_service),
            ("break", "Running the same Python test", self.run_test),
        ]

    def say(self, reg, file, title, detail, tone=None):
        self.pending.append({"ts": int(time.time() * 1000), "source": "console", "reg": reg, "file": file,
                             "title": title, "detail": detail, "tone": tone})

    def collect(self):
        """Moves new real events into the list of steps waiting to be shown."""
        for event in self.tail.read():
            sql = (event.get("data") or {}).get("sql")
            if sql:
                is_procedure = sql.lstrip().lower().startswith("{call")
                event["label"] = "stored procedure via MyBatis" if is_procedure else "plain JDBC"
                event["via"] = PROCEDURE_VIA if is_procedure else PLAIN_VIA
            if event.get("verdict"):
                event["tone"] = "pass" if event["verdict"] == "PASS" else "fail"
            self.pending.append(event)

    def take_pending(self):
        self.collect()
        steps, self.pending = self.pending, []
        return steps

    def show_what_is_up(self):
        rows = {row["name"]: row for row in self.processes.snapshot()}
        self.say("REG-2", THIS_FILE, "Database is up", f"MariaDB is its own process, pid {rows['database']['pid']}, port {DB_PORT}.")
        self.say("REG-2", THIS_FILE, "Fake partner service is up", f"The fake partner service is its own process, pid {rows['partner']['pid']}, port {PARTNER_PORT}.")
        self.say("REG-1", AUTOCONFIG, "Service is up in record mode", f"pid {rows['service']['pid']}, REGTESTS_MODE=record.")

    def send_live_request(self):
        live = sample_requests()["live"]
        post_quote(live["case"], live["body"])
        self._wait_for_event("Case saved")

    def stop_database(self):
        # REG-14: the point of the demo. The database and partner are really stopped, and the next step checks that they are gone.
        pid = self.processes.running["database"].pid
        self.processes.stop("database")
        self.say("REG-14", THIS_FILE, "Database stopped", f"Process {pid} has exited.", "stop")

    def stop_partner(self):
        pid = self.processes.running["partner"].pid
        self.processes.stop("partner")
        self.say("REG-14", THIS_FILE, "Fake partner service stopped", f"Process {pid} has exited.", "stop")

    def prove_they_are_down(self):
        left = find_pids("mariadbd") + find_pids("fake-partner.jar")
        ports = [port for port in (DB_PORT, PARTNER_PORT) if port_open(port)]
        if left or ports:
            raise RuntimeError(f"still running: pids {left}, ports {ports}")
        self.say("REG-14", THIS_FILE, "Checked: both are gone",
                 f"No such process in /proc, and ports {DB_PORT} and {PARTNER_PORT} refuse connections.", "stop")

    def start_replay_service(self):
        self.processes.stop("service")
        self.processes.start_service("replay", RECORDINGS)
        pid = self.processes.running["service"].pid
        self.say("REG-1", AUTOCONFIG, "Service starts, database and partner are down",
                 f"pid {pid}, REGTESTS_MODE=replay. It makes no calls at startup, so nothing is missing yet.")

    def start_broken_service(self):
        self.processes.stop("service")
        self.processes.start_service("replay", RECORDINGS, demo_break=True)
        self.say("REG-25", QUOTE_SERVICE, "Service restarted with DEMO_BREAK=true",
                 "One line of pricing now adds the discount instead of taking it off. Nothing else changed.")

    def run_test(self):
        env = {**os.environ, "REGTESTS_RECORDINGS": str(RECORDINGS), "REGTESTS_EVENTS": str(EVENTS_FILE),
               "REGTESTS_ONLY_CASE": sample_requests()["live"]["case"]}
        subprocess.run([sys.executable, "-m", "pytest", "tests/test_replay.py", "-q", "--tb=short", "-p", "no:cacheprovider"],
                       cwd=APP, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def _wait_for_event(self, title_starts, timeout=20):
        deadline = time.time() + timeout
        while time.time() < deadline:
            self.collect()
            if any(step.get("title", "").startswith(title_starts) for step in self.pending):
                return
            time.sleep(0.05)
        raise RuntimeError(f"no event starting with '{title_starts}' arrived")


def recordings_on_disk():
    """What the run has really written, for the page and for anyone checking from outside."""
    if not RECORDINGS.exists():
        return []
    return sorted(str(path.relative_to(RECORDINGS)) for path in pathlib.Path(RECORDINGS).rglob("*.json"))
