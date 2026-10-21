# Scanereyes - Server Implementation Guide

This guide describes the WebSocket protocol used by the **Scanereyes** Android application to communicate with a 3D scanner server (e.g., ESP32 or PC).

---

## Connection Details

- **Protocol:** WebSocket (`ws://`)
- **Port:** `8765`
- **URL Format:** `ws://<SERVER_IP>:8765/`
- **Data Format:** Plain text frames (single word strings, no JSON, no quotes, no trailing newlines, case-sensitive).

---

## WebSocket Messages

### 1. Server → App (Received by App)

| Message | Description |
|---|---|
| `take_photo` | Instructs the phone to capture a photo. Upon receipt, the app triggers focus (if enabled), saves the image to device storage (`Pictures/Scanereyes`), and responds with `photo_taken`. |

*Any other incoming text message is ignored by the app.*

---

### 2. App → Server (Sent by App)

| Message | Description |
|---|---|
| `photo_taken` | Sent by the app immediately after the photo is successfully captured and saved. |

---

## Recommended Server Workflow

1. **Listen for Connection:** Accept WebSocket connection at `ws://<SERVER_IP>:8765`.
2. **Request Photo:** Send the text frame `take_photo`.
3. **Wait for Response:** Wait for the `photo_taken` confirmation from the app (expected within 10 seconds).
4. **Proceed:** Advance the scanner turntable, wait for vibrations to settle, and send `take_photo` for the next angle.

---

## Connection & Ping

- **Ping/Pong:** The app sends WebSocket pings every 10 seconds. The server must respond to standard RFC 6455 ping frames.
- **Auto-Reconnect:** If the connection drops, the app automatically retries connecting every 3 seconds.
