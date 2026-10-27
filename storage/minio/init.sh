#!/usr/bin/env bash
# Makes the image bucket and the storage service's MinIO user, then exits.
# Run with MinIO's root credentials, the only thing they are used for besides
# backups and restores (the storage-minio-init container, and
# TestcontainersConfiguration), much as db/owner.sql is for the database:
#
#   MINIO_URL=http://storage-minio:9000 MINIO_ROOT_USER=... MINIO_ROOT_PASSWORD=... \
#   IMAGES_BUCKET=review-images IMAGES_ACCESS_KEY=reviews-images IMAGES_SECRET_KEY=... \
#     bash init.sh
#
# The bucket is private: nothing is served from MinIO itself, as the storage
# service streams each image (image.ImageController). Its user may read,
# write and list the bucket and nothing else: no deleting an image, no other
# bucket, no administration. (A write permission covers replacing an object
# too; the service writes with If-None-Match, and copies an image onto
# itself only to mark it uploaded again.) Unused images are deleted with the
# root credentials (scripts/stack.sh storage prune-images). It is
# idempotent, and sets the user's secret each time, so changing
# MINIO_IMAGES_SECRET_KEY in .env and running this again rotates it.
set -euo pipefail

: "${MINIO_URL:?}" "${MINIO_ROOT_USER:?}" "${MINIO_ROOT_PASSWORD:?}"
: "${IMAGES_BUCKET:?}" "${IMAGES_ACCESS_KEY:?}" "${IMAGES_SECRET_KEY:?}"

# The root credentials as an mc alias, from the environment rather than the
# command line.
MC_CONFIG_DIR=$(mktemp -d)
export MC_CONFIG_DIR
export MC_HOST_store=${MINIO_URL/:\/\//://$MINIO_ROOT_USER:$MINIO_ROOT_PASSWORD@}
trap 'rm -rf "$MC_CONFIG_DIR"' EXIT

mc --quiet mb --ignore-existing "store/$IMAGES_BUCKET"
mc --quiet anonymous set none "store/$IMAGES_BUCKET"

# s3:ListBucket lets a missing image be told from a refused one (404, not
# 403) and the health check reach the bucket; s3:PutObject with
# If-None-Match never replaces an image, and copying one onto itself needs
# s3:GetObject and s3:PutObject only (image.ImageStore).
policy=$MC_CONFIG_DIR/policy.json
cat >"$policy" <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject"],
      "Resource": ["arn:aws:s3:::$IMAGES_BUCKET/*"]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:ListBucket"],
      "Resource": ["arn:aws:s3:::$IMAGES_BUCKET"]
    }
  ]
}
EOF
mc --quiet admin policy create store reviews-images "$policy"
mc --quiet admin user add store "$IMAGES_ACCESS_KEY" "$IMAGES_SECRET_KEY"
# Attaching it again, when it already is, changes nothing.
mc --quiet admin policy attach store reviews-images --user "$IMAGES_ACCESS_KEY" >/dev/null

echo "storage-minio-init: bucket $IMAGES_BUCKET, for $IMAGES_ACCESS_KEY"
