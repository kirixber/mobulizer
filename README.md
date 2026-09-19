# Mobulizer

> turn your old shit into a side desk aesthetic —- Real-ime wireless audio visualizer.

Got an old Android phone lying around?

Put it next to your monitor, connect it to the same Wi-Fi, and let it vibe with whatever you're listening to.

No fancy setup. No need for the phone to play the audio. It just becomes a dedicated little visualizer for your desk.

<p align="center">
  <img src="assets/logo.png" alt="Mobulizer" width="420">
</p>

## features

- real-time music visualization powered by CAVA
- smooth, fluid animations
- mirrored stereo spectrum
- bass hits harder in the center
- wireless over Wi-Fi
- low latency<br>
- fullscreen Android client
- multiple phones at the same time
- made for old phones sitting in a drawer doing nothing

## how it works

```text
music on your PC
       ↓
      CAVA
       ↓
   mobulizer
       ↓
     Wi-Fi
       ↓
   old phone
       ↓
   ✦ vibing ✦
```

## getting started

### Windows

1. Download [Python](https://www.python.org/downloads/) and make sure it's added to your system PATH.
2. Download the Windows client.
3. Open a terminal in the Windows Client directory.
4. Run:

```powershell
python.exe .\bridge.py
```

2. Mobulizer server started
3. Make sure your phone is on the same Wi-Fi
4. Open the Android app
5. Put the phone on your desk
6. play some music
7. enjoy the glow ✦

### Android

Just install the APK, open it, and leave it running.

That's basically it.

## why?

Because throwing a perfectly usable phone into a drawer feels wrong.

Mobulizer gives that old phone one more job:

**sit on your desk and look cool.**

## project status

This thing is still being built.

Expect bugs. Expect random shit to break.
But hey, that's part of the fun.

It's my first time building so stay tuned for updates ᓚ₍⑅^..^₎♡

## Folder Structure

```markdown
Mobulizer/
│
├── README.md
├── LICENSE
├── .gitignore
│
├── Android Client/
│   ├── app/
│   │   ├── src/
│   │   │   └── main/
│   │   │       ├── java/
│   │   │       │   └── com/mobulizer/visualizer/
│   │   │       │       ├── MainActivity.java
│   │   │       │       ├── VisualizerGLView.java
│   │   │       │       ├── VisualizerRenderer.java
│   │   │       │       └── VisualizerView.java
│   │   │       ├── res/
│   │   │       └── AndroidManifest.xml
│   │   └── build.gradle
│   ├── build.gradle
│   ├── settings.gradle
│   └── README.md
│
├── Windows Client/
│   ├── bridge.py
│   ├── mobulizer.conf
│   ├── requirements.txt
│   ├── README.md
│   │
│   └── cava/
│       ├── cava.exe
│       ├── mobulizer_runtime.conf   ← generated at runtime
│       └── ...other CAVA files...
│
└── docs/
    ├── architecture.md
    └── protocol.md
```

## Downloads

--> [Android](https://github.com/kirixber/mobulizer/releases/latest)<br>
--> [Windows](https://github.com/kirixber/mobulizer/releases/latest)<br>
--> Linux larping soon >.<

## contributing

Found something broken? Have an idea?

Open an issue or send a PR.

Don't overthink it.

would love to collaborate <3<br>

## third-party

Mobulizer uses [CAVA](https://github.com/karlstav/cava) for real-time audio analysis.

CAVA is licensed under the **GNU General Public License v3.0**.
See the CAVA source and license included with the Windows client for its applicable license information.

Mobulizer's own code is licensed under the MIT License.

## licenses

Mobulizer --> MIT
CAVA      --> GPLv3

---

made with too much time and an old phone.
