# Shared settings for the acceptance sweep. Source it: `. tools/acceptance/env.sh`
# Override any of these in the environment before sourcing.
ACC_REPO="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/../.." && pwd)"
: "${ACC_SERVER_PORT:=25710}"
: "${ACC_RCON_PORT:=25711}"
: "${ACC_RCON_PASS:=dev}"
: "${ACC_SIDECAR_PORT:=8097}"
: "${ACC_SEED:=4242}"
: "${ACC_HEAP:=8g}"
: "${ACC_LEVEL:=acc}"
# Private store (a copy of the shared store-next; never the shared store itself)
: "${ACC_STORE:?set ACC_STORE to a private copy of the store (cp -R <repo>/store-next <dir>)}"
: "${SWISSDATA_RAW:=<repo>/.data-cache/raw}"
: "${ACC_SCRATCH:=$ACC_STORE/../scratch}"
ACC_RUN="$ACC_REPO/mod/run"
ACC_DIM="$ACC_RUN/$ACC_LEVEL/dimensions/swissworld/switzerland"
export ACC_REPO ACC_SERVER_PORT ACC_RCON_PORT ACC_RCON_PASS ACC_SIDECAR_PORT ACC_SEED ACC_HEAP ACC_LEVEL ACC_STORE \
       SWISSDATA_RAW ACC_SCRATCH ACC_RUN ACC_DIM
case "$ACC_STORE" in
  */swiss-world/store|*/swiss-world/store/|*/swiss-world/store-next|*/swiss-world/store-next/)
    echo "refusing: ACC_STORE points at a shared store ($ACC_STORE)" >&2; return 1 2>/dev/null || exit 1;;
esac
