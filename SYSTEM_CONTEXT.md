SYSTEM CONTEXT & ARCHITECTURE RULES:

1. **Project Goal:** "Sovereign Family Messenger" — Serverless, P2P File Transfer.
2. **Infrastructure:**
   - **Database:** Firebase Firestore (Signaling only).
   - **File Transfer:** **Direct WebRTC Data Channel (P2P)**.
   - **Push Notifications:** Direct FCM via `service-account.json`.
3. **Transfer Strategy ("The Relay"):**
   - **Sender:** Starts a FOREGROUND SERVICE that holds the file and waits for a connection. Screen can be off.
   - **Receiver:** Connects via WebRTC, requests the file, and downloads it.
   - **Storage:** RAM/Stream only. No cloud storage used.
4. **Security:** AES Encryption on the Data Channel (WebRTC handles this by default via DTLS).
5. **Language:** Russian (Русский).