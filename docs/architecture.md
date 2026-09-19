# Mobulizer Architecture

Mobulizer is split into two clients:

- **Android Client** — renders the fullscreen visualizer.
- **Windows Client** — captures/analyzes system audio and sends visualization data over the local network.

## High-level flow

```text
Music playing on Windows
          │
          ▼
        CAVA
          │
          ▼
     bridge.py
          │
       UDP / Wi-Fi
          │
          ▼
   Android Client
          │
          ▼
Fullscreen visualizer
```

The Android phone does not play the audio. It only receives visualization data and renders it.

## Windows Client

`bridge.py` is the network bridge between the Windows audio-analysis process and Android devices.

Its responsibilities are:

1. Start CAVA with the configured bar count.
2. Read CAVA's raw 16-bit bar data from stdout.
3. Add a Mobulizer packet header containing a timestamp and bar count.
4. Send visualization packets over UDP.
5. Listen for Android control messages that describe the display width and requested bar count.
6. Reconfigure the CAVA source when the connected client requests a different bar count.

The Windows client currently uses only Python's standard library.

## Android Client

The Android application receives UDP visualization packets and renders them using OpenGL ES.

`MainActivity.java`
: Owns the fullscreen activity and lifecycle.

`VisualizerGLView.java`
: Hosts the OpenGL rendering surface.

`VisualizerRenderer.java`
: Receives UDP data, reconstructs the mirrored spectrum layout, smooths the visual motion, and draws the bars.

## Visualization

The visual layout is designed to resemble a CAVA-style mirrored spectrum:

```text
TREBLE ... MID ... BASS | BASS ... MID ... TREBLE
                         CENTER
```

Bass is placed around the center, with higher frequencies extending toward the edges.

## Network model

The Android client sends control information to the Windows bridge using UDP port `49322`.

Visualization data is sent using UDP port `49321`.

UDP is used because visualization frames are transient: displaying the newest frame is more important than retransmitting an old one.

## Repository layout

```text
Mobulizer/
├── README.md
├── LICENSE
├── .gitignore
│
├── Android Client/
│   ├── app/
│   ├── build.gradle
│   ├── settings.gradle
│   └── README.md
│
├── Windows Client/
│   ├── bridge.py
│   ├── mobulizer.conf
│   ├── requirements.txt
│   ├── README.md
│   └── cava/
│
└── docs/
    ├── architecture.md
    └── protocol.md
```
