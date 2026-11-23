# Third-party notices

CarStalker is MIT-licensed. The following data and references are used with compatible terms.

## Generic DTC descriptions

- **Source:** [OBDex](https://github.com/foerbsnavi/obdex), file `generic.min.json`, accessed 2026-11-22.
- **What is used:** the code and English title of 9,533 generic SAE J2012 trouble codes (`P0`, `P2`, `P3`, `U0`, `U3`, `C0`, `B0`).
- **License:** [CC0 1.0 Universal](https://creativecommons.org/publicdomain/zero/1.0/legalcode) (public domain dedication). Attribution is not required; it is given here anyway.
- **Caveat:** some manufacturers reuse generic-range codes with their own meaning. Manufacturer-specific codes are not included and are shown as such in the app.

## VIN / WMI

- World Manufacturer Identifier prefixes are public registration data. The bundled lookup is a small heuristic for common passenger cars, not a complete VIN decoder. It is used only to label a vehicle, never to select a service function.

## Guided diagnosis references

The catalyst-vs-O2 wizard was informed by public technical explanations and independent technician guidance:

- [APEX Tech Nation — Diagnosing Catalyst Efficiency Codes, P0420 and P0430](https://apextechnation.com/articles/diagnosing-catalyst-efficiency-codes) — ASE Master Tech procedure: engine health first, upstream/downstream waveform comparison, temperature test, downstream-sensor response and exhaust-leak checks.
- [TroubleCodes.net — P0420](https://www.troublecodes.net/pcodes/p0420/) — upstream switching vs downstream steady signal, and the causes that mimic a failed converter.
- [AA1Car — Oxygen Sensors: How to Diagnose and Replace](https://www.aa1car.com/library/o2sensor.htm) — narrowband O2 voltage range and switching behaviour.
- [EngineerFix — Which O2 Sensor Is on the Catalytic Converter?](https://engineerfix.com/which-o2-sensor-is-on-the-catalytic-converter/) — catalyst oxygen-storage explanation.

The rules, thresholds, wording and UI are CarStalker's own. No article text or service-publication text is copied. The thresholds are heuristics and require real-car validation; the wizard presents evidence and confidence, not a guarantee.

## Standards and protocol references

CarStalker implements protocol behaviour from public facts and observed adapter responses. The following references informed the implementation:

- SAE J1979 / ISO 15031-5 — OBD-II services and PIDs (service identifiers, PID formulas, Mode 06 structure).
- ISO 15765-2 — ISO-TP framing.
- ISO 14229 — UDS service identifiers and negative response codes (future enhanced diagnostics).
- ELM Electronics, *ELM327 datasheet* — AT command behaviour, throughput and multi-frame limits.
- OBD Solutions, *STN1110 vs ELM327* — adapter capability comparison.

No paid standard text, OEM service publication, or proprietary PID/DTC database is copied into this repository.

## Open-source references

The project does not link or copy code from the following, but they were consulted for protocol understanding:

- [python-OBD](https://github.com/brendan-w/python-OBD) — GPL-2.0. Reference only; no code copied.
- [python-udsoncan](https://github.com/pylessard/python-udsoncan) — MIT. Reference for UDS service semantics.
- [commaai/opendbc](https://github.com/commaai/opendbc) — MIT. Future CAN signal definitions.
- [SavvyCAN](https://github.com/collin80/SavvyCAN) — MIT. Future CAN monitor UX reference.

If you believe a file has been included without the correct license, open an issue and it will be removed or corrected.
