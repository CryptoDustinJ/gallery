# Swarm Bridge

HTTP API server that exposes on-device Gemma 4 and phone automation to a remote agent swarm.

## Architecture

```
┌──────────────────────────────────────────────┐
│  Phone (Android)                             │
│                                              │
│  ┌─────────────┐  ┌───────────────────────┐  │
│  │ Gemma 4 E2B │  │ Swarm Bridge Server   │  │
│  │ (LiteRT-LM) │◄─│ HTTP :8080            │  │
│  └─────────────┘  │                       │  │
│                   │ /api/chat (multimodal) │  │
│  ┌─────────────┐  │ /api/screen           │  │
│  │ Accessibility│◄─│ /api/action           │  │
│  │ Service     │  │ /api/notifications    │  │
│  └─────────────┘  │ /api/camera           │  │
│                   │ /api/status            │  │
│  ┌─────────────┐  │ /api/hive             │  │
│  │ Notification│◄─│                       │  │
│  │ Listener    │  └───────────────────────┘  │
│  └─────────────┘           ▲                 │
└────────────────────────────┼─────────────────┘
                             │ Tailscale / LAN
┌────────────────────────────┼─────────────────┐
│  WSL2 Agent Swarm          │                 │
│                            │                 │
│  DrClaude ─────────────────┘                 │
│  Rook                                        │
│  Ralph                                       │
│  Nova                                        │
│  CodeMaster                                  │
│  Dustin Code                                 │
└──────────────────────────────────────────────┘
```

## API Reference

### POST /api/chat
Send text, image, and/or audio to Gemma for processing.

```bash
# Text only
curl -X POST http://phone:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"text": "What are my Discord notifications?"}'

# Image + text (multimodal)
curl -X POST http://phone:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"image": "'$(base64 -w0 photo.png)'", "text": "What is this?"}'

# Audio + text
curl -X POST http://phone:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"audio": "'$(base64 -w0 recording.pcm)'", "text": "Transcribe this"}'
```

Response: `{"response": "...", "thinking": "..."}`

### POST /api/action
Execute phone automation directly (bypasses Gemma).

```bash
# Tap at coordinates
curl -X POST http://phone:8080/api/action \
  -d '{"action": "tap", "params": {"x": 540, "y": 1200}}'

# Type text
curl -X POST http://phone:8080/api/action \
  -d '{"action": "type", "params": {"text": "Hello world"}}'

# Swipe (scroll down)
curl -X POST http://phone:8080/api/action \
  -d '{"action": "swipe", "params": {"x1": 540, "y1": 1500, "x2": 540, "y2": 500}}'

# Open an app
curl -X POST http://phone:8080/api/action \
  -d '{"action": "open_app", "params": {"package": "com.discord"}}'

# Press back / home
curl -X POST http://phone:8080/api/action -d '{"action": "press_back"}'
curl -X POST http://phone:8080/api/action -d '{"action": "press_home"}'

# Read clipboard
curl -X POST http://phone:8080/api/action -d '{"action": "clipboard"}'
```

### GET /api/screen
Capture a screenshot.

```bash
curl http://phone:8080/api/screen
# Returns: {"image": "<base64 png>", "width": 1080, "height": 2400}
```

### POST /api/camera
Snap a photo and have Gemma analyze it.

```bash
curl -X POST http://phone:8080/api/camera \
  -d '{"prompt": "Describe what the camera sees"}'
# Returns: {"response": "...", "image": "<base64>"}
```

### GET /api/notifications
Read active notifications.

```bash
curl http://phone:8080/api/notifications
# Returns: {"notifications": [{"package": "com.discord", "title": "...", "text": "...", "time": ...}]}
```

### GET /api/status
Health check.

```bash
curl http://phone:8080/api/status
# Returns: {"server": "swarm-bridge", "device": "Pixel 8", "battery_pct": 85, "model_ready": true, ...}
```

### POST /api/hive
Forward data to the hive-mind ledger.

```bash
curl -X POST http://phone:8080/api/hive \
  -d '{"agent": "phone", "type": "observation", "data": "User is at the office"}'
```

## Setup

### 1. Build the app

```bash
cd gallery/Android/src/
./gradlew installDebug
```

### 2. First launch
1. Open "Edge Gallery" on your phone
2. Download **Gemma 4 E2B** (or E4B) model
3. Wait for it to load

### 3. Enable services
1. **Settings > Accessibility > Edge Gallery** — enable the Swarm Bridge accessibility service
2. **Settings > Notifications > Notification access > Edge Gallery** — enable notification reading
3. Open Edge Gallery **Settings** dialog — toggle **Swarm Bridge** ON

### 4. Verify
```bash
curl http://<phone-ip>:8080/api/status
```

### 5. Remote access (Tailscale)
1. Install Tailscale on phone and WSL2
2. `tailscale up` on both
3. Access via `http://phone.ts.net:8080/api/status`

## Gemma Tools (On-Device Function Calling)

When Gemma processes a /api/chat request, it can invoke these tools:

| Tool | Description |
|---|---|
| `readNotifications` | Read active phone notifications |
| `openApp` | Launch an app by package name |
| `tapScreen` | Tap at x,y coordinates |
| `typeText` | Type into focused input field |
| `swipeScreen` | Swipe between two points |
| `pressBack` | Press Android back button |
| `readClipboard` | Read clipboard contents |
| `reportToHive` | Send findings to agent swarm hive-mind |

## Skills

Three built-in skills (loadable in Agent Skills mode):

- **swarm-bridge** — General phone control agent
- **swarm-scout** — Local research/analysis (free inference, no cloud costs)
- **swarm-eyes** — Visual assistant for construction/trades work
