# Antimax: Full-Stack WebRTC & Firebase Messenger

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen)](https://github.com/melvud/antimax)
[![Platform](https://img.shields.io/badge/platform-Android-green)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.x-blueviolet.svg)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-blue.svg)](https://developer.android.com/jetpack/compose)
[![Backend](https://img.shields.io/badge/Backend-Firebase-ffca28)](https://firebase.google.com/)
[![Comm](https://img.shields.io/badge/Comm-WebRTC-red)](https://webrtc.org/)

**Antimax** is a production-ready, high-performance video and audio calling application for Android. It demonstrates a complete, full-stack solution integrating a modern **Jetpack Compose** UI with a powerful **WebRTC** media engine and a scalable **Firebase** backend.

This repository serves as a professional portfolio piece, showcasing a deep understanding of real-time communication, modern Android development, and complex client-server architecture designed for reliability and scale.

---

## ✨ Core Features

This isn't just a simple demo; it's a robust, full-stack communication platform.

* **📞 1-on-1 Video & Audio Calls:** High-quality, low-latency real-time communication powered by the native Google WebRTC library.
* **🔥 Full-Stack Firebase Backend:** A robust, serverless backend using **Firebase Cloud Functions (TypeScript)**, **Firestore**, and **FCM** for signaling, user management, and real-time notifications.
* **🔔 Rich Push Notifications:** Uses FCM high-priority data messages to deliver reliable incoming call notifications, complete with "Accept" and "Decline" actions that work even when the app is in the background or killed.
* **🚀 Modern Android Stack:** Built 100% with **Kotlin**, **Jetpack Compose**, and **Coroutines**, targeting the latest Android SDK 34.
* **⚙️ Robust Call Management:** A centralized `WebRtcCallManager` singleton handles all aspects of the call lifecycle, including audio focus, speaker/mic control, camera switching, and media stream management.

---

## 🛠️ Technology Stack & Architecture

This project is built with a modern, scalable, and maintainable tech stack.

* **UI:** 100% **Jetpack Compose** with a **Material 3** design system.
* **Language:** 100% **Kotlin** (JVM Target 17).
* **Architecture:** Clean, single-activity MVVM (ViewModel) architecture.
* **Asynchronous:** **Kotlin Coroutines & Flows** for all async operations and UI state management.
* **Real-time Comms:** **Google WebRTC SDK for Android** (`io.github.webrtc-sdk:android`) for the core peer-to-peer connection.
* **Backend Logic:** **Firebase Cloud Functions (TypeScript)** for secure, server-side operations like initiating calls and syncing devices.
* **Database & Signaling:** **Firebase Firestore** for managing user data, device tokens, and as a signaling channel for WebRTC (passing Offers, Answers, and ICE Candidates).
* **Push Notifications:** **Firebase Cloud Messaging (FCM)** for delivering call invitations and commands.
* **Authentication:** **Firebase Authentication**.

---

## 🧠 How It Works: The Full-Stack Call Flow

The most complex feature is the robust, multi-device call flow. Here's how it works:

1.  **Caller (User A)** starts a call to User B.
2.  User A's app calls the `sendCallNotification` Firebase Cloud Function, passing `toUserId=B`.
3.  The Cloud Function queries Firestore for all device tokens listed under `users/B/devices/*`.
4.  The Function sends a high-priority FCM data multicast to all of User B's registered devices.
5.  **Recipient (User B)**'s devices (e.g., a phone and a tablet) receive the FCM push via `MyFirebaseMessagingService`.
6.  The service on both devices displays a high-priority incoming call notification with "Accept" and "Decline" actions.
7.  User B taps "Accept" on their **Phone (Device 1)**.
8.  The Phone's app opens the `CallScreen` and begins the WebRTC signaling process (creating an Offer/Answer).
9.  **CRITICAL STEP:** Simultaneously, the Phone (Device 1) calls the `hangupOtherDevices` Cloud Function, passing its own `acceptedToken`.
10. The Cloud Function queries Firestore for all of User B's devices *except* the `acceptedToken`.
11. The Function sends a new "hangup" FCM message to User B's **Tablet (Device 2)**, which then automatically dismisses the incoming call notification.
12. Back on Device 1, the WebRTC handshake completes (exchanging SDP and ICE candidates via Firestore/signaling server). The `PeerConnection` state changes to `CONNECTED`, and the call is live.

This architecture ensures a seamless and professional user experience, preventing "ghost ringing" on secondary devices.

---

## 🚀 Building the Project

This is a standard Android Studio project with a Firebase backend.

1.  **Clone the repository:**
    ```bash
    git clone [https://github.com/melvud/antimax.git](https://github.com/melvud/antimax.git)
    ```
2.  **Firebase Setup (Backend):**
    * Create a new project in the [Firebase Console](https://console.firebase.google.com/).
    * Install the Firebase CLI: `npm install -g firebase-tools`.
    * Navigate to the `functions/` directory: `cd functions`.
    * Install dependencies: `npm install`.
    * Connect the directory to your project: `firebase use --add [YOUR_PROJECT_ID]`.
    * Deploy the functions: `firebase deploy --only functions`.
    * In the console, **enable Firebase Authentication, Firestore, and Messaging**.

3.  **Firebase Setup (Android):**
    * In your Firebase project settings, add a new Android app.
    * Use the package name `com.example.messenger_app`.
    * Download the `google-services.json` file and place it in the `app/` directory.

4.  **STUN/TURN Server Configuration:**
    * This project requires a STUN/TURN server to enable calls between users on different networks (NAT traversal).
    * The demo credentials are in `app/src/main/java/com/example/messenger_app/webrtc/WebRtcCallManager.kt`.
    * **You MUST replace these with your own STUN/TURN server credentials.**
    ```kotlin
    // app/src/main/java/com/example/messenger_app/webrtc/WebRtcCallManager.kt

    val iceServers = listOf(
        IceServer.builder("stun:YOUR_STUN_HOST.com")
            .createIceServer(),
        IceServer.builder("turn:YOUR_TURN_HOST.com?transport=udp")
            .setUsername("YOUR_USERNAME").setPassword("YOUR_PASSWORD").createIceServer(),
        IceServer.builder("turns:YOUR_TURN_HOST.com:443?transport=tcp")
            .setUsername("YOUR_USERNAME").setPassword("YOUR_PASSWORD").createIceServer()
    )
    ```

5.  **Open in Android Studio:**
    * Open the project in the latest stable version of Android Studio.
    * Let Gradle sync all dependencies.
    * Build and run the project on two separate devices to test the call functionality.

---

## 👨‍💼 Looking for a Developer?

Hi! I'm the developer behind this project. I specialize in building high-quality, performant, and beautiful native Android applications with complex backend integrations.

If you're impressed by the architecture and quality of this app, I'm confident I can bring the same level of expertise to your project.

* **Email:** `ivsilan2005@gmail.com`

Let's build something great together.
