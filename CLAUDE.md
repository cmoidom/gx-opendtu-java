# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Java port of [gx-opendtu](https://github.com/cmoidom/gx-opendtu) (Python): a
zero-injection ("zero export") controller for a single-phase PV install with
Hoymiles micro-inverters (via OpenDTU) and a Victron Cerbo GX grid meter.
Unlike the Python original, this port **only ever runs on a separate Linux
VM** (never on the Cerbo GX itself), so grid/battery reading is always over
Modbus TCP — there is no D-Bus/Venus OS code path at all.

Read [`ARCHITECTURE.md`](ARCHITECTURE.md) before making non-trivial changes,
and [`AGENTS.md`](AGENTS.md) for the invariants that must not be broken
(sign conventions, fail-safe behavior, Modbus register layout, etc.) and the
testable/non-testable boundary.

## Commands

Requires JDK 21 + Maven (neither was available when this port was written —
verify they're installed before running any of these).

```sh
mvn test                                    # full test suite
mvn -q -Dtest=ControllerTest test           # a single test class
mvn -q -Dtest=ControllerTest#quantizeRoundsToNearestStep test   # a single test method
mvn package                                 # builds target/gx-opendtu-java.jar (shaded, single jar)
java -jar target/gx-opendtu-java.jar --config config/config.json --dry-run   # run without writing to OpenDTU
```

There is no separate lint step configured (no checkstyle/spotless plugin).

## Architecture

Package root: `gxopendtu` (no `io.github...` prefix — deliberately flat).

- `config/` — `AppConfig` (records) + `ConfigLoader` (JSON parse/validate via Jackson).
- `control/` — pure control-loop math, no I/O: `SoftTargetController` (PI +
  quantization + ramp), `CapacityEstimator`, `BatteryFullHysteresis`,
  `GridPowerSmoother`. Fully unit-testable without a socket.
- `allocator/` — `WaterFillAllocator`, pure multi-inverter power distribution.
- `modbus/` — hand-written minimal Modbus TCP client (`ModbusTcpClient`, MBAP
  framing + function code 3 only) and pure register codecs
  (`RegisterCodec`: signed int16, big-endian 32-bit combination). No
  third-party Modbus library — see AGENTS.md for why.
- `grid/`, `battery/` — `GridMeter`/`BatterySoc` interfaces + their sole
  `Modbus*` implementations, thin I/O wrappers with no business logic.
- `opendtu/` — `OpenDTUApi` (interface used by the control loop, so tests can
  supply a fake with no real HTTP) and `OpenDTUClient` (real implementation,
  `java.net.http.HttpClient`).
- `state/` — thread-safe shared state: `LiveState` (ring buffer for the
  dashboard), `HourlyEnergyHistory`, `ManualOverride`, `InjectionModeOverride`,
  `StateStore` (the one bit of state persisted to disk, `state.json`).
- `loop/ControlLoop` — orchestration: `run(...)` wires everything from
  config and loops forever (not unit-tested, same as Python's `main.run()`);
  its package-private static helpers (`decisionCycle`, `applyFailsafe`,
  `releaseForCharging`, etc.) are the actual unit-test surface, exercised via
  a hand-written `FakeOpenDTUApi` in tests — mirrors `tests/test_dry_run.py`
  from the Python original.
- `webui/` — `com.sun.net.httpserver.HttpServer`-based config page and live
  dashboard (`dashboard.html` under `src/main/resources/webui/` is reused
  near-verbatim from the Python project — it's already pure client-side JS
  polling `/status.json`).
- `Main` — entry point (`--config`, `--dry-run`), wires config → control
  loop → optional web UI.

Test layout mirrors `src/main/java` under `src/test/java`, same packages
(tests of package-private methods must stay in the same package as the class
under test — see `loop/ControlLoopDryRunTest` + `loop/FakeOpenDTUApi`,
`modbus/FakeModbusServer`).

## Git workflow

Standing authorization (2026-07-13): after implementing a change on this
repo, if `mvn test` passes, commit and push to `origin/main` immediately
without asking for confirmation first. This overrides the general
"always confirm before push" default for this repository specifically.
Still stop and ask if tests fail, if the change is unusually large/risky
(e.g. touches deployment/systemd config, rewrites history, force-pushes),
or if explicitly told to hold off in a given conversation.
