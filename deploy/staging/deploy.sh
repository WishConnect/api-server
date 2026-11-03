#!/usr/bin/env bash
set -euo pipefail

# 운영 app/service/env 경로를 받는 인자를 두지 않는다.
APP_DIR=/home/ubuntu/wishconnect-staging
INCOMING="$APP_DIR/incoming"
UNIT=wishconnect-staging
EXPECTED_COMMIT="${1:?expected commit SHA required}"
[[ "$EXPECTED_COMMIT" =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid commit SHA'; exit 1; }
command -v psql >/dev/null
command -v python3 >/dev/null
sudo -n true

cd "$INCOMING"
trap 'rm -f "$INCOMING/staging.env"' EXIT
export EXPECTED_COMMIT
python3 - <<'PY'
import hashlib, json, os, pathlib
data = json.loads(pathlib.Path('staging-version.json').read_text())
if data['commit'] != os.environ['EXPECTED_COMMIT']:
    raise SystemExit('Artifact commit mismatch')
if hashlib.sha256(pathlib.Path('staging-release.jar').read_bytes()).hexdigest() != data['sha256']:
    raise SystemExit('Artifact checksum mismatch')
PY

# 시크릿 파일을 shell source/eval 하지 않는다. 값은 subprocess 환경으로만 전달한다.
python3 - <<'PY'
import os, pathlib, shlex, subprocess, urllib.parse
values = {}
for line in pathlib.Path('staging.env').read_text().splitlines():
    if not line.strip() or line.lstrip().startswith('#'):
        continue
    key, sep, value = line.partition('=')
    if not sep or not key.strip().replace('_', '').isalnum():
        raise SystemExit('Invalid staging environment line')
    parsed = shlex.split(value, comments=False)
    if len(parsed) > 1:
        raise SystemExit('Environment values containing spaces must be quoted')
    values[key.strip()] = parsed[0] if parsed else ''
url = values.get('STAGING_DB_URL', '')
database = urllib.parse.urlparse(url.removeprefix('jdbc:')).path.removeprefix('/')
if not url.startswith('jdbc:postgresql://') or not database.endswith('_staging'):
    raise SystemExit('A separate *_staging PostgreSQL database is required')
if int(values.get('STAGING_REDIS_DATABASE', '1')) <= 0:
    raise SystemExit('Staging must not use Redis database 0')
required = ['STAGING_DB_USERNAME', 'STAGING_DB_PASSWORD', 'STAGING_REDIS_HOST', 'STAGING_JWT_SECRET']
if any(not values.get(key) for key in required):
    raise SystemExit('Missing staging configuration')
env = {**os.environ, 'PGPASSWORD': values['STAGING_DB_PASSWORD'],
       'PGOPTIONS': '-c lock_timeout=5s -c statement_timeout=60s'}
subprocess.run(['psql', url.removeprefix('jdbc:'), '-U', values['STAGING_DB_USERNAME'],
                '-v', 'ON_ERROR_STOP=1', '-f',
                'src/main/resources/db/migration/V20261102_01__scholarship_pipeline_review.sql'],
               env=env, check=True)
PY

mkdir -p "$APP_DIR/releases"
sudo install -m 600 -o root -g root staging.env /etc/wishconnect-staging.env
sudo install -m 644 -o root -g root deploy/staging/wishconnect-staging.service \
  /etc/systemd/system/wishconnect-staging.service
cp staging-release.jar "$APP_DIR/releases/$EXPECTED_COMMIT.jar"
cp staging-version.json "$APP_DIR/releases/$EXPECTED_COMMIT.json"
PREVIOUS="$(readlink "$APP_DIR/app.jar" || true)"
ln -sfn "$APP_DIR/releases/$EXPECTED_COMMIT.jar" "$APP_DIR/app.jar"
sudo systemctl daemon-reload
sudo systemctl reset-failed "$UNIT" || true
if ! sudo systemctl restart "$UNIT"; then
  if [[ -n "$PREVIOUS" && -f "$PREVIOUS" ]]; then
    ln -sfn "$PREVIOUS" "$APP_DIR/app.jar"
    sudo systemctl restart "$UNIT" || true
  fi
  exit 1
fi

for attempt in $(seq 1 36); do
  if curl -fsS --max-time 3 http://127.0.0.1:18081/actuator/health | grep -q '"status":"UP"'; then
    rm -f staging.env
    echo "Staging healthy: $EXPECTED_COMMIT (functional comparison still required)"
    exit 0
  fi
  sleep 5
done

if [[ -n "$PREVIOUS" && -f "$PREVIOUS" ]]; then
  ln -sfn "$PREVIOUS" "$APP_DIR/app.jar"
  sudo systemctl reset-failed "$UNIT" || true
  sudo systemctl restart "$UNIT" || true
else
  sudo systemctl stop "$UNIT" || true
fi
rm -f staging.env
echo 'Staging health failed; previous JAR restored when available'
exit 1
