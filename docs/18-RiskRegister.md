# 18 — Risk Register

## R1 — Deep OBD/EdiabasLib cannot expose needed operations

Mitigation: keep DiagnosticClient abstraction, fake backend, and mark unsupported functions clearly. MVP still works as dashboard if raw block read works.

## R2 — label file format varies too much

Mitigation: tolerant parser, warnings, manual correction, do not block conversion because of one bad file.

## R3 — Mapping for 037 906 024 AG is incomplete or wrong

Mitigation: show raw values, confidence flags, logs, and compare against VCDS-Lite/Deep OBD/VCDS labels during validation.

## R4 — Old ECU communication is slow

Mitigation: sequential operation queue, conservative polling, no concurrent requests.

## R5 — User mistakes ignition procedure

Mitigation: strong warnings, checklist, no invented timing values, emphasize mechanical distributor adjustment and confirmed PMH mark.

## R6 — App crashes during trip

Mitigation: fake-mode tests, N/A handling, connection recovery, minimal Trip Dashboard polling.
