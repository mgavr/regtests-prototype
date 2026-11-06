"""Starts, stops and inspects the real processes inside the container."""
import os
import pathlib
import shutil
import signal
import socket
import subprocess
import time

APP = pathlib.Path("/app")
DATA = pathlib.Path("/data")
DB_DIR = DATA / "mariadb"
DB_SOCKET = DB_DIR / "mysqld.sock"
LOGS = DATA / "logs"
EVENTS_FILE = DATA / "events.jsonl"
ACTUAL = DATA / "actual"

DB_PORT = 3306
PARTNER_PORT = 8082
SERVICE_PORT = 8081
DEBUG_PORT = 5005

JAVA = ["java", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-Xmx256m"]

# name -> (what to show, text that identifies the process in /proc, port)
KNOWN = {
    "database": ("MariaDB database", "mariadbd", DB_PORT),
    "partner": ("Fake partner service", "fake-partner.jar", PARTNER_PORT),
    "service": ("Demo service", "demo-service.jar", SERVICE_PORT),
}


def port_open(port):
    with socket.socket() as probe:
        probe.settimeout(0.3)
        return probe.connect_ex(("127.0.0.1", port)) == 0


def find_pids(marker):
    """Reads /proc directly, so the answer comes from the kernel and not from what we think we started."""
    pids = []
    for entry in os.listdir("/proc"):
        if entry.isdigit() and int(entry) != os.getpid():
            try:
                command = pathlib.Path("/proc", entry, "cmdline").read_bytes().replace(b"\0", b" ").decode()
            except OSError:
                continue
            if marker in command:
                pids.append(int(entry))
    return pids


class Processes:
    def __init__(self):
        self.running = {}
        self.service_mode = None

    def reset_data(self):
        shutil.rmtree(DB_DIR, ignore_errors=True)
        shutil.rmtree(ACTUAL, ignore_errors=True)
        LOGS.mkdir(parents=True, exist_ok=True)
        EVENTS_FILE.write_text("")

    def start_database(self):
        DB_DIR.mkdir(parents=True)
        shutil.chown(DB_DIR, "mysql", "mysql")
        subprocess.run(["mariadb-install-db", "--user=mysql", f"--datadir={DB_DIR}", "--skip-test-db",
                        "--auth-root-authentication-method=normal"],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self._start("database", ["mariadbd", "--user=mysql", f"--datadir={DB_DIR}", f"--socket={DB_SOCKET}",
                                 "--bind-address=127.0.0.1", f"--port={DB_PORT}", "--skip-name-resolve",
                                 "--innodb-buffer-pool-size=64M", "--innodb-flush-log-at-trx-commit=0"])
        self._wait_for_port("database", DB_PORT)
        with open(APP / "db" / "seed.sql", "rb") as seed:
            subprocess.run(["mariadb", f"--socket={DB_SOCKET}", "-uroot"], stdin=seed, check=True)

    def start_partner(self):
        self._start("partner", JAVA + ["-jar", str(APP / "lib" / "fake-partner.jar")])
        self._wait_for_port("partner", PARTNER_PORT)

    def start_service(self, mode, recordings, events=True, demo_break=False):
        env = {"REGTESTS_MODE": mode, "REGTESTS_RECORDINGS": str(recordings), "REGTESTS_ACTUAL": str(ACTUAL)}
        if events:
            env["REGTESTS_EVENTS"] = str(EVENTS_FILE)
        if demo_break:
            env["DEMO_BREAK"] = "true"
        debug = [f"-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:{DEBUG_PORT}"] \
            if os.environ.get("DEBUG") == "1" else []
        self._start("service", JAVA + debug + ["-jar", str(APP / "lib" / "demo-service.jar")], env)
        self.service_mode = mode
        self._wait_for_port("service", SERVICE_PORT, timeout=60)

    def stop(self, name):
        process = self.running.pop(name, None)
        if process is None:
            return
        process.send_signal(signal.SIGTERM)
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()

    def stop_all(self):
        for name in list(self.running):
            self.stop(name)

    def snapshot(self):
        """What is really running right now."""
        rows = []
        for name, (label, marker, port) in KNOWN.items():
            pids = find_pids(marker)
            row = {"name": name, "label": label, "running": bool(pids), "pid": pids[0] if pids else None,
                   "port": port, "portOpen": port_open(port)}
            if name == "service" and pids:
                row["mode"] = self.service_mode
            rows.append(row)
        return rows

    def _start(self, name, command, extra_env=None):
        log = open(LOGS / f"{name}.log", "ab")
        self.running[name] = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT,
                                              env={**os.environ, **(extra_env or {})})

    def _wait_for_port(self, name, port, timeout=30):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if port_open(port):
                return
            if self.running[name].poll() is not None:
                break
            time.sleep(0.1)
        tail = (LOGS / f"{name}.log").read_text(errors="replace")[-1500:]
        raise RuntimeError(f"{name} did not start on port {port}. End of its log:\n{tail}")
