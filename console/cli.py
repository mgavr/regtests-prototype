"""What the container runs: `console` (the default), `test` or `record-examples`."""
import os
import pathlib
import shutil
import signal
import subprocess
import sys
import time

from processes import APP, DATA, EVENTS_FILE, Processes
from server import serve
from stages import EventTail, post_quote, sample_requests, seed_config


def stop_on_signal(signum, frame):
    raise SystemExit(0)


def run_tests():
    """Replays the committed recordings. Only the service runs: no database, no partner."""
    recordings = DATA / "replay-copy"  # A copy, because one test writes a tampered case next to the real ones.
    shutil.copytree(APP / "recordings", recordings, dirs_exist_ok=True)
    processes = Processes()
    processes.reset_data()
    print("Running processes: the service only. No database and no partner.", flush=True)
    try:
        processes.start_service("replay", recordings, events=False,
                                demo_break=os.environ.get("DEMO_BREAK") == "true")
        env = {**os.environ, "REGTESTS_RECORDINGS": str(recordings)}
        return subprocess.call([sys.executable, "-m", "pytest", "tests", "-v", "--tb=short", "-p", "no:cacheprovider"],
                               cwd=APP, env=env)
    finally:
        processes.stop_all()


def record_examples():
    """Regenerates the example cases from a real run, into /data/recordings. Mount a folder there to keep them."""
    recordings = DATA / "recordings"
    seed_config(recordings)
    processes = Processes()
    processes.reset_data()
    tail = EventTail()
    try:
        processes.start_database()
        processes.start_partner()
        processes.start_service("record", recordings)
        for example in sample_requests()["examples"]:
            status = post_quote(example["case"], example["body"])
            deadline = time.time() + 20
            while not any(event["title"] == f"Case saved: {example['case']}" for event in tail.read()):
                if time.time() > deadline:
                    raise RuntimeError(f"case {example['case']} was not saved")
                time.sleep(0.05)
            print(f"Recorded {example['case']}: HTTP {status}", flush=True)
        return 0
    finally:
        processes.stop_all()


def main():
    signal.signal(signal.SIGTERM, stop_on_signal)
    command = sys.argv[1] if len(sys.argv) > 1 else "console"
    if command == "console":
        serve()
    elif command == "test":
        sys.exit(run_tests())
    elif command == "record-examples":
        sys.exit(record_examples())
    else:
        print(__doc__)
        sys.exit(2)


if __name__ == "__main__":
    main()
