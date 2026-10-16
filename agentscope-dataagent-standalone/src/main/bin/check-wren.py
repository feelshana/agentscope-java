#!/usr/bin/env python3
"""Check the release's Wren environment without installing or downloading anything."""
import importlib
from importlib.metadata import version
import json
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import urllib.request


def main():
    action, *args = sys.argv[1:]
    if action == "health":
        # Do not use proxy environment variables for a local readiness probe.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(args[0], timeout=2) as response:
            return 0 if json.load(response).get("status") == "UP" else 1
    if action == "port":
        with socket.socket() as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            probe.bind(("0.0.0.0", int(args[0])))
        return 0
    if action != "dependencies":
        raise ValueError("unknown check")
    if sys.version_info < (3, 11):
        raise ValueError("Wren 需要 Python >= 3.11")
    requirements = Path(__file__).resolve().parents[1] / "requirements-wren.txt"
    for line in requirements.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        package, expected = line.strip().split("==")
        package = package.split("[")[0]
        actual = version(package)
        if actual != expected:
            raise ValueError(f"{package} 版本应为 {expected}，当前为 {actual}")
    for module in ("wren", "wren_core", "mcp", "pymysql", "lancedb", "onnxruntime"):
        importlib.import_module(module)
    executable = shutil.which(args[0])
    if not executable:
        raise ValueError("未找到 Wren CLI，请设置 DATAAGENT_WREN_EXECUTABLE")
    result = subprocess.run([executable, "--version"], capture_output=True, timeout=30)
    if result.returncode:
        raise ValueError("Wren CLI 无法执行，请检查其 Python 环境")
    if result.stdout.decode("utf-8").strip() != f"wrenai {version('wrenai')}":
        raise ValueError("Wren CLI 与预检 Python 的版本不一致，请检查解释器和可执行文件路径")
    print("Wren CLI、MCP、MySQL 与 ONNX/LanceDB 依赖检查通过；模型缓存及业务查询另行验收。")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        if len(sys.argv) > 1 and sys.argv[1] != "health":
            print(f"检查失败：{error}", file=sys.stderr)
        sys.exit(1)
