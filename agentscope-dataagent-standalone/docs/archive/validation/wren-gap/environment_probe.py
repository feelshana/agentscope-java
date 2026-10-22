"""Report non-secret runtime availability and selected probe output."""
import importlib.util
import json
from pathlib import Path
import socket
import sys

print({name: importlib.util.find_spec(name) is not None
       for name in ("duckdb", "pandas", "pymysql", "wren_core")})
for port in (8080, 8082, 3306):
    with socket.socket() as sock:
        sock.settimeout(1)
        print("127.0.0.1", port, "listening" if sock.connect_ex(("127.0.0.1", port)) == 0 else "unavailable")
root = Path(__file__).resolve().parent
result = json.loads((root / "official-results.json").read_text(encoding="utf-8"))
for probe in result["probes"]:
    if probe["name"] in ("bad_dimension_selected_sqlonly", "bad_dimension_expanded_plan", "logical_revenue_plan"):
        print(probe["name"], probe["stdout"], probe["stderr"])
