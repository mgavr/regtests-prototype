"""Replays recorded cases against a service running with REGTESTS_MODE=replay.

The service answers from the recordings and judges itself. This test sends each recorded
request, asks for the verdict, and fails with a readable list of differences.
"""
import json
import os
import pathlib
import shutil
import socket
import time
import urllib.error
import urllib.request

import pytest

SERVICE = os.environ.get("SERVICE_URL", "http://localhost:8081")
RECORDINGS = pathlib.Path(os.environ.get("REGTESTS_RECORDINGS", "recordings"))
ONLY_CASE = os.environ.get("REGTESTS_ONLY_CASE")
EVENTS = os.environ.get("REGTESTS_EVENTS")
THIS_FILE = "tests/test_replay.py"


def discover_cases():
    names = sorted(path.parent.name for path in RECORDINGS.glob("*/inbound.json"))
    return [name for name in names if not ONLY_CASE or name == ONLY_CASE]


def emit(reg, title, detail, **extra):
    """Adds a step to the live view, when there is one."""
    if EVENTS:
        line = {"ts": int(time.time() * 1000), "source": "test", "reg": reg, "file": THIS_FILE,
                "title": title, "detail": detail, **extra}
        with open(EVENTS, "a", encoding="utf-8") as events:
            events.write(json.dumps(line) + "\n")


def send(case_name, request):
    """Sends one recorded request. Returns the run id that the verdict is filed under."""
    url = SERVICE + request["path"] + ("?" + request["query"] if request.get("query") else "")
    body = request.get("body")
    outgoing = urllib.request.Request(url, method=request["method"],
                                      data=None if body is None else json.dumps(body).encode())
    if request.get("contentType"):
        outgoing.add_header("Content-Type", request["contentType"])
    outgoing.add_header("X-Regtests-Case", case_name)
    try:
        with urllib.request.urlopen(outgoing, timeout=30) as response:
            return response.headers["X-Regtests-Run"]
    except urllib.error.HTTPError as error:
        return error.headers["X-Regtests-Run"]


def fetch_report(run_id):
    with urllib.request.urlopen(f"{SERVICE}/__regtests/report/{run_id}", timeout=30) as response:
        return json.load(response)


def replay(case_name):
    inbound = json.loads((RECORDINGS / case_name / "inbound.json").read_text())
    return fetch_report(send(case_name, inbound["request"]))


def render(report):
    lines = [f"Case {report['case']} differs from its recording:"]
    for found in report["differences"]:
        lines.append(f"  {found['where']} {found['path']}: expected {found['expected']}, got {found['actual']}")
    lines += [f"  {call}" for call in report["unexpectedCalls"]]
    lines += [f"  Recorded but never made: {call}" for call in report["missingCalls"]]
    if report["serviceError"]:
        lines.append(f"  The service failed with: {report['serviceError']}")
    if report["timedOut"]:
        lines.append("  Async work did not finish in time.")
    return "\n".join(lines)


@pytest.mark.parametrize("case_name", discover_cases())
def test_case_replays_identically(case_name):
    # REG-16: the Python test sends the recorded request, naming its case in a header. That header is the only thing that is special about it.
    emit("REG-16", f"Python test sends the case: {case_name}", "POST /quotes with the X-Regtests-Case header.")
    inbound = json.loads((RECORDINGS / case_name / "inbound.json").read_text())
    report = fetch_report(send(case_name, inbound["request"]))

    # REG-24: the verdict. Any difference in the response or in an attempted call fails the test, with each difference spelled out.
    if report["passed"]:
        emit("REG-24", "PASS", f"{case_name}: {report['callsCompared']} calls and the response match the recording.", verdict="PASS")
    else:
        emit("REG-24", "FAIL", f"{case_name}: differs from the recording.", verdict="FAIL", report=report)
    assert report["passed"], render(report)


def test_database_and_partner_are_down():
    """This suite is only meaningful if nothing real is there to answer."""
    for name, port in (("database", 3306), ("partner", 8082)):
        with socket.socket() as probe:
            probe.settimeout(1)
            assert probe.connect_ex(("127.0.0.1", port)) != 0, f"the {name} is up on port {port}"


@pytest.mark.skipif(ONLY_CASE is not None, reason="runs with the full suite only")
def test_an_unexpected_call_fails_loudly():
    """Takes one database call out of a copy of a case. The service then makes a call the case lacks."""
    source = RECORDINGS / discover_cases()[0]
    tampered = RECORDINGS / "tampered-copy"
    shutil.copytree(source, tampered)
    try:
        sql_file = tampered / "sql.json"
        calls = json.loads(sql_file.read_text())
        sql_file.write_text(json.dumps(calls[1:]))

        report = replay("tampered-copy")

        assert not report["passed"]
        assert calls[0]["sql"] in report["unexpectedCalls"][0]
    finally:
        shutil.rmtree(tampered)


def test_a_request_with_no_case_is_refused():
    request = urllib.request.Request(f"{SERVICE}/quotes", data=b"{}", method="POST")
    with pytest.raises(urllib.error.HTTPError) as refused:
        urllib.request.urlopen(request, timeout=30)
    assert refused.value.code == 400
