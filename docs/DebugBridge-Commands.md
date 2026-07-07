# Debug Bridge — adb command reference (vehicle session)

Debug builds register a broadcast receiver so a laptop can drive the KWP1281
session live over adb while at the car. Watch results with:

```bash
adb logcat -s DIGIDASH_DBG
```

Send commands (action `io.databang.digidash.DEBUG`):

```bash
# connect using the saved dongle (forces real backend)
adb shell am broadcast -a io.databang.digidash.DEBUG --es cmd connect

# adapter probe result + battery voltage (proves the link before ECU decode)
adb shell am broadcast -a io.databang.digidash.DEBUG --es cmd adapter
adb shell am broadcast -a io.databang.digidash.DEBUG --es cmd voltage

# raw ECU identification blocks (reveals the real part-number format)
adb shell am broadcast -a io.databang.digidash.DEBUG --es cmd id
adb shell am broadcast -a io.databang.digidash.DEBUG --es cmd identify

# read one group / scan a range / poll one group live while revving
adb shell am broadcast -a io.databang.digidash.DEBUG --es cmd group --ei n 0
adb shell am broadcast -a io.databang.digidash.DEBUG --es cmd scan --ei from 0 --ei to 15
adb shell am broadcast -a io.databang.digidash.DEBUG --es cmd poll --ei n 0 --ei count 40 --el interval 400

# read DTCs
adb shell am broadcast -a io.databang.digidash.DEBUG --es cmd dtc

# send arbitrary bytes to the adapter and log the response (framing probing)
adb shell am broadcast -a io.databang.digidash.DEBUG --es cmd raw --es hex "82 F1 F1 FE FE 60"

# tune framing live, then reconnect to apply (fix first-contact issues without rebuild)
#   depair: auto|on|off   block: full|titleonly   acks: on|off
adb shell am broadcast -a io.databang.digidash.DEBUG --es cmd set --es depair off --es block full --es acks on --ei baud 9600 --ei addr 1
adb shell am broadcast -a io.databang.digidash.DEBUG --es cmd disconnect
adb shell am broadcast -a io.databang.digidash.DEBUG --es cmd connect

# pull the raw byte capture (debug build allows run-as)
adb exec-out run-as io.databang.digidash sh -c 'cat files/logs/raw_*.log'
```

## Framing hypotheses to try live if group reads fail

1. `set --es block full --es acks on --es depair auto` (default — Kotlin builds
   the full KWP1281 block, sends ACKs, auto de-pair).
2. `set --es block titleonly --es acks off` — firmware builds the block framing
   and handles acks (send just title+data).
3. `set --es depair off` / `--es depair on` — toggle RX (data,status) de-pairing.
4. Vary `--ei baud 4800|9600|10400` for early Digifant units.

Reconnect after each `set`. Capture-raw should be ON so every byte is logged.
