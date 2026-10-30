#!/usr/bin/env bash
# Fixed account operations for the dedicated Docker MySQL deployment.
set -Eeuo pipefail
umask 077
fail() { echo "用户管理失败：$*" >&2; exit 1; }
usage() {
    cat <<'HELP'
用法：bash docker/user-admin.sh <操作> [账号或关键词]
  search 关键词   按中文名、英文账号、公司、部门查找（最多100条）
  show BI账号     查看准确账号及角色
  admins          列出全局管理员
  grant BI账号    将已有账号设为 user,admin
  revoke BI账号   将已有账号改为 user，拒绝撤销最后一个管理员
  help            显示帮助
默认连接 /data/dataagent/rehearsal-db 的独立 MySQL；不创建用户、不修改密码。
角色修改后需重新从BI入口登录；已有JWT不会立即失效，最长等待配置的令牌有效期。
HELP
}
action="${1:-help}"
case "$action" in
    help|-h|--help) usage; exit 0;;
    search|show|grant|revoke) [[ $# == 2 && -n "${2//[[:space:]]/}" ]] || fail '此操作需要一个非空参数';;
    admins) [[ $# == 1 ]] || fail 'admins不接受额外参数';;
    *) usage >&2; exit 1;;
esac
value="${2:-}"
[[ ! "$value" =~ [[:cntrl:]] ]] || fail '参数不能包含控制字符'
[[ ${#value} -le 191 ]] || fail '参数过长（最多191个字符）'
base="${DATAAGENT_REHEARSAL_DB_DIR:-/data/dataagent/rehearsal-db}"
[[ "$base" =~ ^/[a-zA-Z0-9_./-]+$ && "$base" != / && "$base" != /data && "$base" != /data/dataagent && ! -L "$base" ]] || fail '数据库目录无效'
base="$(cd -- "$base" && pwd -P)" || fail '数据库目录不存在'
project="${DATAAGENT_REHEARSAL_DB_PROJECT:-dataagent66-rehearsal-db}"
[[ "$project" =~ ^[a-z0-9][a-z0-9_-]{0,62}$ ]] || fail 'Compose项目名无效'
[[ -f "$base/.dataagent-rehearsal" && "$(cat "$base/.dataagent-rehearsal")" == "$base|$project" ]] || fail '数据库项目标记不匹配'
docker_cmd=(sudo docker)
[[ "${DATAAGENT_DOCKER_SUDO:-true}" == false ]] && docker_cmd=(docker)
d() { "${docker_cmd[@]}" "$@"; }
containers="$(d ps -q --filter "label=com.docker.compose.project=$project" --filter 'label=com.docker.compose.service=mysql')"
[[ -n "$containers" && "$containers" != *$'\n'* ]] || fail '需要且只能有一个运行中的本项目MySQL容器'
[[ "$(d inspect --format '{{index .Config.Labels "dataagent.rehearsal.base"}}' "$containers")" == "$base" ]] || fail '容器目录标记不匹配'
mysql_query() {
    d exec -i "$containers" sh -ec '
        export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
        exec mysql --protocol=socket --connect-timeout=5 --default-character-set=utf8mb4 --batch --skip-column-names -uroot dataagent_platform
    '
}
# Encode user input as bytes; never interpolate it into SQL string literals.
hex="$(printf '%s' "$value" | od -An -tx1 | tr -d ' \n')"
target="(CONVERT(0x${hex:-00} USING utf8mb4) COLLATE utf8mb4_unicode_ci)"
columns="user_id, username, COALESCE(display_name, ''), COALESCE(company, ''), COALESCE(department, ''), roles_csv"
case "$action" in
    search)
        printf 'SELECT %s FROM dataagent_user WHERE LOCATE(%s, CONCAT_WS(CHAR(10), username, display_name, company, department)) > 0 ORDER BY username LIMIT 100;\n' "$columns" "$target" | mysql_query
        exit 0;;
    show)
        result="$(printf 'SELECT %s FROM dataagent_user WHERE username=%s;\n' "$columns" "$target" | mysql_query)"
        [[ -n "$result" ]] || fail '账号不存在；请先用search查找BI英文账号'
        printf '%s\n' "$result"; exit 0;;
    admins)
        mysql_query <<SQL
SELECT $columns FROM dataagent_user WHERE FIND_IN_SET('admin', REPLACE(COALESCE(roles_csv, ''), ' ', '')) > 0 ORDER BY username;
SQL
        exit 0;;
esac
mkdir -p -- "$base/ops"
[[ ! -L "$base/ops" && ! -L "$base/ops/user-admin.log" && ! -L "$base/ops/.user-admin.lock" ]] || fail '审计目录或文件不能为符号链接'
exec 9> "$base/ops/.user-admin.lock"
flock -w 15 9 || fail '另一个用户管理操作仍在运行'
exec 8>> "$base/ops/user-admin.log"
chmod 0600 "$base/ops/user-admin.log"
audit() {
    printf '%s\t%s@%s\t%s\t%s\t%s\n' "$(date -u +%FT%TZ)" "$(id -un)" "$(hostname)" "$action" "$value" "$*" >&8
}
audit START
desired='user'
[[ "$action" == grant ]] && desired='user,admin'
# Lock actual rows before counting admins. This also serializes cross-host CLI changes.
if ! result="$(mysql_query <<SQL
SET SESSION innodb_lock_wait_timeout=10;
START TRANSACTION;
SELECT '__LOCK__', user_id FROM dataagent_user ORDER BY user_id FOR UPDATE;
SELECT COUNT(*), MAX(COALESCE(roles_csv, '')) INTO @targets, @before FROM dataagent_user WHERE username=$target;
SET @normalized=REPLACE(COALESCE(@before,''), ' ', '');
SET @known=(@normalized='' OR @normalized REGEXP '^(user|admin)(,(user|admin))*$');
SELECT COUNT(*) INTO @admins FROM dataagent_user WHERE FIND_IN_SET('admin', REPLACE(COALESCE(roles_csv,''), ' ', ''))>0;
SET @status=CASE WHEN @targets=0 THEN 'NOT_FOUND' WHEN @targets<>1 THEN 'AMBIGUOUS' WHEN NOT @known THEN 'UNSUPPORTED_ROLES' WHEN '$action'='revoke' AND FIND_IN_SET('admin',@normalized)>0 AND @admins<=1 THEN 'LAST_ADMIN' WHEN @normalized='$desired' THEN 'NO_CHANGE' ELSE 'UPDATED' END;
UPDATE dataagent_user SET roles_csv='$desired' WHERE username=$target AND @status='UPDATED';
SELECT '__RESULT__', @status, COALESCE(@before,''), COALESCE((SELECT roles_csv FROM dataagent_user WHERE username=$target LIMIT 1),'');
COMMIT;
SQL
)"; then
    audit SQL_FAILED
    fail 'SQL执行失败；未确认成功，请用show核对，审计已记录'
fi
line="$(printf '%s\n' "$result" | awk -F '\t' '$1=="__RESULT__" {print; count++} END {if(count!=1) exit 1}')" || { audit INVALID_RESULT; fail '数据库返回结果异常，请核对账号'; }
status="$(printf '%s\n' "$line" | cut -f2)"
before="$(printf '%s\n' "$line" | cut -f3)"
after="$(printf '%s\n' "$line" | cut -f4)"
audit "$status before=$before after=$after"
case "$status" in
    UPDATED|NO_CHANGE)
        printf '账号：%s；结果：%s；角色：%s → %s\n' "$value" "$status" "$before" "$after"
        echo '请重新从BI入口登录获取新角色；已有令牌不会立即失效。';;
    NOT_FOUND) fail '账号不存在；不会自动创建或猜测账号';;
    LAST_ADMIN) fail '不能撤销最后一个全局管理员；请先为其他账号授权';;
    UNSUPPORTED_ROLES) fail '账号含user/admin以外的角色，拒绝覆盖，请人工核对';;
    *) fail "操作未执行：$status";;
esac
