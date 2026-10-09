"""Summarize the newest agent traces pulled from the phone.

    python scripts/summarize-traces.py traces 24      # last 24 runs
Prints goal, outcome, total seconds, model calls and model seconds per run, then totals.
"""
import json
import pathlib
import sys

folder = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "traces")
count = int(sys.argv[2]) if len(sys.argv) > 2 else 24
files = sorted(folder.glob("*.jsonl"))[-count:]
done = 0
total_ms = 0
for f in files:
    goal, outcome, ms, model_calls, model_ms, say = "", "?", 0, 0, 0, ""
    for line in f.read_text(encoding="utf-8").splitlines():
        try:
            o = json.loads(line)
        except json.JSONDecodeError:
            continue
        if o.get("type") == "start":
            goal = o.get("goal", "")
        elif o.get("type") == "step":
            r = o.get("record", {})
            if r.get("modelMs", 0) > 0:
                model_calls += 1
                model_ms += r["modelMs"]
        elif o.get("type") == "result":
            outcome, say, ms = o.get("outcome"), o.get("say", ""), o.get("executionMs", 0)
    done += outcome == "DONE"
    total_ms += ms
    print(f"{outcome:7} {ms/1000:6.1f}s  model {model_calls:2} calls {model_ms/1000:5.1f}s | {goal} | {say[:50]}")
print(f"== {done}/{len(files)} DONE, {total_ms/1000/max(len(files),1):.1f}s average")
