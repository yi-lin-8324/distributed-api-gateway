#!/usr/bin/env bash
set -e

if [ -z "${API_KEY:-}" ]; then
    echo "API_KEY environment variable is missing"
    exit 1
fi

KEY_HASH=$(printf '%s' "$API_KEY" | shasum -a 256 | awk '{print $1}')

docker exec -i gateway-postgres psql -U gateway -d gateway <<SQL
CREATE TABLE IF NOT EXISTS api_keys (
    key_hash TEXT PRIMARY KEY,
    active BOOLEAN NOT NULL,
    user_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL
);

INSERT INTO api_keys (key_hash, active, user_id, tenant_id)
VALUES ('$KEY_HASH', true, 'user_1', 'tenant_A')
ON CONFLICT (key_hash) DO NOTHING;
SQL