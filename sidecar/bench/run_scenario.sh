#!/bin/sh
# usage: run_scenario.sh NAME LABEL [--cold] [--cprofile] CELL...   (re-clones the scenario from sidecar-speed-base each run)
set -e
D=<repo>/.data-cache
W=${SRC:-$(cd "$(dirname "$0")/.." && pwd)}
PY=<worktrees>/preview-texture/sidecar/.venv/bin/python
NAME=$1; LABEL=$2; shift 2
FLAGS=""; PREP=""
while [ "${1#--}" != "$1" ]; do case $1 in --cold) PREP="--cold";; --cprofile) FLAGS="--cprofile";; esac; shift; done
cd $D
$PY $W/bench/prep_scenario.py sidecar-speed-base sidecar-speed-$NAME $PREP "$@" >/dev/null
nice -n 19 $PY $W/bench/profile_cell.py --store sidecar-speed-$NAME/store --raw sidecar-speed-$NAME/raw --scratch sidecar-speed-$NAME/scratch --out sidecar-speed-out/$NAME-$LABEL $FLAGS --label $LABEL "$@"
