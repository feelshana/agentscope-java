#!/bin/bash
# =====================================================
# data-analysis 停止脚本（Linux/Mac）
# 使用方式：./bin/stop.sh
# =====================================================

APP_HOME=$(cd "$(dirname "$0")/.." && pwd)
APP_NAME="data-analysis"
PID_FILE="$APP_HOME/data-analysis.pid"

if [ ! -f "$PID_FILE" ]; then
    echo "$APP_NAME is not running (pid file not found)"
    exit 0
fi

PID=$(cat "$PID_FILE")

if kill -0 "$PID" 2>/dev/null; then
    echo "Stopping $APP_NAME (PID=$PID) ..."
    kill "$PID"
    # 等待最多 30 秒
    for i in $(seq 1 30); do
        if ! kill -0 "$PID" 2>/dev/null; then
            echo "$APP_NAME stopped."
            rm -f "$PID_FILE"
            exit 0
        fi
        sleep 1
    done
    echo "Force killing $APP_NAME ..."
    kill -9 "$PID"
    rm -f "$PID_FILE"
    echo "$APP_NAME force stopped."
else
    echo "$APP_NAME is not running (stale pid file), cleaning up."
    rm -f "$PID_FILE"
fi
