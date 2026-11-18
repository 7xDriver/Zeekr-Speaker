# ZEEKR Speaker

**Version: 0.1.0**

ZEEKR Speaker is an independent, experimental Android Automotive utility for sending live microphone audio and selected sound clips to the vehicle's exterior speaker.

Unlike the official ZEEKR application, which restricts its exterior audio functionality to when the vehicle is parked, ZEEKR Speaker is designed to make exterior audio functionality available while the vehicle is in Drive.

Operation while the vehicle is moving is available after accepting the terms associated with the maximum speed setting (greater than 0 km/h). When driving, use of the floating button controls is required instead of interacting with the main application interface.

> **Important:** This is an unofficial third-party project. It is not made, approved, supported or endorsed by ZEEKR or its affiliates.

---

## Features

### Live Microphone Audio

- Send live microphone audio to the vehicle's external speaker.
- **Push-to-Talk:** Temporarily activate microphone audio while the control is pressed.
- **Push-to-Activate:** Activate microphone audio using the floating control.

### Audio Presets

- Store up to 10 audio presets.
- Assign custom labels to individual presets.
- Play selected sound clips through the vehicle's external speaker.

### Speed Limiter

- Set a maximum vehicle speed from 0 to 100 km/h, in 10 km/h increments.
- Audio playback is permitted only while the vehicle is below the selected speed limit.
- A setting of 0 km/h allows audio playback only while stationary (default).
- Selecting a limit above 0 km/h requires acknowledgement that application audio may mix or overlap with AVAS and affect the audibility of pedestrian warning sounds.
- Live microphone audio and preset playback are automatically stopped if the vehicle exceeds the selected speed limit.

### Floating Controls

- Configurable floating controls for convenient access to application functions.
- Designed to allow operation without interacting with the main application interface while driving.

### Car Microphone Recorder (Save to USB)

- Record audio using the vehicle's built-in microphone.
- Save recordings to a selected USB drive.
- Recordings are saved as WAV files.

**Recording format:**
- Sample rate: 44.1 kHz
- Channels: Mono
- Bit depth: 16-bit PCM

### Diagnostics

The diagnostics section provides information about selected application functions, including:

- Microphone permission and availability.
- Overlay permission status.
- Whether the configured exterior speaker audio output is available to the application.
- Current microphone gain setting (default: 4×).

### Play Car Music

The Play Car Music feature is temporarily unavailable pending further review.

Related implementation files may remain in the project, but the feature is not currently presented as an available application function.

---

## Important Safety Information

### Operation While Driving

Users should configure and test the application while the vehicle is safely parked.

Using exterior audio while driving may create distractions, affect other road users or interact with factory vehicle audio functions. Users are responsible for considering the circumstances in which the application is used and ensuring that its operation does not compromise vehicle safety.

The application must not be used in a way that distracts the driver, obstructs the driver's attention or interferes with required vehicle warning systems.

### AVAS and Factory Vehicle Audio Systems

Particular care is required regarding the **Acoustic Vehicle Alerting System (AVAS)**.

This application is not designed, tested or certified to operate alongside, control, replace or integrate with the vehicle's AVAS system.

The developer makes no representation that application audio is isolated from factory vehicle audio or safety-related warning sounds.

Do not bypass, disable or interfere with vehicle safety systems. Stop using the application if unexpected interactions with factory audio or warning functions occur.

### Responsible Use

Users must not use the application to:

- Imitate emergency sirens or official emergency warnings.
- Create excessive noise or unreasonable disturbance.
- Compromise the audibility of required vehicle warning sounds.

Users are responsible for complying with applicable road safety, vehicle equipment, noise and loudspeaker regulations.

---

## Compatibility

ZEEKR Speaker is designed for the Aus ZEEKR 7X running OS 2.1.1

Compatibility and behaviour may depend on vehicle model, hardware configuration, Android Automotive version, vehicle firmware, available audio output devices and routing configuration.

Feature availability may vary between vehicles and software versions. The application does not guarantee an independent or isolated audio path from factory vehicle audio or warning systems.

---

## Privacy

ZEEKR Speaker processes microphone audio for its intended audio functions.

The application does not transmit microphone recordings or selected audio files to the developer.

Users should understand that audio played through the vehicle's exterior speaker can be heard by people outside the vehicle.

---

## Disclaimer

ZEEKR Speaker is provided as experimental software on an **"as is" and "as available"** basis.

The developer makes no warranties or representations regarding the application's reliability, compatibility, suitability, safety or uninterrupted operation.

Use of the application is entirely at the user's own risk.

The application is not a ZEEKR product and must not be represented as an official ZEEKR application or endorsed product.
