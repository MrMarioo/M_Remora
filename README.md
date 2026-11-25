# MRemora

Cross-platform remote desktop for Windows and Linux, written in modern Java.

MRemora lets you see and control a Windows or Linux computer from another machine in real time. An agent runs on the controlled computer, captures the screen and injects mouse and keyboard input, while a relay server connects both sides, even behind NAT.

> 🚧 Work in progress. This document describes the target architecture.

## Architecture

```mermaid
flowchart LR
    subgraph Host["Controlled machine"]
        A["Agent<br/>capture · encode · input"]
    end
    subgraph Relay["Relay cluster"]
        LB["Load balancer"]
        R1["Relay node 1"]
        R2["Relay node N"]
        REDIS[("Redis<br/>sessions · pairing codes")]
        LB --> R1
        LB --> R2
        R1 <--> REDIS
        R2 <--> REDIS
    end
    subgraph Client["Controlling machine"]
        V["Viewer<br/>render · send input"]
    end
    A <-- "TLS" --> LB
    V <-- "TLS" --> LB
```

| Component | Responsibility |
|---|---|
| **Agent** | Captures the screen, detects changed regions, encodes frames, injects mouse and keyboard input |
| **Relay** | Authenticates clients, pairs sessions, forwards traffic without decoding it |
| **Viewer** | Renders frames, sends input, shows connection stats |
| **Protocol** | Shared binary message format and codecs |

### Connection flow

1. The agent connects over TLS and receives a short-lived, single-use pairing code.
2. The viewer connects with that code and the relay links both sessions.
3. The agent streams screen updates; the viewer sends input back.
4. The person at the controlled machine sees an active-session indicator and can end it at any time.

## Architectural assumptions

### Platforms
- **Windows 10+** and **Linux on X11** first.
- **Linux on Wayland** in a later stage, through XDG Desktop Portals and PipeWire, with the user approving access in a system dialog.
- Platform code sits behind small interfaces (`ScreenCapturer`, `InputInjector`), selected at runtime.
- Screen geometry includes **DPI scaling and monitor layout**, so input coordinates always match what the viewer sees.

### Performance
- **Send only what changed:** the screen is split into tiles; only changed tiles are encoded.
- **Input before video:** mouse and keyboard events never wait behind frame data.
- **Drop, don't buffer:** if the viewer falls behind, old frames are discarded so latency and memory stay bounded.
- **Off-heap buffer pools** for frame data, benchmarked with JMH, to keep GC pauses out of the hot path.
- Native capture through the **Foreign Function & Memory API** (Desktop Duplication on Windows, XShm or PipeWire on Linux), without JNI.
- Targets: 30 FPS at 1080p for office work, under 50 ms p95 latency on a LAN, under 1 Mbit/s for a mostly static screen.

### Input
- **Physical keys** are sent as USB HID codes, which works for shortcuts and games regardless of OS.
- **Text** is sent as Unicode, so keyboard layouts and characters like `ą`, `ę`, `ż` arrive correctly.

### Scalability and reliability
- Relay nodes are **stateless** apart from open connections; sessions and pairing codes live in Redis with a TTL.
- Relays scale horizontally behind a load balancer (Kubernetes HPA).
- Netty event loops for I/O, virtual threads for blocking work.
- Heartbeats detect dead connections; agents reconnect with exponential backoff.
- Protocol version is negotiated in the first message, so older and newer clients can still talk.

### Security
- **TLS 1.3** on every connection.
- Pairing codes expire after a few minutes, work once, and are invalidated after repeated failed attempts.
- Clipboard sharing is **off by default**, text only, with a size limit.
- The relay never decodes or stores screen content. End-to-end encryption between agent and viewer is planned.

### Privacy and consent
MRemora is meant for controlling your own machines or helping someone **with their explicit consent**. A session always shows a visible indicator on the controlled machine and can be ended there at any time.

### Out of scope (for now)
- Controlling elevated (administrator) windows, UAC prompts and the Windows login screen; these require running the agent as a system service.
- File transfer, audio, macOS.
- Direct peer-to-peer connections.

## Modules

```
mremora/
├── mremora-protocol/   # messages, codecs, framing
├── mremora-agent/      # capture, encoding, input injection
├── mremora-relay/      # Netty server, pairing, routing, metrics
├── mremora-viewer/     # JavaFX client
└── mremora-testkit/    # fakes, fixtures, load generator
```

## Build

Requires JDK 25 or newer. Maven is provided by the wrapper.

```bash
./mvnw verify        # Linux
mvnw.cmd verify      # Windows
```

## Install

Download the installer for your system from the repository's Releases page. Each package contains two programs: **MRemora Agent** (share this computer's screen) and **MRemora Viewer** (connect to someone else's computer).

### Windows

1. Run `MRemora-setup-<version>.exe`. The installer is unsigned, so SmartScreen may warn you: click "More info" and then "Run anyway".
2. It installs per user and needs no administrator rights. Shortcuts are added to the Desktop and the Start menu.
3. Start **MRemora Agent** on the machine you want to control and allow it through Windows Firewall when prompted.

**Firewall and network profile.** Windows 11 marks a newly joined Wi-Fi network as *Public* by default, and an app allowed only on private networks is then still blocked. Either set the network to Private (Settings → Network & internet → Wi-Fi or Ethernet → the network's Properties → *Private network*), or allow MRemora Agent on public networks too. If you clicked Cancel in the firewall prompt, fix it in Windows Security → Firewall & network protection → *Allow an app through firewall*: find MRemora Agent and tick the network types you need.

### Linux

Ubuntu 24.04+ or Debian 13+:

```bash
sudo apt install ./mremora_<version>_amd64.deb
```

The package adds two menu entries: "MRemora Agent" and "MRemora Viewer".

On Wayland, the agent shows a system screen-sharing dialog that you must accept. If the mouse does not move on the controlled machine, log in with the "Ubuntu on Xorg" session instead.

### How to connect

1. Start **MRemora Agent** on the machine to be controlled. It shows its IP address, the port and a 6-digit code.
2. Start **MRemora Viewer** on the other machine, enter the IP address and the code, and connect.

### Logs

The agent writes `agent0.log` and the viewer `viewer0.log` (older runs rotate to `agent1.log`, `agent2.log`, ...):

- Windows: `%LOCALAPPDATA%\MRemora\logs`
- Linux: `$XDG_STATE_HOME/mremora/logs`, or `~/.local/state/mremora/logs` if `XDG_STATE_HOME` is not set

### Build packages

Build the app-image first, then the package for the same system:

```bash
scripts/build-app-image.sh 0.1.0         # 1. jpackage app-image with both programs (app version X.Y.Z)
scripts/build-deb.sh 0.1.0               # 2a. Linux: .deb, same version as the app-image
scripts/build-installer.sh 2026.11.25.1  # 2b. Windows (Git Bash, needs NSIS): setup.exe, version YYYY.MM.DD.N
```

A merge into `main` builds both packages and publishes them as a GitHub Release. To get test builds from another branch, run the Release workflow manually (Actions → Release → Run workflow); that only uploads artifacts.

## Run from source

Start the agent on the controlled machine. It opens a small always-on-top window with a 6-digit pairing code and listens on port 7420.

```bash
./mvnw -q install -DskipTests                                   # Linux, once
./mvnw -q -pl mremora-agent exec:java
./mvnw -q -pl mremora-agent exec:java -Dexec.args="--port 7500" # custom port
```

```bat
mvnw.cmd -q install -DskipTests
mvnw.cmd -q -pl mremora-agent exec:java
mvnw.cmd -q -pl mremora-agent exec:java -Dexec.args="--port 7500"
```

Then start the viewer on the controlling machine. Enter the agent's IP address and the code shown in its window; the port defaults to 7420.

```bash
./mvnw -q install -DskipTests                                   # Linux, once (skip if already done)
./mvnw -q -pl mremora-viewer javafx:run
```

```bat
mvnw.cmd -q install -DskipTests
mvnw.cmd -q -pl mremora-viewer javafx:run
```

On Windows, allow the agent through Windows Firewall when prompted on the first start, otherwise other machines on the LAN cannot connect (see the firewall note under [Windows](#windows) if the network is Public).

## Tech stack

Java 25 · Netty · Foreign Function & Memory API · JavaFX · Redis · Micrometer · Prometheus · JUnit 6 · JMH · Testcontainers · Docker · Kubernetes · GitHub Actions

## Roadmap

- [ ] **Stage 1:** LAN connection, full frames, mouse control (Windows, Linux X11)
- [ ] **Stage 2:** tile-based deltas, compression, flow control, stats overlay
- [ ] **Stage 3:** relay, TLS, pairing codes, Redis
- [ ] **Stage 4:** keyboard (HID + Unicode text), clipboard
- [ ] **Stage 5:** native capture via FFM, hardware or royalty-free video codec
- [ ] **Stage 6:** Wayland support
- [ ] **Stage 7:** multi-node relays on Kubernetes, load tests

## License

MIT © 2026 MTech Solutions Mariusz Śmistek
