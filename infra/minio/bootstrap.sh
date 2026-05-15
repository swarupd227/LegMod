#!/bin/sh
# Idempotent: create the corpus and artifact buckets on first boot.
set -e

mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"

for bucket in "$MINIO_BUCKET_CORPUS" "$MINIO_BUCKET_ARTIFACTS"; do
  if mc ls "local/$bucket" >/dev/null 2>&1; then
    echo "bucket $bucket already exists"
  else
    mc mb "local/$bucket"
    echo "created bucket $bucket"
  fi
done

# Default lifecycle: corpus envelopes auto-delete after 90 days for dev.
mc ilm rule add --expire-days 90 "local/$MINIO_BUCKET_CORPUS" || true

echo "minio bootstrap complete"
