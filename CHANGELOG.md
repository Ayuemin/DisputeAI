# Changelog

## 1.1.0

- Native Jetpack Compose interface; WebView is no longer used by the application.
- Responsive swipe drawer with chat search, new chat, pin/rename/delete and settings.
- Elastic 2+ model discussions plus an independent result model.
- Cycle count and first responder moved to General settings.
- Pause/continue and stop are integrated directly into the composer; Stop replaces Send while a cycle is active.
- Persistent on-demand attachments shared by all participants without resending file contents on every request.
- Independent per-model prompt, optional temperature, timeout and capability-aware reasoning controls.
- Result model can evaluate the whole discussion or the last N cycles while always retaining user interventions.
- API keys encrypted with AES/GCM using Android Keystore; backups remain disabled and redirects/unsafe keyed HTTP are blocked.
- Settings reset moved to General settings with confirmation; chat history is preserved.

## 1.0.0

Initial DisputeAI build.
