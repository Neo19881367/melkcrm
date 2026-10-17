#!/usr/bin/env python3
"""Checks that every string resource the Android code references exists in strings.py, and that the
error, refusal, server and geocoding codes produced by core, client and backend have a message.
Run: python3 android/tools/check_resources.py   (exit status 1 when something is missing)."""
import ast, os, re, sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
APP = os.path.join(ROOT, "android", "app", "src", "main", "kotlin")

def strings():
    tree = ast.parse(open(os.path.join(ROOT, "android", "tools", "strings.py"), encoding="utf-8").read())
    for node in tree.body:
        if isinstance(node, ast.Assign) and getattr(node.targets[0], "id", None) == "S":
            return {row[0]: row for row in ast.literal_eval(node.value)}
    raise SystemExit("S not found in strings.py")

def sources(*dirs):
    for d in dirs:
        for base, _, files in os.walk(os.path.join(ROOT, d)):
            if "/test" in base or "/build" in base:
                continue
            for f in files:
                if f.endswith(".kt"):
                    yield os.path.join(base, f), open(os.path.join(base, f), encoding="utf-8").read()

def codes(patterns, *dirs):
    out = set()
    for _, text in sources(*dirs):
        for p in patterns:
            out.update(re.findall(p, text))
    return out

S = strings()
missing = {}
for path, text in sources("android/app/src/main/kotlin"):
    for key in re.findall(r"R\.string\.([a-zA-Z0-9_]+)", text):
        if key not in S:
            missing.setdefault(key, set()).add(os.path.relpath(path, APP))

def has(*names):
    return any(n in S for n in names)

validation = codes([r'ValidationIssue\([^,()]+, *"([a-z_]+)"', r'(?:error|warning|warn|err)\("[a-zA-Z]+", *"([a-z_]+)"',
                    r'Parsed\.Error\("([a-z_]+)"', r'to "([a-z_]+)"\)\)'], "core/src/main", "client/src/main", "android/app/src/main")
deny = codes([r'Forbidden\("([a-z_]+)"', r'return "([a-z_]+)"'], "client/src/main/kotlin/ir/melkcrm/client/data")
server = codes([r'ApiException\(\d+, *"([a-z_]+)"', r'ApiException\([A-Za-z.]+, *"([a-z_]+)"', r'ErrorResponse\("([a-z_]+)"',
                r'reject\(c, user, ch, "([a-z_]+)"', r'RemoteException\(\d+, *"([a-z_]+)"', r'SyncReport\(error = "([a-z_]+)"\)',
                r'-> "(password_[a-z_]+)"'],
               "backend/src/main", "client/src/main", "core/src/main/kotlin/ir/melkcrm/core/validation")
geo = codes([r'GeocodingException\("([a-z_]+)"'], "client/src/main")

problems = []
for k, files in sorted(missing.items()):
    problems.append(f"missing string {k} (used in {', '.join(sorted(files))})")
for c in sorted(validation):
    if not has("err_" + c, "warn_" + c):
        problems.append(f"validation code {c}: add err_{c} or warn_{c}")
for c in sorted(deny):
    if not has("deny_" + c):
        problems.append(f"refusal code {c}: add deny_{c}")
for c in sorted(server):
    if not has("srv_" + c, "deny_" + c, "err_" + c):
        problems.append(f"server code {c}: add srv_{c}")
for c in sorted(geo):
    if not has("geo_" + c):
        problems.append(f"geocoding code {c}: add geo_{c}")
for k, row in S.items():
    a = sorted(re.findall(r"%\d\$[ds]", row[1])); b = sorted(re.findall(r"%\d\$[ds]", row[2]))
    if a != b:
        problems.append(f"format mismatch in {k}")

print("\n".join(problems) if problems else f"OK: {len(S)} strings, {len(validation)} validation, {len(deny)} refusal, {len(server)} server, {len(geo)} geocoding codes covered")
sys.exit(1 if problems else 0)
