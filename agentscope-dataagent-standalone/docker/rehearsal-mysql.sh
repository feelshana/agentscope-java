#!/usr/bin/env bash
# Dedicated rehearsal database only. No host port, service removal, or global cleanup.
set -Eeuo pipefail
fail() { echo "演练库操作失败：$*" >&2; exit 1; }
action="${1:-}"
case "$action" in prepare|start|verify|status|logs) ;; *) fail '参数：prepare|start|verify|status|logs';; esac
base="${DATAAGENT_REHEARSAL_DB_DIR:-/data/dataagent/rehearsal-db}"
[[ "$base" =~ ^/[a-zA-Z0-9_./-]+$ && "$base" != / && "$base" != /data && "$base" != /data/dataagent ]] || fail '演练目录无效'
parent="$(cd -- "$(dirname -- "$base")" && pwd -P)"
[[ -w "$parent" && ! -L "$base" ]] || fail '父目录不可写或目标为符号链接'
base="$parent/$(basename -- "$base")"
project="${DATAAGENT_REHEARSAL_DB_PROJECT:-dataagent66-rehearsal-db}"
[[ "$project" =~ ^[a-z0-9][a-z0-9_-]{0,62}$ ]] || fail 'Compose项目名无效'
docker_cmd=(sudo docker)
[[ "${DATAAGENT_DOCKER_SUDO:-true}" == false ]] && docker_cmd=(docker)
d() { "${docker_cmd[@]}" "$@"; }
d compose version >/dev/null
umask 077

if [[ "$action" == prepare ]]; then
    if [[ -e "$base" ]]; then
        [[ -f "$base/.dataagent-rehearsal" && "$(cat "$base/.dataagent-rehearsal")" == "$base|$project" ]] || fail '目录已存在且未标记，拒绝覆盖'
        for file in compose.yml compose.env mysql.env app-db.env init/00-databases.sql init/01-platform.sql init/02-users.sh; do
            [[ -s "$base/$file" ]] || fail '准备未完整完成，保留现场，请核对缺失文件'
        done
        echo "演练配置已存在，保留原密码和数据：$base"; exit 0
    fi
    source_root="${DATAAGENT_SOURCE_MODULE:-$HOME/dataagent-source/agentscope-java/agentscope-dataagent-standalone}"
    ddl="$source_root/docs/migrations/0000-initial-platform-mysql.sql"
    [[ -f "$ddl" && -f "$source_root/docs/migrations/0000-create-databases-mysql.sql" ]] || fail '未找到完整初始化SQL'
    [[ "$(grep -c '^CREATE TABLE ' "$ddl")" == 37 ]] || fail 'DDL表数量已变化，请先重新验收'
    image="${DATAAGENT_REHEARSAL_MYSQL_IMAGE:-m.daocloud.io/docker.io/library/mysql:8.0}"
    [[ "$(d image inspect --format '{{.Os}}/{{.Architecture}}' "$image")" == linux/amd64 ]] || fail '镜像不是linux/amd64'
    image_id="$(d image inspect --format '{{.Id}}' "$image")"
    version="$(d run --rm --pull never --network none --cpus 1 --memory 512m --entrypoint mysql "$image_id" --version)"
    [[ "$version" == *'Ver 8.0.'* ]] || fail '镜像不是MySQL8.0，或宿主无法运行'
    mkdir -- "$base"
    printf '%s\n' "$base|$project" > "$base/.dataagent-rehearsal"
    mkdir -- "$base/mysql" "$base/init"
    chmod 0755 "$base/init"
    random_hex() { od -An -N32 -tx1 /dev/urandom | tr -d ' \n'; }
    root_password="$(random_hex)"; platform_password="$(random_hex)"; dataset_password="$(random_hex)"
    printf 'MYSQL_ROOT_PASSWORD=%s\nPLATFORM_PASSWORD=%s\nDATASET_PASSWORD=%s\nMYSQL_INITDB_SKIP_TZINFO=1\nTZ=Asia/Shanghai\n' \
        "$root_password" "$platform_password" "$dataset_password" > "$base/mysql.env"
    printf 'DB_DIR=%s\nMYSQL_IMAGE=%s\n' "$base" "$image_id" > "$base/compose.env"
    printf 'DATAAGENT_DB_URL=jdbc:mysql://mysql:3306/dataagent_platform?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true\nDATAAGENT_DB_USER=dataagent_platform\nDATAAGENT_DB_PASSWORD=%s\nDATAAGENT_DATASET_DB_URL=jdbc:mysql://mysql:3306/data_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true\nDATAAGENT_DATASET_DB_USER=dataagent_dataset\nDATAAGENT_DATASET_DB_PASSWORD=%s\n' \
        "$platform_password" "$dataset_password" > "$base/app-db.env"
    cp -- "$source_root/docs/migrations/0000-create-databases-mysql.sql" "$base/init/00-databases.sql"
    { printf 'USE `dataagent_platform`;\n'; cat "$ddl"; } > "$base/init/01-platform.sql"
    cat > "$base/init/02-users.sh" <<'INIT'
#!/bin/bash
set -euo pipefail
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot <<SQL
CREATE USER 'dataagent_platform'@'%' IDENTIFIED BY '${PLATFORM_PASSWORD}';
GRANT SELECT, INSERT, UPDATE, DELETE ON dataagent_platform.* TO 'dataagent_platform'@'%';
CREATE USER 'dataagent_dataset'@'%' IDENTIFIED BY '${DATASET_PASSWORD}';
GRANT ALL PRIVILEGES ON data_agent.* TO 'dataagent_dataset'@'%';
SQL
INIT
    chmod 0644 "$base"/init/*
    # Execute in a child shell so strict options never leak into the official entrypoint.
    chmod 0755 "$base/init/02-users.sh"
    cat > "$base/compose.yml" <<'COMPOSE'
services:
  mysql:
    image: ${MYSQL_IMAGE:?}
    pull_policy: never
    platform: linux/amd64
    restart: unless-stopped
    stop_grace_period: 60s
    cpus: 2
    mem_limit: 2g
    pids_limit: 512
    env_file: [mysql.env]
    labels:
      dataagent.rehearsal.base: ${DB_DIR:?}
    command: [--character-set-server=utf8mb4, --collation-server=utf8mb4_unicode_ci, --default-time-zone=+08:00, --innodb-buffer-pool-size=512M]
    volumes:
      - type: bind
        source: ${DB_DIR}/mysql
        target: /var/lib/mysql
        bind: {create_host_path: false}
      - type: bind
        source: ${DB_DIR}/init
        target: /docker-entrypoint-initdb.d
        read_only: true
        bind: {create_host_path: false}
    healthcheck:
      test: [CMD-SHELL, 'test "$$(MYSQL_PWD="$$MYSQL_ROOT_PASSWORD" mysql --protocol=TCP -h127.0.0.1 -uroot --connect-timeout=3 -Nse "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=''dataagent_platform''")" = 37']
      interval: 5s
      timeout: 5s
      retries: 40
      start_period: 20s
    logging:
      driver: json-file
      options: {max-size: 10m, max-file: '3'}
networks:
  default:
    labels:
      dataagent.rehearsal.base: ${DB_DIR:?}
COMPOSE
    printf '%s\n' "$version" > "$base/mysql-version.txt"
    echo "准备完成，密码仅保存在私有env文件，未启动数据库：$base"
    exit 0
fi

[[ -f "$base/.dataagent-rehearsal" && "$(cat "$base/.dataagent-rehearsal")" == "$base|$project" ]] || fail '项目标记不匹配'
exec 9> "$base/.control.lock"
flock -n 9 || fail '已有本项目操作在运行'
for container in $(d ps -aq --filter "label=com.docker.compose.project=$project"); do
    [[ "$(d inspect --format '{{index .Config.Labels "dataagent.rehearsal.base"}}' "$container")" == "$base" ]] || fail '同名Compose项目含非本目录容器，停止'
done
for network in $(d network ls -q --filter "name=^${project}_default$"); do
    [[ "$(d network inspect --format '{{index .Labels "dataagent.rehearsal.base"}}' "$network")" == "$base" ]] || fail '网络已被其他项目使用'
done
c() { d compose -p "$project" --project-directory "$base" --env-file "$base/compose.env" -f "$base/compose.yml" "$@"; }
case "$action" in
    start) c up -d --no-build --pull never --wait --wait-timeout 240 mysql;;
    status) c ps; exit 0;;
    logs) c logs --tail 80 mysql; exit 0;;
esac
c exec -T mysql sh -ec '
  export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
  mysql --protocol=socket -uroot -e "SELECT VERSION() AS mysql_version; SELECT table_schema, COUNT(*) AS table_count FROM information_schema.tables WHERE table_schema IN ('\''dataagent_platform'\'', '\''data_agent'\'') GROUP BY table_schema; SHOW DATABASES LIKE '\''data_agent'\'';"
  test "$(mysql --protocol=socket -uroot -Nse "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='\''dataagent_platform'\''")" = 37
  MYSQL_PWD="$PLATFORM_PASSWORD" mysql --protocol=socket -udataagent_platform dataagent_platform -e "SELECT CURRENT_USER() AS platform_account, COUNT(*) AS users FROM dataagent_user;"
  MYSQL_PWD="$DATASET_PASSWORD" mysql --protocol=socket -udataagent_dataset data_agent -e "SELECT CURRENT_USER() AS dataset_account;"
'
echo "演练库核对通过；MySQL未发布宿主端口，应用需加入${project}_default网络；业务服务尚未启动"
