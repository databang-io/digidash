#!/usr/bin/env bash
# Garage dump — one command: connect to the ECU and capture REAL raw group
# frames (0x02 headers + 0xF4 bodies) to samples/deepobd/dumps/ for offline
# decoding. No fabrication: this only records what the ECU actually sends.
#
# Usage:  tools/garage-dump.sh [seconds] [groups]
#   e.g.  tools/garage-dump.sh 45 "0,1,2,3"
set -u
export PATH="$PATH:$HOME/Android/Sdk/platform-tools"
T="${ADB_SERIAL:-348dfe79}"
SECONDS_CAP="${1:-45}"
GROUPS="${2:-0,1,2,3}"
OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/samples/deepobd/dumps"
mkdir -p "$OUT_DIR"

B() { adb -s "$T" shell am broadcast -a io.databang.digidash.DEBUG "$@" >/dev/null 2>&1; }
LOG() { adb -s "$T" logcat -d -s DIGIDASH_DBG 2>/dev/null; }

echo "== garage-dump: device =="
adb devices | sed '1d' | grep -q "$T" || { echo "ERREUR: tablette $T absente d'adb"; exit 1; }

echo "== réveil + app =="
adb -s "$T" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
adb -s "$T" shell monkey -p io.databang.digidash -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 3

echo "== connexion ECU (2 tentatives) =="
adb -s "$T" logcat -c 2>/dev/null
for attempt in 1 2; do
  B --es cmd connect
  sleep 16
  if LOG | grep -q "connect -> true"; then CONNECTED=1; break; fi
  echo "  tentative $attempt échouée"
  CONNECTED=0
done
[ "${CONNECTED:-0}" = "1" ] || { echo "ERREUR: connexion ECU impossible (dongle alimenté ? contact mis ?)"; LOG | tail -5; exit 1; }
echo "  connecté ✓"

echo "== dump groupes [$GROUPS] pendant ${SECONDS_CAP}s =="
echo "   (idéal: ralenti moteur chaud; donne aussi quelques coups de gaz)"
adb -s "$T" logcat -c 2>/dev/null
B --es cmd dumpgroups --es groups "$GROUPS" --ei seconds "$SECONDS_CAP"
sleep $((SECONDS_CAP + 5))

REMOTE=$(LOG | grep -oE "dumpgroups DONE -> \S+" | tail -1 | awk '{print $NF}')
[ -n "$REMOTE" ] || { echo "ERREUR: pas de fichier de dump signalé"; LOG | tail -8; exit 1; }

echo "== pull $REMOTE =="
adb -s "$T" pull "$REMOTE" "$OUT_DIR/" || exit 1
LOCAL="$OUT_DIR/$(basename "$REMOTE")"
echo ""
echo "== RÉSUMÉ $LOCAL =="
grep -c "T=02" "$LOCAL" | xargs echo "  headers (T=02):"
grep -c "T=F4" "$LOCAL" | xargs echo "  bodies  (T=F4):"
grep -c "T=0A" "$LOCAL" | xargs echo "  refus   (T=0A):"
echo ""
echo "OK — décodage offline depuis: $LOCAL"
