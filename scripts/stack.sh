#!/usr/bin/env bash
# Manages the Docker Compose stack (compose.yaml) one project at a time:
#
#   scripts/stack.sh PROJECT COMMAND [ARG]
#
# PROJECT  identity  Keycloak, its PostgreSQL database, and Mailpit
#          storage   the storage service, its PostgreSQL database, MinIO for
#                    its images, and Spring Boot Admin
#          presentation  the web application (it keeps no data)
#          edge      nginx, the way in to the web application (:3000) and
#                    Keycloak (:8180); identity and presentation start it too
#          all       all of them
# COMMAND  up            build if needed and start, waiting until healthy
#          deps          (storage) start only what a locally run service needs:
#                        Keycloak (through the edge), the storage database and
#                        MinIO
#          stop          stop and remove the containers; data is kept
#          restart       restart the containers
#          status        containers and data volumes
#          logs          follow the logs
#          backup [DIR]  dump the databases (and images) into DIR,
#                        default backups/PROJECT-YYYYmmdd-HHMMSS
#          restore DIR   replace the data with a backup made by "backup"
#          prune-images [--yes]
#                        (storage) list the images no product or review
#                        uses and that were last uploaded more than
#                        PRUNE_IMAGES_OLDER_THAN (default 7d) ago; delete them
#                        with --yes
#          reset --yes   delete the containers and ALL their data
#          admin EMAIL   (identity) make a registered account a site
#                        administrator (Keycloak's "admin" realm role)
#
# Environment: DOCKER_CONTEXT selects the Docker daemon; COMPOSE_PROJECT_NAME
# overrides the project (default jamesleigh-review). BIND_ADDRESS sets the
# interface published ports bind to (default 127.0.0.1; 0.0.0.0 exposes the
# stack to the network). Secrets live in .env at the repository root, which
# scripts/secrets.sh generates on first use (every command ensures it).
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
project=${COMPOSE_PROJECT_NAME:-jamesleigh-review}
[[ -n ${DOCKER_CONTEXT:-} ]] || unset DOCKER_CONTEXT

# The comment above, up to the first line that is not one.
usage() { awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0"; exit "${1:-0}"; }
die() { echo "stack: $*" >&2; exit 1; }
compose() {
  local envfile=()
  [[ -f $root/.env ]] && envfile=(--env-file "$root/.env")
  docker compose --project-directory "$root" "${envfile[@]}" -f "$root/compose.yaml" -p "$project" "$@"
}

[[ $# -ge 2 ]] || usage 1
target=$1 command=$2 arg=${3:-}

case $target in
  identity)
    services=(keycloak keycloak-config keycloak-db mailpit)
    apps=(keycloak keycloak-config mailpit) ; volumes=(keycloak-db) ;;
  storage)
    services=(storage storage-admin storage-migrate storage-db-roles storage-db storage-minio-init storage-minio)
    apps=(storage storage-admin) ; volumes=(storage-db storage-minio) ;;
  presentation) services=(presentation) ; apps=(presentation) ; volumes=() ;;
  edge) services=(edge) ; apps=(edge) ; volumes=() ;;
  all)
    services=() ; apps=(keycloak keycloak-config mailpit storage storage-admin presentation edge)
    volumes=(keycloak-db storage-db storage-minio) ;;
  -h | --help | help) usage ;;
  *) die "unknown project '$target' (identity, storage, presentation, edge or all)" ;;
esac

# Keycloak and the web application are reached only through the edge, so
# starting either starts it too.
case $target in identity | presentation) up_also=(edge) ;; *) up_also=() ;; esac

# Every command renders the Compose configuration, which needs the secrets.
"$root/scripts/secrets.sh"

running() { [[ -n $(compose ps -q --status running "$1" 2>/dev/null) ]]; }

require_running() {
  for service in "$@"; do
    running "$service" || die "$service is not running; start it with: scripts/stack.sh $target up"
  done
}

# dump SERVICE USER DATABASE FILE: a pg_dump custom-format archive.
dump() {
  echo "  $3 database -> $4"
  compose exec -T "$1" pg_dump -U "$2" -d "$3" --format=custom >"$4"
}

# restore_db SERVICE USER DATABASE FILE: replaces the database's contents.
# Triggers stay off while loading, so the journal is restored as it was
# rather than re-recording every row. Grants are left out, as the roles they
# name may not exist yet: storage-migrate grants the storage service's again.
# Restored as the superuser, the objects are its until storage-db-roles gives
# them back to the schema's owner.
restore_db() {
  echo "  $4 -> $3 database"
  compose exec -T "$1" pg_restore -U "$2" -d "$3" --clean --if-exists --no-owner --no-privileges \
    --disable-triggers --single-transaction <"$4"
}

# minio_mc DIR ARGS...: the MinIO client, as MinIO's root user, in a
# throwaway container (storage-minio-init's image and credentials) running as
# you, with DIR at /backup.
minio_mc() {
  local dir=$1
  shift
  # shellcheck disable=SC2016 # Expanded by bash in the container, from its environment.
  compose run --rm --no-deps -T --user "$(id -u):$(id -g)" -v "$dir:/backup" --entrypoint bash storage-minio-init -c \
    'export MC_CONFIG_DIR=$(mktemp -d) MC_HOST_store=${MINIO_URL/:\/\//://$MINIO_ROOT_USER:$MINIO_ROOT_PASSWORD@}
     mc --quiet "$@"' mc "$@"
}

# listed: mc's --json listing (ls or find) on stdin, as "NAME SIZE" lines
# sorted by name; any error fails it.
listed() {
  local line
  while IFS= read -r line; do
    [[ $line == *'"status":"success"'* && $line =~ \"size\":([0-9]+).*\"key\":\"([^\"]+)\" ]] ||
      { echo "stack: cannot list the images: $line" >&2; return 1; }
    echo "${BASH_REMATCH[2]##*/} ${BASH_REMATCH[1]}"
  done | LC_ALL=C sort
}

# objects DIR TARGET: the images in the bucket (store/review-images) or in a
# directory of DIR (/backup/...), as listed.
objects() { minio_mc "$1" ls --recursive --json "$2" | listed; }

# missing EXPECTED ACTUAL: how many lines of EXPECTED (both listed) ACTUAL
# lacks. mc mirror exits 0 even when it skipped some files, so every copy is
# checked with this.
missing() { LC_ALL=C comm -23 "$1" "$2" | wc -l | tr -d ' '; }

# start [ARGS]: up -d the apps and wait until they are healthy.
# keycloak-config runs once and exits, which --wait would count as a
# failure: wait for the others, then run it to completion.
start() {
  local waited=() app
  for app in "${apps[@]}" "${up_also[@]}"; do [[ $app == keycloak-config ]] || waited+=("$app"); done
  compose up -d "$@" --wait "${waited[@]}"
  if [[ " ${apps[*]} " == *" keycloak-config "* ]]; then compose up --no-log-prefix keycloak-config; fi
}

case $command in
  up)
    start --build
    compose ps
    ;;

  deps)
    [[ $target == storage ]] || die "deps applies to storage only"
    compose up -d --wait keycloak storage-db storage-minio edge
    compose up --no-log-prefix storage-db-roles
    compose up --no-log-prefix storage-minio-init
    compose up keycloak-config
    echo "Keycloak: http://localhost:${KEYCLOAK_HTTP_PORT:-8180}  storage database: localhost:${STORAGE_DB_PORT:-5433}" \
      " MinIO (images): http://localhost:${STORAGE_MINIO_PORT:-9100}"
    ;;

  stop)
    if [[ $target == all ]]; then compose down; else compose rm --stop --force "${services[@]}"; fi
    ;;

  restart)
    if [[ $target == all ]]; then compose restart; else compose restart "${apps[@]}"; fi
    ;;

  status)
    if [[ $target == all ]]; then compose ps -a; else compose ps -a "${services[@]}"; fi
    echo
    for volume in "${volumes[@]}"; do
      name=${project}_$volume
      if docker volume inspect "$name" >/dev/null 2>&1; then
        echo "volume $name: $(docker volume inspect -f '{{.CreatedAt}}' "$name")"
      else
        echo "volume $name: none"
      fi
    done
    ;;

  logs)
    if [[ $target == all ]]; then compose logs -f --tail=200; else compose logs -f --tail=200 "${services[@]}"; fi
    ;;

  backup)
    [[ $target != presentation ]] || die "presentation keeps no data to back up"
    dir=${arg:-$root/backups/$target-$(date +%Y%m%d-%H%M%S)}
    mkdir -p "$dir"
    dir=$(cd "$dir" && pwd)
    echo "Backing up $target to $dir"
    if [[ $target == identity || $target == all ]]; then
      require_running keycloak-db
      dump keycloak-db keycloak keycloak "$dir/keycloak.dump"
    fi
    if [[ $target == storage || $target == all ]]; then
      require_running storage-db storage-minio
      dump storage-db postgres reviews "$dir/reviews.dump"
      # Into images.partial/ first, named images/ only once it has every
      # image the bucket had when the copy began: restore makes the bucket
      # exactly images/, so an incomplete copy must never be taken for one.
      echo "  images bucket -> $dir/images/"
      work=$(mktemp -d)
      trap 'rm -rf "$work"' EXIT
      rm -rf "$dir/images.partial"
      mkdir "$dir/images.partial"
      objects "$dir" store/review-images >"$work/bucket"
      minio_mc "$dir" mirror --overwrite store/review-images /backup/images.partial >/dev/null
      objects "$dir" /backup/images.partial >"$work/copied"
      lost=$(missing "$work/bucket" "$work/copied")
      [[ $lost == 0 ]] ||
        die "$lost of the bucket's $(wc -l <"$work/bucket" | tr -d ' ') images were not copied; back up again"
      rm -rf "$dir/images"
      mv "$dir/images.partial" "$dir/images"
      echo "  $(wc -l <"$work/copied" | tr -d ' ') images"
    fi
    echo "Done. Restore with: scripts/stack.sh $target restore $dir"
    ;;

  restore)
    [[ $target != presentation ]] || die "presentation keeps no data to restore"
    [[ -n $arg && -d $arg ]] || die "restore needs a backup directory"
    dir=$(cd "$arg" && pwd)
    echo "Restoring $target from $dir"
    if [[ $target == identity || $target == all ]]; then
      [[ -f $dir/keycloak.dump ]] || die "$dir has no keycloak.dump"
      compose up -d --wait keycloak-db
      compose stop keycloak
      restore_db keycloak-db keycloak keycloak "$dir/keycloak.dump"
    fi
    if [[ $target == storage || $target == all ]]; then
      [[ -f $dir/reviews.dump && ( -d $dir/images || -f $dir/images.tar ) ]] ||
        die "$dir needs reviews.dump and images/ (or images.tar, from before MinIO)"
      compose up -d --wait storage-db
      compose stop storage
      restore_db storage-db postgres reviews "$dir/reviews.dump"
      compose up --no-log-prefix storage-db-roles
      compose up --no-log-prefix storage-migrate
      # The bucket becomes exactly the backup's images. A backup from before
      # MinIO holds them as images.tar, unpacked for this.
      compose up -d --wait storage-minio
      compose up --no-log-prefix storage-minio-init
      work=$(mktemp -d)
      trap 'rm -rf "$work"' EXIT
      images=$dir
      if [[ ! -d $dir/images ]]; then
        images=$work
        mkdir "$images/images"
        tar -C "$images/images" -xf "$dir/images.tar"
      fi
      objects "$images" /backup/images >"$work/backup"
      echo "  $images/images ($(wc -l <"$work/backup" | tr -d ' ') images) -> images bucket"
      minio_mc "$images" mirror --overwrite /backup/images store/review-images >/dev/null
      objects "$images" store/review-images >"$work/bucket"
      lost=$(missing "$work/backup" "$work/bucket")
      [[ $lost == 0 ]] || die "$lost of the backup's images did not reach the bucket, which lost none; restore again"
      # Only now, with all of the backup's images, does the bucket lose the others.
      cut -d' ' -f1 "$work/backup" >"$work/backup.names"
      cut -d' ' -f1 "$work/bucket" | LC_ALL=C comm -13 "$work/backup.names" - |
        sed 's|^|store/review-images/|' >"$work/extra"
      if [[ -s $work/extra ]]; then minio_mc "$images" rm --force --stdin <"$work/extra" >/dev/null; fi
      objects "$images" store/review-images >"$work/bucket"
      cmp -s "$work/backup" "$work/bucket" || die "the bucket still has images the backup does not; restore again"
    fi
    start
    echo "Restored."
    ;;

  prune-images)
    [[ $target == storage ]] || die "prune-images applies to storage only"
    [[ -z $arg || $arg == --yes ]] || die "prune-images takes only --yes"
    require_running storage-db storage-minio
    age=${PRUNE_IMAGES_OLDER_THAN:-7d}
    work=$(mktemp -d)
    trap 'rm -rf "$work"' EXIT
    # Uploading an image again makes it new (image.ImageStore), so one being
    # added to a review is not old. The images are listed before the
    # database is asked, so one a review takes up meanwhile is in its answer.
    minio_mc "$work" find store/review-images --older-than "$age" --json | listed >"$work/old"
    compose exec -T storage-db psql -U postgres -d reviews -v ON_ERROR_STOP=1 -AtX -c \
      "SELECT substring(image FROM 9) FROM product WHERE image IS NOT NULL
       UNION SELECT substring(image FROM 9) FROM review WHERE image IS NOT NULL" | LC_ALL=C sort >"$work/used"
    cut -d' ' -f1 "$work/old" | LC_ALL=C comm -23 - "$work/used" >"$work/unused"
    unused=$(wc -l <"$work/unused" | tr -d ' ')
    if [[ $unused == 0 ]]; then
      echo "No unused images last uploaded more than $age ago."
    elif [[ $arg != --yes ]]; then
      echo "$unused unused images, last uploaded more than $age ago:"
      sed 's/^/  /' "$work/unused"
      echo "Delete them with: scripts/stack.sh storage prune-images --yes"
    else
      echo "Deleting $unused unused images, last uploaded more than $age ago"
      sed 's|^|store/review-images/|' "$work/unused" | minio_mc "$work" rm --force --stdin >/dev/null
      objects "$work" store/review-images | cut -d' ' -f1 >"$work/left"
      kept=$(LC_ALL=C comm -12 "$work/unused" "$work/left" | wc -l | tr -d ' ')
      [[ $kept == 0 ]] || die "$kept of them are still there; prune again"
      echo "Deleted. $(wc -l <"$work/left" | tr -d ' ') images are left."
    fi
    ;;

  reset)
    [[ $arg == --yes ]] || die "reset deletes all $target data; confirm with: scripts/stack.sh $target reset --yes"
    if [[ $target == all ]]; then
      compose down --volumes
    else
      compose rm --stop --force "${services[@]}"
      for volume in "${volumes[@]}"; do
        docker volume rm "${project}_$volume" >/dev/null 2>&1 && echo "Deleted volume ${project}_$volume" || true
      done
    fi
    ;;

  admin)
    [[ $target == identity ]] || die "admin applies to identity only"
    [[ -n $arg ]] || die "admin needs the account's email"
    # Against the Keycloak that is running: without --no-deps, Compose would
    # recreate it (signing everyone out mid-request) whenever its
    # configuration has changed since it started.
    require_running keycloak
    compose run --rm --no-deps -e REVIEWS_ADMIN_EMAILS="$arg" keycloak-config
    ;;

  *) die "unknown command '$command'; see scripts/stack.sh --help" ;;
esac
