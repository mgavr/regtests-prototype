"""All the comparing lives here, in one place, for every language's capture library.

A library only records and answers. This module reads a recorded case folder and a run folder,
which have the same format, and decides whether they match. Nothing here knows about Java.
"""
import json
import pathlib
import re
from decimal import Decimal
from urllib.parse import urlsplit

TIMESTAMP = re.compile(r"\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?")
UUID = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
ABSENT = "(absent)"


def read_json(path):
    """Decimals stay exact, so a number never drifts by a rounding error."""
    return json.loads(pathlib.Path(path).read_text(encoding="utf-8"), parse_float=Decimal)


def load_config(recordings):
    """regtests.json is optional. With no file, the default normaliser still applies."""
    file = pathlib.Path(recordings) / "regtests.json"
    return read_json(file) if file.is_file() else {}


def read_case(folder):
    folder = pathlib.Path(folder)
    calls = read_json(folder / "outbound-http.json") + read_json(folder / "sql.json")
    return read_json(folder / "inbound.json"), sorted(calls, key=lambda call: call["seq"])


def key_of(call):
    """Which recorded call this one should line up with: the statement, or the method and path."""
    return call["sql"] if "sql" in call else f"{call['method']} {urlsplit(call['url']).path}"


# REG-23: the comparison. What the new build did is held against the recording, field by field. ISO timestamps and UUIDs are normalised first, then the service's config file says what else may differ.
def compare_case(recorded, run, config):
    expected_inbound, expected_calls = read_case(recorded)
    actual_inbound, actual_calls = read_case(run)
    waiting = {}
    for call in expected_calls:
        waiting.setdefault(key_of(call), []).append(call)
    differences, unexpected, compared = [], [], 0
    for call in actual_calls:
        if waiting.get(key_of(call)):
            differences += compare_call(waiting[key_of(call)].pop(0), call, config)
            compared += 1
        else:
            unexpected.append(f"REPLAY: unexpected call: {key_of(call)}. "
                              f"Case {expected_inbound['case']} has no such call left.")
    in_response = compare_response(expected_inbound["response"], actual_inbound["response"], config)
    missing = [key for key, left in waiting.items() for _ in left]
    return {"case": expected_inbound["case"], "passed": not (differences or in_response or unexpected or missing),
            "status": actual_inbound["response"]["status"], "callsCompared": compared,
            "differences": differences + in_response, "inResponse": len(in_response),
            "unexpectedCalls": unexpected, "missingCalls": missing, "serviceError": actual_inbound.get("error")}


def compare_call(expected, actual, config):
    if "sql" in expected:
        return compare_params(expected["sql"], expected["params"], actual["params"], config)
    url = urlsplit(actual["url"])
    where = f"Outbound {actual['method']} {url.path}"
    differences = []
    if not same(urlsplit(expected["url"]).query, url.query, config):
        differences.append(difference(where, "query", urlsplit(expected["url"]).query, url.query, show=str))
    return differences + compare_json(where, expected.get("requestBody"), actual.get("requestBody"), config)


def compare_response(expected, actual, config):
    differences = []
    if expected["status"] != actual["status"]:
        differences.append(difference("Response", "status", expected["status"], actual["status"], show=str))
    return differences + compare_json("Response", expected.get("body"), actual.get("body"), config)


def compare_params(sql, expected, actual, config):
    """Parameters are compared by position, skipping the ones the config file names for this statement."""
    rules = config.get("ignore", {}).get("sqlParams", {})
    ignored = [position for text, positions in rules.items() if text.lower() in sql.lower() for position in positions]
    where = f"Database call {short_name(sql)}"
    differences = []
    for index in range(max(len(expected), len(actual))):
        before = expected[index] if index < len(expected) else None
        after = actual[index] if index < len(actual) else None
        if index + 1 not in ignored and not same(before, after, config):
            differences.append(difference(where, f"parameter {index + 1}", before, after))
    return differences


def compare_json(where, expected, actual, config):
    differences = []
    walk(where, "$", expected, actual, config, differences)
    return differences


def walk(where, path, expected, actual, config, out):
    ignored = config.get("ignore", {}).get("jsonPaths", [])
    if path in ignored or re.sub(r"\[\d+]", "[*]", path) in ignored:
        return
    if isinstance(expected, dict) and isinstance(actual, dict):
        for name in sorted(expected.keys() | actual.keys()):
            walk(where, f"{path}.{name}", expected.get(name), actual.get(name), config, out)
    elif isinstance(expected, list) and isinstance(actual, list):
        for index in range(max(len(expected), len(actual))):
            walk(where, f"{path}[{index}]", expected[index] if index < len(expected) else None,
                 actual[index] if index < len(actual) else None, config, out)
    elif not same(expected, actual, config):
        out.append(difference(where, path, expected, actual))


def same(expected, actual, config):
    if is_number(expected) and is_number(actual):
        return expected == actual  # 767.25 and 767.250 are the same number.
    if isinstance(expected, str) and isinstance(actual, str):
        return normalize(expected, config) == normalize(actual, config)
    return type(expected) is type(actual) and expected == actual


def is_number(value):
    return isinstance(value, (int, Decimal)) and not isinstance(value, bool)


def normalize(text, config):
    rules = config.get("normalize", {})
    if rules.get("timestamps", True):
        text = TIMESTAMP.sub("<timestamp>", text)
    if rules.get("uuids", True):
        text = UUID.sub("<uuid>", text)
    return text


def short_name(sql):
    """"{call some_procedure(?, ?)}" becomes "some_procedure". Other statements stop at the first bracket."""
    text = re.sub(r"(?i)^\{?\s*call\s+", "", sql)
    return text.split("(")[0].strip() if "(" in text[1:] else text.strip()


def difference(where, path, expected, actual, show=None):
    """One difference, written so a person can read it without opening a file."""
    render = show or shown
    return {"where": where, "path": path, "expected": render(expected), "actual": render(actual)}


def shown(value):
    if value is None:
        return ABSENT
    if isinstance(value, Decimal):
        return str(value)
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False, default=str)
