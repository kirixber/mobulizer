import socket
import struct
import subprocess
import threading
import time
from pathlib import Path

# ============================================================
# PATHS
# ============================================================

# Everything is located relative to this bridge.py file.
BASE_DIR = Path(__file__).resolve().parent
CONFIG_DIR = BASE_DIR / "config"

CAVA = CONFIG_DIR / "cava.exe"
BASE_CONFIG = CONFIG_DIR / "mobulizer.conf"
RUNTIME_CONFIG = CONFIG_DIR / "mobulizer_runtime.conf"

# ============================================================
# NETWORK
# ============================================================

DATA_PORT = 49321
CONTROL_PORT = 49322

# Fallback only: used until the phone's first control packet
# reveals its actual IP address.
BROADCAST_DEST = ("255.255.255.255", DATA_PORT)

# ============================================================
# BAR SIZING
# ============================================================

DEFAULT_BARS = 40
MIN_BARS = 24
MAX_BARS = 64
TARGET_BAR_WIDTH = 30.0

# ============================================================
# SHARED STATE
# ============================================================

state_lock = threading.Lock()

requested_bars = DEFAULT_BARS
config_generation = 0

# IP of the phone, learned from incoming "BARS" control packets.
phone_ip = None


def validate_paths():
    """Check that the bundled Windows client files exist."""
    missing = []

    if not CAVA.is_file():
        missing.append(str(CAVA))

    if not BASE_CONFIG.is_file():
        missing.append(str(BASE_CONFIG))

    if missing:
        raise FileNotFoundError(
            "Mobulizer files are missing:\n"
            + "\n".join(f"  - {path}" for path in missing)
            + "\n\nExpected layout:\n"
              "Mobulizer-Windows/\n"
              "├── bridge.py\n"
              "└── config/\n"
              "    ├── cava.exe\n"
              "    ├── mobulizer.conf\n"
              "    └── mobulizer_runtime.conf (created automatically)"
        )


validate_paths()

with open(BASE_CONFIG, "r", encoding="utf-8") as f:
    BASE_CONFIG_TEXT = f.read()


def create_runtime_config(bars: int):
    lines = []
    found = False

    for line in BASE_CONFIG_TEXT.splitlines():
        stripped = line.strip()

        if stripped.startswith("bars") and "=" in line:
            lines.append(f"bars = {bars}")
            found = True
        else:
            lines.append(line)

    if not found:
        raise RuntimeError("Could not find 'bars =' in mobulizer.conf")

    with open(RUNTIME_CONFIG, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def calculate_bars(width: int) -> int:
    bars = round(width / TARGET_BAR_WIDTH)

    if bars % 2 != 0:
        bars += 1

    bars = max(MIN_BARS, bars)
    bars = min(MAX_BARS, bars)

    return bars


# ============================================================
# CONTROL SERVER
# ============================================================

def control_server():
    global requested_bars, config_generation, phone_ip

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", CONTROL_PORT))

    print(f"Control server listening on UDP {CONTROL_PORT}")

    while True:
        data, addr = sock.recvfrom(256)

        # ANY packet from the phone tells us where to send data.
        with state_lock:
            if phone_ip != addr[0]:
                phone_ip = addr[0]
                print(
                    f"\nPhone detected at {phone_ip} "
                    "-> sending data unicast"
                )

        try:
            message = data.decode("ascii", errors="ignore").strip()

            if not message.startswith("BARS "):
                continue

            width = int(message.split(" ", 1)[1])

        except Exception:
            continue

        bars = calculate_bars(width)

        with state_lock:
            if bars == requested_bars:
                continue

            requested_bars = bars
            config_generation += 1
            generation = config_generation

        print(
            f"\nPhone width {width}px -> "
            f"{bars} CAVA bars (generation {generation})"
        )


# ============================================================
# CAVA STDOUT HELPERS
# ============================================================

def read_exact(stream, size):
    data = bytearray(size)
    view = memoryview(data)
    position = 0

    while position < size:
        count = stream.readinto(view[position:])

        if not count:
            return None

        position += count

    return data


def start_cava(bars):
    create_runtime_config(bars)

    process = subprocess.Popen(
        [str(CAVA), "-p", str(RUNTIME_CONFIG)],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        bufsize=0,
    )

    print(f"CAVA started with {bars} bars.")

    return process


# ============================================================
# MAIN
# ============================================================

def main():
    global config_generation

    print()
    print("======================================")
    print("        MOBULIZER CAVA BRIDGE")
    print("======================================")
    print(f"Base directory: {BASE_DIR}")
    print(f"Config directory: {CONFIG_DIR}")
    print(f"Data UDP:    {DATA_PORT} (unicast once phone is known)")
    print(f"Control UDP: {CONTROL_PORT}")
    print(f"Bar range:   {MIN_BARS}-{MAX_BARS}")
    print()

    threading.Thread(
        target=control_server,
        name="Mobulizer-Control",
        daemon=True,
    ).start()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 65536)

    current_bars = DEFAULT_BARS
    process = start_cava(current_bars)
    generation_seen = 0

    frame_count = 0
    last_report = time.perf_counter()

    try:
        while True:
            # Reconfigure if the phone requested a different bar count.
            with state_lock:
                desired_bars = requested_bars
                desired_generation = config_generation

            if desired_generation != generation_seen:
                print(
                    f"\nReconfiguring CAVA: "
                    f"{current_bars} -> {desired_bars}"
                )

                try:
                    process.terminate()
                    process.wait(timeout=0.5)

                except Exception:
                    try:
                        process.kill()
                    except Exception:
                        pass

                current_bars = desired_bars
                process = start_cava(current_bars)
                generation_seen = desired_generation

            if process.stdout is None:
                raise RuntimeError("CAVA stdout unavailable.")

            frame_size = current_bars * 2
            raw = read_exact(process.stdout, frame_size)

            if raw is None:
                print("\nCAVA stopped. Restarting...")

                try:
                    process.kill()
                except Exception:
                    pass

                process = start_cava(current_bars)
                continue

            timestamp_us = time.monotonic_ns() // 1000
            header = struct.pack(
                "<4sQH",
                b"CAVA",
                timestamp_us,
                current_bars,
            )

            with state_lock:
                destination = (
                    (phone_ip, DATA_PORT)
                    if phone_ip
                    else BROADCAST_DEST
                )

            sock.sendto(header + raw, destination)
            frame_count += 1

            now = time.perf_counter()

            if now - last_report >= 1.0:
                with state_lock:
                    target = phone_ip if phone_ip else "broadcast"

                print(
                    f"\rCAVA -> {target} | "
                    f"{frame_count:3d} FPS | "
                    f"{current_bars:2d} bars",
                    end="",
                    flush=True,
                )

                frame_count = 0
                last_report = now

    except KeyboardInterrupt:
        print("\nStopping...")

    finally:
        try:
            process.terminate()
            process.wait(timeout=1)

        except Exception:
            try:
                process.kill()
            except Exception:
                pass

        sock.close()


if __name__ == "__main__":
    main()
