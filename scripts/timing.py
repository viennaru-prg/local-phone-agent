"""Time from a recognized command to its finished result, per run (pulled traces).

usage: python scripts/timing.py <traces dir> [newest N=20] [target seconds=10]

Each trace records executionMs: from the moment the service got the recognized text until the result.
Prints one line per run and the runs over the target.
"""
import glob
import json
import os
import sys

folder = sys.argv[1]
count = int(sys.argv[2]) if len(sys.argv) > 2 else 20
target = float(sys.argv[3]) if len(sys.argv) > 3 else 10.0

runs = []
for path in sorted(glob.glob(os.path.join(folder, "*.jsonl")))[-count:]:
    goal, origin, outcome, ms, models = "", "", "", None, 0
    with open(path, encoding="utf-8") as f:
        for line in f:
            d = json.loads(line)
            if d.get("type") == "start":
                goal = d.get("goal", "")
                origin = (d.get("metadata") or {}).get("origin", "")
            elif d.get("type") == "step" and d["record"].get("source", "").startswith("model"):
                models += 1
            elif d.get("type") == "result":
                outcome, ms = d.get("outcome", ""), d.get("executionMs")
    if ms is not None:
        runs.append((goal, origin, outcome, ms / 1000.0, models))

for goal, origin, outcome, s, models in runs:
    mark = "  " if s <= target else "!!"
    print(f"{mark} {s:5.1f}s  model {models}  {outcome:6}  {origin:14}  {goal}")
over = [r for r in runs if r[3] > target]
print(f"\n{len(runs) - len(over)}/{len(runs)} within {target:g}s")
