#!/usr/bin/env bash
# Generates the repository's secrets on first use: .env at the repository
# root, git-ignored and readable only by you (mode 600). Idempotent —
# existing keys are never changed, so this is safe to run every time.
# Rotating a secret means editing .env and restarting the stack; the Keycloak
# client secret, the Keycloak admin password and the databases' own passwords
# are fixed when their container's data is first created, so rotating those
# needs the admin console or a reset (scripts/stack.sh identity reset --yes).
# STORAGE_DB_OWNER_PASSWORD (the storage schema owner's) and
# STORAGE_DB_SERVICE_PASSWORD (the storage service's) are set again by
# storage-db-roles and storage-migrate on every scripts/stack.sh storage up,
# and MINIO_IMAGES_SECRET_KEY (the storage service's MinIO user's) by
# storage-minio-init; MINIO_ROOT_PASSWORD is fixed like the databases'.
# ACTUATOR_PASSWORD (the storage actuator's account, which Spring Boot Admin
# signs in with) is read when the service starts.
# Every key must hold a value: an empty one fails here, before anything can
# start without it. JWT_SECRET alone may be empty — production leaves it so.
# It signs the development tokens (storage/scripts/dev-token.sh, used by the
# seed scripts), which the storage service accepts only when DEV_TOKENS=true
# is added to .env: this script never sets that.
#
#   scripts/secrets.sh          ensure .env exists and is complete
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
envfile=$root/.env

# name:length — hex bytes are half as long, and safe unquoted in .env
keys=(
  JWT_SECRET:32
  SESSION_SECRET:32
  PRESENTATION_CLIENT_SECRET:32
  STORAGE_DB_PASSWORD:16
  STORAGE_DB_OWNER_PASSWORD:16
  STORAGE_DB_SERVICE_PASSWORD:16
  KEYCLOAK_DB_PASSWORD:16
  KEYCLOAK_ADMIN_PASSWORD:16
  ADMIN_PASSWORD:16
  ACTUATOR_PASSWORD:16
  MINIO_ROOT_PASSWORD:16
  MINIO_IMAGES_SECRET_KEY:16
)

command -v openssl >/dev/null || { echo "secrets: openssl is required" >&2; exit 1; }

added=0
if [[ -f $envfile ]]; then
  chmod 600 "$envfile"
else
  umask 077
  : >"$envfile"
  echo "secrets: created $envfile (git-ignored, mode 600)"
fi

for entry in "${keys[@]}"; do
  name=${entry%%:*} bytes=${entry##*:}
  if grep -qE "^${name}=" "$envfile"; then
    if [[ $name != JWT_SECRET && -z $(sed -n "s/^${name}=//p" "$envfile" | tr -d '[:space:]') ]]; then
      echo "secrets: $name is empty in $envfile — set a value, or delete its line to generate one" >&2
      exit 1
    fi
  else
    printf '%s=%s\n' "$name" "$(openssl rand -hex "$bytes")" >>"$envfile"
    added=$((added + 1))
  fi
done

if ((added)); then
  echo "secrets: added $added key(s) to $envfile"
fi

# Each project directory gets .env as a symlink, so `cd identity && docker
# compose up` (and Next.js's own .env loading in presentation/) find the same
# secrets without copying them. A copy found there instead (of this .env as
# it was) would miss the keys added since and keep secrets rotated since: it
# is replaced, if every line of it is in .env, and named otherwise.
for sub in identity storage presentation; do
  link=$root/$sub/.env
  [[ -L $link ]] && continue
  if [[ -e $link ]]; then
    if grep -qvxF -f "$envfile" "$link"; then
      echo "secrets: $link is a file of its own, not a link to $envfile, and holds what that does not;" \
        "compose and npm run dev in $sub/ read it instead. Keep what you need, then: ln -sf ../.env $link" >&2
      continue
    fi
    rm -f "$link"
    echo "secrets: replaced the copy $link with a link to $envfile"
  fi
  ln -s ../.env "$link"
done
