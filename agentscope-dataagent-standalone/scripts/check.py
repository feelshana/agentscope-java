#!/usr/bin/env python3
"""Run daily backend/frontend checks or the complete pre-merge verification."""

import argparse
import fnmatch
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def selected_tests(value):
    if not re.fullmatch(r"[A-Za-z0-9_.*#,+$-]+", value):
        raise argparse.ArgumentTypeError("Use class names or Class#method, separated by commas.")
    sources = {p.stem: p for p in (ROOT / "src/test/java").rglob("*Test.java")}
    for selector in value.split(","):
        parts = selector.split("#")
        if len(parts) > 2 or not parts[0]:
            raise argparse.ArgumentTypeError("Invalid test selector: " + selector)
        matches = [p for name, p in sources.items() if fnmatch.fnmatchcase(name, parts[0])]
        if not matches:
            raise argparse.ArgumentTypeError("No test class matches: " + selector)
        if len(parts) == 2:
            for method in parts[1].split("+"):
                if not method or not any(
                    any(fnmatch.fnmatchcase(name, method) for name in re.findall(
                        r"\bvoid\s+(\w+)\s*\(", p.read_text(encoding="utf-8")))
                    for p in matches
                ):
                    raise argparse.ArgumentTypeError("No test method matches: " + selector)
    return value


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    backend = modes.add_parser("backend", help="Backend only; no npm or frontend build")
    selection = backend.add_mutually_exclusive_group()
    selection.add_argument("--tests", type=selected_tests, help="Class or Class#method selectors")
    selection.add_argument("--unit", action="store_true", help="Exclude integration and external checks")
    frontend = modes.add_parser("frontend", help="Frontend tests, local root prefix by default")
    frontend.add_argument("--files", nargs="+", help="Exact *.test.mjs filenames")
    frontend.add_argument("--all-prefixes", action="store_true")
    modes.add_parser("full", help="Clean backend/frontend tests and package; no deployment")
    args = parser.parse_args(argv)

    if args.mode == "frontend":
        available = {p.name for p in (ROOT / "frontend/tests").glob("*.test.mjs")}
        if args.files and any(name not in available for name in args.files):
            parser.error("Unknown frontend test file; use exact names from frontend/tests.")
        command = ["npm", "test", "--"]
        for name in args.files or []:
            command.extend(["--file", name])
        if not args.all_prefixes:
            command.extend(["--base", "/"])
        directory = ROOT / "frontend"
    else:
        command = ["mvn", "-B", "-ntp"]
        if args.mode == "backend":
            command += ["-Dskip.npm=true", "-Dskip.installnodenpm=true"]
            if args.tests:
                command.append("-Dtest=" + args.tests)
            elif args.unit:
                command.append("-DexcludedGroups=integration | external")
            command.append("test")
        else:
            command += ["clean", "verify"]
        directory = ROOT

    executable = shutil.which(command[0])
    if not executable:
        parser.error("Executable not found on PATH: " + command[0])
    command[0] = executable
    print("Check mode:", args.mode, "|", " ".join(command), flush=True)
    print("Working directory:", directory, flush=True)
    try:
        return subprocess.run(command, cwd=directory).returncode
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
