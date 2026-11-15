# ARTHER — Android Personal AI Assistant

ARTHER is an Android application with an Arabic/French/English interface, voice and text input, a conversational agent backend, and optional Android Accessibility actions.

## Build the Android APK
The repository builds a debug APK with GitHub Actions:
1. Open **Actions** in this repository.
2. Select **Build ARTHER Android APK**.
3. Run the workflow or push to `arther-chatgpt-work`.
4. Download the `ARTHER-APK` artifact from a successful run.

The artifact is a debug APK for testing. A production release requires a signing key and a release build.

## Backend configuration
Set these environment variables on the backend:
- `AI_BASE_URL`: OpenAI-compatible API base URL.
- `AI_API_KEY`: provider API key; never place it in the Android app.
- `AI_MODEL`: model supported by the provider.
- `DATABASE_URL`: optional PostgreSQL connection for conversation and long-term memory.
- `GITHUB_REPOSITORY`, `GITHUB_BRANCH`, `GITHUB_TOKEN`: optional repository read access for the agent.

## Current capabilities
- Voice recognition and text input.
- Selectable Arabic, French and English for speech recognition and spoken responses; automatic mode follows the device language.
- Android intents for apps, maps, settings, calls, SMS composition, timers and alarms.
- Optional Accessibility service for navigation, screen gestures, text entry and visible screen text.
- Flashlight, volume, brightness (subject to Android permissions), device information and current location.
- Server-side agent tools for workspace files, repository reading/search, and PostgreSQL memory.

## Important limitations
- Wake-word listening currently runs while ARTHER is open in the foreground; it is not an always-on background assistant.
- Android requires user approval for microphone, location, Accessibility and special settings permissions.
- Shizuku is currently detected and permission-requested; a privileged Shizuku command bridge is not yet implemented.
- Accessibility actions work only when the service is enabled and the target app exposes accessible UI elements.
- Android restricts some system toggles and background operations.
- Voice speaker identification (ignoring other speakers) is not implemented.
- The research endpoint does not execute arbitrary Python; secure isolated execution must be added before enabling it.
- The APK artifact is a debug build, not a signed Play Store release.

ARTHER reports an action as complete only when the available operation returns evidence of success. A successful build does not replace testing on the actual phone.
