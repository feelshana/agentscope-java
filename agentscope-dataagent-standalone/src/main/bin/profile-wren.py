#!/usr/bin/env python3
"""Run one official Wren CLI command with opt-in, content-free phase profiling.

Use the same Python, account, HOME, project and compatibility PYTHONPATH as the app.
The report contains only allowlisted stages, counts and timings, never CLI arguments.
Inclusive times overlap: do not add them. Interpreter/sitecustomize startup is external.
"""

import argparse
import json
import os
from pathlib import Path
import sys
from time import perf_counter


def stage_for(filename, function):
    path = filename.replace("\\", "/").lower()
    if function == "_find_and_load" and "importlib" in path:
        return "python_imports"
    if path.endswith("wren/memory/embeddings.py"):
        return {
            "_hf_file": "model_file_resolution",
            "_runtime": "embedding_runtime_including_load",
            "_encode": "embedding_including_runtime",
        }.get(function)
    if path.endswith("onnxruntime_inference_collection.py"):
        return {
            "_create_inference_session": "onnx_session_load",
            "run": "onnx_inference",
        }.get(function)
    if "tokenizers" in function and "encode_batch" in function:
        return "tokenization"
    if "lancedb" in path and function in {"connect", "create_table", "add", "delete", "to_list"}:
        return "lancedb_" + function
    if path.endswith("wren/memory/store.py"):
        return {
            "index_schema": "schema_index_total",
            "store_query": "store_query_total",
            "recall_queries": "recall_query_total",
            "sync_markdown_queries": "markdown_sync_total",
            "_prepare_query_records": "example_embedding_batch",
            "_write_query_records": "example_write_batch",
        }.get(function)
    if path.endswith("wren/memory/markdown.py") and function == "write_query_markdown":
        return "markdown_write"
    return None


class PhaseProfiler:
    """Time allowlisted call boundaries using monotonic wall time, not CPU samples."""

    def __init__(self):
        self.active = {}
        self.phases = {}
        self.depth = {}
        self.codes = {}
        self.missing = object()
        self.native_calls = 0

    def enter(self, key, stage):
        depth = self.depth.get(stage, 0)
        self.depth[stage] = depth + 1
        self.active[key] = (stage, perf_counter(), depth == 0)

    def leave(self, key):
        active = self.active.pop(key, None)
        if active:
            stage, started, outermost = active
            self.depth[stage] -= 1
            self.record(stage, started, outermost)

    def record(self, stage, started, outermost):
        ms = (perf_counter() - started) * 1000
        row = self.phases.setdefault(stage, {"calls": 0, "inclusiveMs": 0.0, "outermostMs": 0.0, "firstMs": ms, "maxMs": 0.0})
        row["calls"] += 1
        row["inclusiveMs"] += ms
        if outermost:
            row["outermostMs"] += ms
        row["maxMs"] = max(row["maxMs"], ms)

    def callback(self, frame, event, arg):
        if event == "call":
            code = frame.f_code
            stage = self.codes.get(code, self.missing)
            if stage is self.missing:
                stage = stage_for(code.co_filename, code.co_name)
                self.codes[code] = stage
            if stage:
                self.enter(id(frame), stage)
        elif event == "return":
            self.leave(id(frame))
        elif event == "c_call":
            name = getattr(arg, "__qualname__", "")
            if "Tokenizer.encode_batch" in name:
                self.native_calls += 1
                self.enter((id(frame), name), "tokenization")
        elif event in {"c_return", "c_exception"}:
            if self.native_calls:
                name = getattr(arg, "__qualname__", "")
                if "Tokenizer.encode_batch" in name:
                    self.native_calls -= 1
                    self.leave((id(frame), name))

    def summary(self):
        return {stage: {key: round(value, 3) if isinstance(value, float) else value
                        for key, value in row.items()} for stage, row in self.phases.items()}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("Provide a Wren command after --")
    # Never overwrite an existing report or follow an output symlink.
    fd = os.open(args.report, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    profiler = PhaseProfiler()
    started = perf_counter()
    outcome = "ok"
    original_argv = sys.argv
    previous_profile = sys.getprofile()
    try:
        sys.setprofile(profiler.callback)
        from wren.cli import app

        sys.argv = ["wren", *command]
        app()
    except SystemExit as error:
        if error.code not in (None, 0):
            outcome = "failed"
        raise
    except BaseException:
        outcome = "failed"
        raise
    finally:
        sys.setprofile(previous_profile)
        sys.argv = original_argv
        report = {
            "formatVersion": 1,
            "outcome": outcome,
            "profiledWallMs": round((perf_counter() - started) * 1000, 3),
            "inclusiveTimesOverlap": True,
            "excludesInterpreterAndSitecustomizeStartup": True,
            "phases": profiler.summary(),
        }
        with os.fdopen(fd, "w", encoding="utf-8") as target:
            json.dump(report, target, ensure_ascii=False, indent=2)
            target.write("\n")


if __name__ == "__main__":
    main()
