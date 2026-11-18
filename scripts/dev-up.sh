#!/usr/bin/env bash
set +x
set -euo pipefail

cd "$(dirname "$0")/.."
root="$(pwd -P)"
config_dir="${XDG_CONFIG_HOME:-$HOME/.config}/price-analyzer"
settings="$config_dir/dev.conf"
api_pid=
web_pid=
pg_started=false
temp_settings=

fail() { printf '%s\n' "$*" >&2; exit 1; }

cleanup() {
    trap '' INT TERM
    [[ -z "$web_pid" ]] || kill "$web_pid" 2>/dev/null || true
    [[ -z "$api_pid" ]] || kill "$api_pid" 2>/dev/null || true
    [[ -z "$web_pid" ]] || wait "$web_pid" 2>/dev/null || true
    [[ -z "$api_pid" ]] || wait "$api_pid" 2>/dev/null || true
    if "$pg_started"; then
        "$pg_ctl" -D "$pg_data" -m fast -w stop
    fi
    [[ -z "$temp_settings" ]] || rm -f -- "$temp_settings"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

prompt_setup() {
    [[ -t 0 ]] || fail 'Запустите настройку в терминале.'
    umask 077
    mkdir -p -- "$config_dir"
    local canonical
    canonical="$(cd "$config_dir" && pwd -P)"
    [[ -O "$config_dir" && ! -L "$config_dir" && "$canonical/" != "$root/"* ]] ||
        fail 'Каталог настроек должен принадлежать вам и находиться вне проекта.'
    chmod 700 "$config_dir"
    local new_url new_user existing=false
    DB_URL=jdbc:postgresql://127.0.0.1:5432/price_analyzer
    DB_USERNAME=price_user
    if [[ -e "$settings" || -L "$settings" ]]; then
        read_settings
        existing=true
    fi
    read -r -p "JDBC URL [$DB_URL]: " new_url
    DB_URL="${new_url:-$DB_URL}"
    read -r -p "Пользователь БД [$DB_USERNAME]: " new_user
    DB_USERNAME="${new_user:-$DB_USERNAME}"
    read -rs -p 'Пароль PostgreSQL: ' DB_PASSWORD
    printf '\n'
    [[ -n "$DB_PASSWORD" ]] || fail 'Введите пароль существующего пользователя PostgreSQL.'
    check_url
    if ! "$existing"; then
        read -r -p 'Каталог PGDATA (Enter для системной службы): ' pg_data
        pg_ctl=
        if [[ -n "$pg_data" ]]; then
            read -r -p 'Путь к pg_ctl (Enter для поиска через PATH): ' pg_ctl
            pg_ctl="${pg_ctl:-$(command -v pg_ctl || true)}"
            [[ -x "$pg_ctl" && -r "$pg_data/PG_VERSION" ]] || fail 'Укажите существующий PGDATA и исполняемый pg_ctl.'
        fi
        APP_JWT_SECRET="$(openssl rand -base64 32)"
    fi
    temp_settings="$(mktemp "$config_dir/dev.conf.XXXXXX")"
    printf '%s\n' "$DB_URL" "$DB_USERNAME" "$DB_PASSWORD" "$APP_JWT_SECRET" "$pg_data" "$pg_ctl" > "$temp_settings"
    if "$existing"; then
        mv -f -- "$temp_settings" "$settings"
    else
        ln "$temp_settings" "$settings" || fail 'Файл настроек уже существует.'
    fi
    printf 'Настройки сохранены в %s\n' "$settings"
}

check_url() {
    [[ "$DB_URL" =~ ^jdbc:postgresql://(localhost|127\.0\.0\.1):([0-9]+)/[^/?[:space:]]+$ ]] ||
        fail 'Укажите JDBC URL локальной PostgreSQL с портом и именем базы.'
    pg_host="${BASH_REMATCH[1]}"
    pg_port="${BASH_REMATCH[2]}"
}

read_settings() {
    [[ -f "$settings" && ! -L "$settings" && -O "$settings" ]] ||
        fail 'Сначала выполните ./scripts/dev-up.sh setup.'
    [[ "$(cd "$config_dir" && pwd -P)/" != "$root/"* ]] || fail 'Храните настройки вне проекта.'
    [[ "$(stat -f '%Lp' "$settings")" == 600 ]] || fail "Ограничьте права: chmod 600 '$settings'."
    {
        IFS= read -r DB_URL &&
        IFS= read -r DB_USERNAME &&
        IFS= read -r DB_PASSWORD &&
        IFS= read -r APP_JWT_SECRET &&
        IFS= read -r pg_data &&
        IFS= read -r pg_ctl
    } < "$settings" || fail 'Файл настроек неполный.'
}

case "${1:-}" in
    setup) prompt_setup; exit ;;
    seed) [[ "$#" == 1 ]] || fail 'Использование: dev-up.sh seed' ;;
    admin) [[ "${2:-}" =~ ^(status|create|reset)$ && "$#" == 2 ]] || fail 'Использование: dev-up.sh admin {status|create|reset}' ;;
    '') ;;
    *) fail 'Использование: dev-up.sh [setup|seed|admin {status|create|reset}]' ;;
esac

read_settings
check_url

site=http://localhost:5173
api=http://127.0.0.1:8080
if [[ -z "${1:-}" ]]; then
    for port in 5173 8080; do
        if lsof -nP -iTCP:"$port" -sTCP:LISTEN || nc -z -w 1 127.0.0.1 "$port" || nc -z -w 1 ::1 "$port"; then
            fail "Порт $port занят. Остановите прежний запуск проекта и повторите ./scripts/dev-up.sh."
        fi
    done
fi

pg_ready="$(command -v pg_isready || true)"
if [[ -z "$pg_ready" ]]; then
    for candidate in "${pg_ctl%/*}/pg_isready" /Library/PostgreSQL/*/bin/pg_isready; do
        if [[ -x "$candidate" ]]; then pg_ready="$candidate"; break; fi
    done
fi
[[ -n "$pg_ready" ]] || fail 'Добавьте каталог bin установленного PostgreSQL в PATH.'
if ! "$pg_ready" -q -h "$pg_host" -p "$pg_port" -t 2; then
    if [[ -n "$pg_data" ]]; then
        "$pg_ctl" -D "$pg_data" -l "$config_dir/postgres.log" -w start
        pg_started=true
    else
        fail "Запустите существующую службу PostgreSQL на $pg_host:$pg_port, затем повторите команду."
    fi
fi
"$pg_ready" -q -h "$pg_host" -p "$pg_port" -t 2 || fail 'PostgreSQL не отвечает по адресу из JDBC URL.'

if [[ "${1:-}" == seed ]]; then
    case ",${SPRING_PROFILES_ACTIVE:-}," in
        *,prod,*|*,production,*) fail 'Демоданные разрешены только для локальной PostgreSQL.' ;;
    esac
    [[ "${DB_URL##*/}" == price_analyzer && "$DB_USERNAME" == price_user ]] ||
        fail 'Демоданные разрешены только для локальной БД price_analyzer под ролью price_user.'
    psql="${pg_ready%/*}/psql"
    [[ -x "$psql" ]] || psql="$(command -v psql || true)"
    [[ -n "$psql" ]] || fail 'Добавьте каталог bin установленного PostgreSQL в PATH.'
    PGPASSWORD="$DB_PASSWORD" "$psql" -Xw -h "$pg_host" -p "$pg_port" -U "$DB_USERNAME" -d price_analyzer \
        -v ON_ERROR_STOP=1 --single-transaction -f scripts/demo-prices.sql
    exit
fi

export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -q -f backend/pom.xml -DskipTests package
artifact="$(sed -n 's/^artifactId=//p' backend/target/maven-archiver/pom.properties)"
version="$(sed -n 's/^version=//p' backend/target/maven-archiver/pom.properties)"
jar="$root/backend/target/$artifact-$version.jar"
[[ -f "$jar" ]] || fail 'Maven не создал запускаемый JAR.'

boot() {
    SPRING_DATASOURCE_URL="$DB_URL" SPRING_DATASOURCE_USERNAME="$DB_USERNAME" SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD" \
        SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver APP_JWT_SECRET="$APP_JWT_SECRET" APP_AUTH_ORIGIN="$site" \
        exec java -jar "$jar" "$@"
}

if [[ "${1:-}" == admin ]]; then
    (boot --local-admin "$2")
    exit
fi

[[ -x frontend/node_modules/.bin/vite ]] || npm ci --prefix frontend

(boot --spring.profiles.active=postgres --server.address=127.0.0.1 --server.port=8080) &
api_pid=$!
(cd frontend && exec node node_modules/vite/bin/vite.js --host localhost) &
web_pid=$!

for ((attempt=0; attempt<90; attempt++)); do
    kill -0 "$api_pid" "$web_pid" 2>/dev/null || fail 'Запуск приложения прервался. См. сообщения выше.'
    if curl --noproxy '*' -fs --max-time 2 "$api/api/health" >/dev/null &&
        curl --noproxy '*' -fs --max-time 2 "$site/" >/dev/null &&
        curl --noproxy '*' -fs --max-time 2 "$site/api/health" >/dev/null; then
        printf '\nPrice Analyzer: %s\nДля остановки нажмите Ctrl+C.\n' "$site"
        while kill -0 "$api_pid" "$web_pid" 2>/dev/null; do sleep 1; done
        fail 'Один из серверов завершился.'
    fi
    sleep 1
done
fail 'Приложение не ответило за время ожидания.'
