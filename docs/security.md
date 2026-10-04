# Security notes

- API keys are encrypted locally using Android Keystore-backed AES/GCM.
- Backups are disabled for app-private data.
- HTTPS is required globally; cleartext traffic is permitted only for loopback development endpoints by Android network security policy.
- API redirects are not followed automatically.
- API keys are never stored in chat history.
