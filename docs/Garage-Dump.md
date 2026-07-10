# Garage dump — capture real KW1281 frames for offline work

One adb command captures the REAL raw group frames (0x02 headers + 0xF4 bodies)
to a file on the tablet, so we can decode offline afterwards. No invented data.

## Steps (tablet connected via USB, dongle on the OBD port, ignition on)

```bash
T=348dfe79   # adb serial of the Tab S3
B() { adb -s $T shell am broadcast -a io.databang.digidash.DEBUG "$@" >/dev/null; }

# 1. Connect to the ECU (or use the app's Home > Connect)
B --es cmd connect
#    wait ~15 s, confirm:  adb -s $T logcat -d -s DIGIDASH_DBG | grep 'connect ->'

# 2. Dump groups 0,1,2,3 for 30 s (engine idling warm is best; also try revving)
B --es cmd dumpgroups --es groups "0,1,2,3" --ei seconds 30
#    it logs the file path when done:
adb -s $T logcat -d -s DIGIDASH_DBG | grep 'dumpgroups DONE'

# 3. Pull the file (path printed above), e.g.:
adb -s $T pull /sdcard/Android/data/io.databang.digidash/files/dumps/kwp-dump-<ts>.txt .
```

Optional variants:
- Basic-settings group 1 frames: enter Basic Settings in the app first (Timing
  tab, confirmation dialog), then `dumpgroups --es groups "1" --ei seconds 20`.
- Longer window: `--ei seconds 60`.

## What the file contains

Plain text, one line per raw stream reply:
```
# ecu=[037906024AG, DIGIFANT 1.7 ..., ...]
group 1 T=02 [8B 1A 11 ... 88 FF 00]     # header
group 1 T=F4 [.. .. .. ..]               # body  <- the missing piece
group 2 T=02 [...]
...
```
`T=02` = header, `T=F4` = body, `T=0A` = group refused. A header followed by a
`T=F4` body on the same group = the live header/body handshake confirmed.

Then decode is done from THIS file — real bytes only.
