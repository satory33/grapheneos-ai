# Changelog

All notable changes to this project will be documented in this file.

## [1.1] - 2026-01-08

### 🎉 Major Features

#### Automatic GitHub Copilot Token Refresh
- **Solved the short session problem!** Copilot tokens now refresh automatically
- GitHub OAuth tokens are securely stored and used to maintain active sessions
- Proactive token refresh: checks 5 minutes before expiry and refreshes if needed
- Automatic retry on 401 errors with fresh tokens
- No more manual re-authentication - authenticate once, session stays alive

### ✨ New Features

- **Token Management System**
  - Store GitHub Access Token securely (encrypted at rest)
  - Track Copilot token expiry timestamp
  - Automatic token refresh using stored GitHub credentials
  - Mutex-based locking to prevent concurrent refresh attempts

- **Enhanced Error Handling**
  - Automatic retry with token refresh on 401 errors
  - Better error messages distinguishing between expired tokens vs access denied
  - Seamless token refresh between API calls

- **JWT Token Parsing**
  - Parse and extract expiry time from Copilot JWT tokens
  - Proactive refresh before token expires (5-minute buffer)

### 🔧 Technical Changes

#### Core Changes
- **CopilotClient.kt**
  - Added `getValidToken()` method - intelligently manages token validity
  - Added `handleUnauthorized()` method - recovers from 401 errors
  - All streaming methods now support automatic token refresh
  - Updated error messages for better UX

- **GitHubCopilotAuth.kt**
  - Added `parseTokenExpiry()` static method - extracts exp claim from JWT
  - Added `refreshCopilotToken()` method - exchanges GitHub token for Copilot token
  - Changed `getCopilotToken()` to return expiry timestamp as well
  - New `FullAuthResult` data class containing both tokens and expiry time
  - Updated `authenticate()` to return `FullAuthResult`

- **SecureKeyManager.kt**
  - Added `PREF_GITHUB_ACCESS_TOKEN` for storing long-lived GitHub token
  - Added `PREF_COPILOT_TOKEN_EXPIRY` for tracking token expiration
  - New methods: `setGitHubAccessToken()`, `getGitHubAccessToken()`, `hasGitHubAccessToken()`
  - New methods: `setCopilotTokenExpiry()`, `getCopilotTokenExpiry()`
  - New method: `isCopilotTokenExpired()` - checks if token expires in < 5 minutes
  - Enhanced `clearGitHubAccessToken()` to clear all auth-related data

- **SettingsScreen.kt**
  - Updated `GitHubOAuthDialog` signature to pass `FullAuthResult`
  - Now stores both GitHub Access Token and Copilot token expiry
  - Improved token lifecycle management

#### Dependency Updates
- Added `kotlinx.coroutines.sync.Mutex` for thread-safe token refresh
- Added `android.util.Base64` for JWT parsing

### 📝 Documentation

- Updated README.md with token refresh feature overview
- Added clear explanation of how automatic refresh works
- Removed outdated "known issues" warning about short sessions

### 🐛 Bug Fixes

- Fixed: Sessions expiring prematurely due to lack of token refresh
- Fixed: 401 errors forcing user re-authentication instead of retrying
- Fixed: No way to maintain session beyond ~30 minutes with Copilot

### 🔒 Security

- GitHub Access Tokens encrypted using Android Keystore (same as API keys)
- Tokens stored securely in SharedPreferences
- Mutex prevents race conditions during concurrent token refresh
- Clear separation between short-lived (Copilot) and long-lived (GitHub) tokens

### ⚡ Performance

- Proactive refresh prevents mid-request token expiry
- Single token refresh per lifecycle (reused across multiple requests)
- Minimal overhead - only refreshes when necessary (5-min buffer)

### 📊 Metrics

- Session duration: Extended from ~30 minutes to **indefinite** (until GitHub token revoked)
- API reliability: Improved with automatic 401 retry mechanism
- User experience: Eliminated need for manual re-authentication

---

## [1.0] - 2025-12-20

### 🎉 Initial Release

- ✨ Privacy-focused AI assistant for Android
- 🎤 Voice and text input support
- 📸 Image analysis with vision models
- 🔍 Web search via Brave Search API
- 🎚️ Hardware button activation (Volume Up + Down)
- 🌐 Multiple AI backend support (OpenRouter, GitHub Copilot)
- 🎵 Multiple speech recognition options (Android built-in, Vosk, Groq)
- ♿ Accessibility Service integration