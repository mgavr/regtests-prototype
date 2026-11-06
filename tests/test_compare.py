"""The comparison rules, tested on their own. No service, database or network needed."""
import json
from decimal import Decimal

from compare import compare_case, compare_json, compare_params, read_json

CONFIG = {"ignore": {"jsonPaths": ["$.number", "$.items[*].at"], "sqlParams": {"save_thing": [2]}}}


def parsed(text):
    return json.loads(text, parse_float=Decimal)


def write_case(folder, http=(), sql=(), status=200, body=None, error=None):
    folder.mkdir()
    inbound = {"case": "a-case", "request": {"method": "POST", "path": "/x"},
               "response": {"status": status, "body": body or {"total": 1}}}
    if error:
        inbound["error"] = error
    (folder / "inbound.json").write_text(json.dumps(inbound))
    (folder / "outbound-http.json").write_text(json.dumps(list(http)))
    (folder / "sql.json").write_text(json.dumps(list(sql)))
    return folder


def get(seq, url="http://partner/rates?a=1", body=None):
    return {"seq": seq, "method": "GET", "url": url, "requestBody": body}


def call(seq, sql="{call save_thing(?, ?)}", params=(1, "x")):
    return {"seq": seq, "sql": sql, "params": list(params)}


def test_timestamps_and_uuids_are_normalised_with_no_config():
    before = parsed('{"id":"3f2b8c1e-1111-4222-8333-444455556666","at":"2026-01-02T03:04:05.678Z"}')
    after = parsed('{"id":"9a7d6e5f-aaaa-4bbb-8ccc-ddddeeeeffff","at":"2027-11-12T13:14:15Z"}')

    assert compare_json("Response", before, after, {}) == []


def test_the_normaliser_can_be_turned_off():
    config = {"normalize": {"timestamps": False}}

    found = compare_json("Response", {"at": "2026-01-02T03:04:05Z"}, {"at": "2027-01-02T03:04:05Z"}, config)

    assert [item["path"] for item in found] == ["$.at"]


def test_numbers_compare_by_value():
    assert compare_json("Response", parsed('{"a":10.50}'), parsed('{"a":10.5}'), {}) == []


def test_a_json_path_in_the_config_file_is_ignored():
    assert compare_json("Response", {"number": "Q-1"}, {"number": "Q-2"}, CONFIG) == []
    assert compare_json("Response", {"items": [{"at": "a"}]}, {"items": [{"at": "b"}]}, CONFIG) == []


def test_a_real_difference_is_readable():
    found = compare_json("Response", parsed('{"total":10.50,"gone":1}'), parsed('{"total":12.00}'), CONFIG)

    assert found == [{"where": "Response", "path": "$.gone", "expected": "1", "actual": "(absent)"},
                     {"where": "Response", "path": "$.total", "expected": "10.50", "actual": "12.00"}]


def test_parameters_compare_by_position_and_skip_the_ignored_ones():
    found = compare_params("{call save_thing(?, ?, ?)}", [1, "run-a", "same"], [2, "run-b", "same"], CONFIG)

    assert found == [{"where": "Database call save_thing", "path": "parameter 1", "expected": "1", "actual": "2"}]


def test_plain_sql_is_named_up_to_its_first_bracket():
    found = compare_params("INSERT INTO t (a) VALUES (?)", ["Premium 1"], ["Premium 2"], {})

    assert found == [{"where": "Database call INSERT INTO t", "path": "parameter 1",
                      "expected": '"Premium 1"', "actual": '"Premium 2"'}]


def test_identical_runs_pass(tmp_path):
    recorded = write_case(tmp_path / "recorded", http=[get(1)], sql=[call(2)])
    run = write_case(tmp_path / "run", http=[get(2)], sql=[call(1)])

    report = compare_case(recorded, run, CONFIG)

    assert report["passed"] and report["callsCompared"] == 2


def test_a_changed_query_is_a_difference(tmp_path):
    recorded = write_case(tmp_path / "recorded", http=[get(1)])
    run = write_case(tmp_path / "run", http=[get(1, url="http://partner/rates?a=2")])

    report = compare_case(recorded, run, {})

    assert not report["passed"]
    assert report["differences"] == [{"where": "Outbound GET /rates", "path": "query", "expected": "a=1", "actual": "a=2"}]


def test_a_call_the_recording_lacks_is_unexpected(tmp_path):
    recorded = write_case(tmp_path / "recorded")
    run = write_case(tmp_path / "run", sql=[call(1, sql="DELETE FROM thing", params=())])

    report = compare_case(recorded, run, {})

    assert not report["passed"]
    assert "DELETE FROM thing" in report["unexpectedCalls"][0]


def test_a_recorded_call_that_is_never_made_fails_the_case(tmp_path):
    recorded = write_case(tmp_path / "recorded", sql=[call(1)])
    run = write_case(tmp_path / "run")

    report = compare_case(recorded, run, CONFIG)

    assert not report["passed"]
    assert report["missingCalls"] == ["{call save_thing(?, ?)}"]


def test_calls_line_up_by_key_whatever_order_they_ran_in(tmp_path):
    recorded = write_case(tmp_path / "recorded", sql=[call(1, sql="A", params=[1]), call(2, sql="B", params=[2])])
    run = write_case(tmp_path / "run", sql=[call(1, sql="B", params=[2]), call(2, sql="A", params=[1])])

    assert compare_case(recorded, run, {})["passed"]


def test_a_changed_response_and_what_the_service_threw_are_reported(tmp_path):
    recorded = write_case(tmp_path / "recorded")
    run = write_case(tmp_path / "run", status=500, body={"total": 1}, error="it broke")

    report = compare_case(recorded, run, {})

    assert report["differences"] == [{"where": "Response", "path": "status", "expected": "200", "actual": "500"}]
    assert report["serviceError"] == "it broke"


def test_a_recording_file_keeps_decimals_exact(tmp_path):
    file = tmp_path / "x.json"
    file.write_text('{"a":10.50}')

    assert str(read_json(file)["a"]) == "10.50"
