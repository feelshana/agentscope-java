#!/bin/bash
# =====================================================
# data-analysis 启动脚本（Linux/Mac）
# 使用方式：
#   启动：./bin/start.sh
#   指定环境：SPRING_PROFILES_ACTIVE=prod ./bin/start.sh
# =====================================================

# 脚本所在目录的上一级即为应用根目录
APP_HOME=$(cd "$(dirname "$0")/.." && pwd)
APP_NAME="data-analysis"
JAR_FILE="$APP_HOME/lib/data-analysis.jar"
DEPS_DIR="$APP_HOME/lib/deps/*"
CONF_DIR="$APP_HOME/conf"
LOG_DIR="$APP_HOME/logs"
PID_FILE="$APP_HOME/data-analysis.pid"

# 默认激活 prod 环境
PROFILE=${SPRING_PROFILES_ACTIVE:-prod}

# JVM 参数（可按需调整）
JVM_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC"

mkdir -p "$LOG_DIR"

echo "Starting $APP_NAME with profile: $PROFILE ..."

nohup java $JVM_OPTS \
  -cp "$DEPS_DIR:$JAR_FILE" \
  io.agentscope.examples.dataanalysis.DataAnalysisApplication \
  --spring.profiles.active="$PROFILE" \
  --spring.config.location="classpath:/,file:$CONF_DIR/" \
  >> "$LOG_DIR/app.log" 2>&1 &

echo $! > "$PID_FILE"
echo "$APP_NAME started, PID=$(cat $PID_FILE), log: $LOG_DIR/app.log"
