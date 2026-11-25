# MRemora

Remote desktop for Windows and Linux, written in Java.

MRemora lets you see and control another computer in real time. It has two programs:

- **MRemora Agent** runs on the computer you want to control. It shares the screen and accepts mouse input.
- **MRemora Viewer** runs on your computer. It shows the remote screen and sends your mouse input.

Windows and Linux work both ways: you can control a Linux machine from Windows and the other way round.

> 🚧 Work in progress. It currently works on a local network (LAN) only.

## Planned

- Faster screen updates (only changed parts are sent), compression
- Connecting over the internet through a relay server, with TLS
- Keyboard and clipboard
- Wayland support on Linux

## Install

Download the package for your system from the [Releases](../../releases) page.

**Windows:** run `MRemora-setup-<version>.exe`. No administrator rights needed. If SmartScreen warns you, click "More info" → "Run anyway".

**Linux** (Ubuntu 24.04+ / Debian 13+):

```bash
sudo apt install ./mremora_<version>_amd64.deb
```

## How to use

1. Start **MRemora Agent** on the computer you want to control. It shows its IP address and a 6-digit code. On Windows, allow it through the firewall when asked.
2. Start **MRemora Viewer** on your computer, enter the IP address and the code, and connect.

## Run from source

Requires JDK 25 or newer.

```bash
./mvnw -q install -DskipTests
./mvnw -q -pl mremora-agent exec:java      # agent
./mvnw -q -pl mremora-viewer javafx:run    # viewer
```

On Windows use `mvnw.cmd` instead of `./mvnw`.

## License

MIT © 2026 MTech Solutions Mariusz Śmistek
