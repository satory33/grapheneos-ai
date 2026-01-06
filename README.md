# AI Assistant for Android

A privacy-focused AI assistant I built as an alternative to Google Gemini while using GrapheneOS. Works on any Android device, but tested primarily on Android 16 (Pixel 9 Pro).

> **Why this exists**: I wanted the convenience of an always-available AI assistant (like Gemini) without sacrificing privacy on GrapheneOS. So I built my own.

## ✨ What It Does

- **Quick activation**: Press Volume Up + Volume Down simultaneously to trigger the assistant
- **Voice & text input**: Talk to it or type - your choice
- **Works with images**: Send photos and ask questions about them
- **Web search**: Gets current information via Brave Search API
- **Privacy-first**: Your data stays on your device or goes only to services you control

## 🔧 How It Works

### AI Backends (pick one):
- **OpenRouter** - Access to 100+ AI models (Claude, GPT-4, Llama, Mistral, etc.)
- **GitHub Copilot** - If you already have a Copilot subscription

### Voice Recognition (pick one):
- **Android built-in** - Uses your system's speech recognition
- **Vosk (offline)** - Completely on-device, no internet needed
- **Groq (cloud)** - Fast and accurate cloud-based transcription

### Web Search:
- **Brave Search API** - More private than Google, gets real-time info

## 🚀 Quick Start

1. **Download** the APK from [Releases](releases)
2. **Install** on your device
3. **Get API keys**:
   - [OpenRouter](https://openrouter.ai) or GitHub Copilot
   - [Brave Search](https://brave.com/search/api/) (optional)
4. **Configure** in the app
5. **Set as default assistant**:
```
   Settings → Apps → Default apps → Digital assistant app → AI Assistant
```
6. **Enable accessibility** (for volume button activation):
```
   Settings → Accessibility → AI Assistant → Enable
```

## 📱 Tested On

- **Android 16** (Pixel 9 Pro with GrapheneOS)
- Should work on any Android 12+ device

## 🔐 Privacy Features

- **On-device speech recognition** (Vosk mode)
- **Encrypted API key storage** (Android Keystore)
- **No tracking or analytics**
- **Minimal permissions** (only mic, internet, foreground service)
- **You control what data goes where**

## 💡 Why Not Just Use Gemini?

Good question! Here's my reasoning:

- **Privacy**: Gemini is deeply integrated into Android and sends lots of data to Google
- **Control**: With this, you choose which AI backend and what data to share
- **Open source**: You can see exactly what it does
- **GrapheneOS compatible**: Works perfectly without Google Play Services

## 🛠️ Building from Source
```bash
# Clone the repo
git clone https://github.com/yourusername/ai-assistant-android.git
cd ai-assistant-android

# Build
./gradlew assembleDebug

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 🤝 Contributing

This started as a personal project to solve my own problem, but I'd love to see it improve! Feel free to:

- Report bugs
- Suggest features
- Submit pull requests
- Share your experience

## 📄 License

MIT

## 🙏 Acknowledgments

- **GrapheneOS** - For making privacy-focused Android possible
- **OpenRouter** - For unified access to multiple AI models
- **Brave Search** - For privacy-respecting search API
- **Whisper.cpp** / **Vosk** - For on-device speech recognition

---

**Note**: This project is not affiliated with GrapheneOS, Google, or any AI provider. It's an independent tool built by someone who cares about privacy and wanted a better solution.