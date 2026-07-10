#!/usr/bin/env bash
# KKL-cable dump via gmenounos/kw1281test — the reference implementation, on
# the SAME cable VCDS uses. Captures the full protocol traffic (KW1281Test.log)
# for: ident, fault codes, groups 1/2/3/0, and (optional, confirmed) basic
# setting group 1. Real frames only; logs are kept under samples/deepobd/kkl-dumps/.
#
# Usage: tools/kkl-dump.sh [/dev/ttyUSB0]
set -u
REPO="$(cd "$(dirname "$0")/.." && pwd)"
TOOL_DIR="$HOME/DEV/tools/kw1281test"
OUT="$REPO/samples/deepobd/kkl-dumps/$(date +%Y%m%d-%H%M%S)"

# --- prerequisites -----------------------------------------------------------
if ! command -v dotnet >/dev/null; then
  echo "dotnet manquant. Installe le SDK puis relance :"
  echo "  sudo dnf install dotnet-sdk-8.0        # Fedora"
  exit 1
fi
if [ ! -d "$TOOL_DIR" ]; then
  echo "== clone kw1281test =="
  git clone --depth 1 https://github.com/gmenounos/kw1281test.git "$TOOL_DIR" || exit 1
fi
echo "== build =="
(cd "$TOOL_DIR" && dotnet build -v quiet) || exit 1

# --- port --------------------------------------------------------------------
PORT="${1:-}"
if [ -z "$PORT" ]; then
  PORT=$(ls /dev/ttyUSB* 2>/dev/null | head -1)
fi
[ -n "$PORT" ] || { echo "ERREUR: aucun /dev/ttyUSB* (câble KKL branché ?)"; exit 1; }
[ -r "$PORT" ] || echo "NOTE: si 'permission denied' -> sudo usermod -aG dialout $USER (puis re-login), ou lance avec sudo"
echo "Port: $PORT"
mkdir -p "$OUT"

# One kw1281test invocation = one full session (5-baud init inside).
# The tool appends everything to KW1281Test.log in its working dir; we snapshot
# that log after each command.
run() { # run <label> <timeout_s> <command...>
  local label="$1" tmo="$2"; shift 2
  echo ""
  echo "== $label : kw1281test $PORT $BAUD 1 $* =="
  (cd "$TOOL_DIR" && rm -f KW1281Test.log && timeout "$tmo" dotnet run --no-build -- "$PORT" "$BAUD" 1 "$@")
  if [ -f "$TOOL_DIR/KW1281Test.log" ]; then
    cp "$TOOL_DIR/KW1281Test.log" "$OUT/${label}.log"
    echo "   -> $OUT/${label}.log ($(wc -l < "$OUT/${label}.log") lignes)"
  else
    echo "   -> pas de log produit"
  fi
}

# --- find a working baud with ReadIdent (9600 usual; this ECU synced at 1200) -
BAUD=""
for b in 9600 10400 1200; do
  echo ""
  echo "== essai baud $b (ReadIdent) =="
  (cd "$TOOL_DIR" && rm -f KW1281Test.log && timeout 40 dotnet run --no-build -- "$PORT" "$b" 1 ReadIdent)
  if [ -f "$TOOL_DIR/KW1281Test.log" ] && grep -qiE "037906024|DIGIFANT" "$TOOL_DIR/KW1281Test.log"; then
    BAUD="$b"
    cp "$TOOL_DIR/KW1281Test.log" "$OUT/ident-baud$b.log"
    echo "   BAUD RETENU: $b ✓"
    break
  fi
done
[ -n "$BAUD" ] || { echo "ERREUR: aucune identification à 9600/10400/1200 (contact mis ?)"; exit 1; }

# --- the captures ------------------------------------------------------------
run "faults"  60 ReadFaultCodes
run "group1"  30 GroupRead 1
run "group2"  30 GroupRead 2
run "group3"  30 GroupRead 3
run "group0"  30 GroupRead 0

echo ""
read -r -p "Lancer BasicSetting 1 ? (le manuel: moteur chaud; règle projet: confirmation explicite) [y/N] " yn
if [ "${yn:-n}" = "y" ]; then
  run "basicsetting1" 40 BasicSetting 1
fi

echo ""
echo "== TERMINÉ — logs dans $OUT =="
ls -la "$OUT"
echo "Pense à committer: git add samples/deepobd/kkl-dumps && git commit"
