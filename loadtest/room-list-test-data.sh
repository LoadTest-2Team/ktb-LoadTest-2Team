#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

rooms=30
min_participants=5
max_participants=10
messages=100
random_seed=42
prefix="loadtest-room-list"
mode="seed"
container="mongo-ktb"
mongo_uri=""
password="Test1234!"

usage() {
  sed -n '/^# Usage:/,/^# dataset cannot/p' "$0" | sed 's/^# \{0,1\}//'
}

# Usage:
#   ./loadtest/room-list-test-data.sh [options]
#
# Options:
#   --rooms N          rooms to create/expect (default: 30)
#   --min-participants N  minimum unique participants per room (default: 5)
#   --max-participants N  maximum unique participants per room (default: 10)
#   --messages N       recent messages per room (default: 100)
#   --seed N           reproducible participant-count seed (default: 42)
#   --prefix VALUE     isolated dataset prefix (default: loadtest-room-list)
#   --verify           verify only; do not write
#   --cleanup          delete only data owned by the prefix
#   --container NAME   MongoDB container name (default: mongo-ktb)
#   --mongo-uri URI    override apps/backend/.env MONGO_URI
#   --password VALUE   password for generated users (default: Test1234!)
#   -h, --help         show this help
#
# The script runs mongosh inside the existing MongoDB container. It never drops
# a collection or database. Seed mode refuses an existing prefix so a comparison
# dataset cannot be accidentally regenerated between test runs.

while [[ $# -gt 0 ]]; do
  case "$1" in
    --rooms) rooms="${2:?missing value for --rooms}"; shift 2 ;;
    --min-participants) min_participants="${2:?missing value for --min-participants}"; shift 2 ;;
    --max-participants) max_participants="${2:?missing value for --max-participants}"; shift 2 ;;
    --messages) messages="${2:?missing value for --messages}"; shift 2 ;;
    --seed) random_seed="${2:?missing value for --seed}"; shift 2 ;;
    --prefix) prefix="${2:?missing value for --prefix}"; shift 2 ;;
    --verify) mode="verify"; shift ;;
    --cleanup) mode="cleanup"; shift ;;
    --container) container="${2:?missing value for --container}"; shift 2 ;;
    --mongo-uri) mongo_uri="${2:?missing value for --mongo-uri}"; shift 2 ;;
    --password) password="${2:?missing value for --password}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

for value in "$rooms" "$min_participants" "$max_participants"; do
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || { echo "rooms and participant bounds must be positive integers" >&2; exit 2; }
done
[[ "$messages" =~ ^[0-9]+$ ]] || { echo "messages must be a non-negative integer" >&2; exit 2; }
[[ "$random_seed" =~ ^[0-9]+$ ]] && (( 10#$random_seed <= 4294967295 )) || { echo "seed must be an integer between 0 and 4294967295" >&2; exit 2; }
(( min_participants <= max_participants )) || { echo "min-participants cannot exceed max-participants" >&2; exit 2; }
[[ "$prefix" =~ ^[a-z0-9][a-z0-9-]{2,39}$ ]] || { echo "prefix must be 3-40 lowercase letters, digits, or hyphens" >&2; exit 2; }

if [[ -z "$mongo_uri" && -f "${PROJECT_DIR}/apps/backend/.env" ]]; then
  mongo_uri="$(sed -n 's/^MONGO_URI=//p' "${PROJECT_DIR}/apps/backend/.env" | tail -n 1)"
fi
mongo_uri="${mongo_uri:-mongodb://localhost:27017/bootcamp-chat}"

# When mongosh runs inside the Mongo container, localhost correctly means that
# container. The DB name is extracted separately so scripts also work with URI
# query parameters.
database="${mongo_uri%%\?*}"
database="${database%/}"
database="${database##*/}"
database="${database:-bootcamp-chat}"

password_hash=""
if [[ "$mode" == "seed" ]]; then
  command -v htpasswd >/dev/null || { echo "htpasswd is required to create a BCrypt password hash" >&2; exit 1; }
  password_hash="$(htpasswd -bnBC 4 loadtest "$password" | cut -d: -f2)"
fi

command -v docker >/dev/null || { echo "docker is required" >&2; exit 1; }
docker inspect "$container" >/dev/null 2>&1 || { echo "MongoDB container '$container' is not running" >&2; exit 1; }

echo "Mode: $mode"
echo "Prefix: $prefix"
echo "Database: $database"
echo "Rooms / participant range / messages / seed: $rooms / $min_participants-$max_participants / $messages / $random_seed"

docker exec -i \
  -e LOADTEST_MODE="$mode" \
  -e LOADTEST_ROOMS="$rooms" \
  -e LOADTEST_MIN_PARTICIPANTS="$min_participants" \
  -e LOADTEST_MAX_PARTICIPANTS="$max_participants" \
  -e LOADTEST_MESSAGES="$messages" \
  -e LOADTEST_RANDOM_SEED="$random_seed" \
  -e LOADTEST_PREFIX="$prefix" \
  -e LOADTEST_DATABASE="$database" \
  -e LOADTEST_PASSWORD_HASH="$password_hash" \
  "$container" mongosh --quiet "$mongo_uri" --file /dev/stdin \
  < "${SCRIPT_DIR}/room-list-test-data.js"
