# Antimax: Full-Stack WebRTC & Firebase Messenger

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen)](https://github.com/melvud/antimax)
[![Platform](https://img.shields.io/badge/platform-Android-green)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.x-blueviolet.svg)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-blue.svg)](https://developer.android.com/jetpack/compose)
[![Backend](https://img.shields.io/badge/Backend-Firebase-ffca28)](https://firebase.google.com/)
[![Comm](https://img.shields.io/badge/Comm-WebRTC-red)](https://webrtc.org/)

**Antimax** is a production-ready, high-performance video, audio, and chat application for Android. It demonstrates a complete, full-stack solution integrating a modern **Jetpack Compose** UI with a powerful **WebRTC** media engine and a scalable **Firebase** backend.

---

## ✨ Core Features

This isn't just a simple demo; it's a robust, full-stack communication platform.

* **📞 1-on-1 Video & Audio Calls:** High-quality, low-latency real-time communication powered by the native Google WebRTC library.
* **💬 Full-Stack Real-time Chat:**
    * 1-on-1 text messaging with replies, editing, and deleting.
    * Media sharing (images, videos, files) via **Firebase Storage**.
    * Voice messages and stickers.
    * Real-time typing indicators.
    * Message status (Sent, Delivered, Read) via backend logic.
* **🔥 Full-Stack Firebase Backend:** A robust, serverless backend using **Firebase Cloud Functions (TypeScript)**, **Firestore**, **FCM**, and **Storage** for signaling, chat, user management, and real-time notifications.
* **🔔 Rich Push Notifications:** Uses FCM high-priority data messages to deliver reliable incoming call and new message notifications, complete with "Accept" and "Decline" actions that work even when the app is in the background or killed.
* **🚀 Modern Android Stack:** Built 100% with **Kotlin**, **Jetpack Compose**, and **Coroutines**, targeting the latest **Android SDK 34** with **Java 17**.
* **⚙️ Robust Call Management:**
    * Centralized `WebRtcCallManager` handles the entire call lifecycle.
    * **Video Upgrade:** Users in an audio call can seamlessly request to upgrade to a video call.
    * **Call Timeout:** Unanswered calls are automatically terminated by a cloud function after 30 seconds.
    * **Self-Call Prevention:** Backend-level check prevents users from initiating calls to themselves.
* **✅ In-App Updates:** A custom `AppUpdateManager` checks a remote JSON file for new versions and prompts the user to download and install the latest APK.

---

## 🛠️ Technology Stack & Architecture

This project is built with a modern, scalable, and maintainable tech stack.

* **UI:** 100% **Jetpack Compose** (BOM `2024.08.00`) with a **Material 3** design system.
* **Language:** 100% **Kotlin** (`2.0.21`, JVM Target 17).
* **Architecture:** Clean, single-activity MVVM (ViewModel) architecture.
* **Asynchronous:** **Kotlin Coroutines & Flows** (`1.8.1`) for all async operations and UI state management.
* **Real-time Comms:** **Google WebRTC SDK for Android** (`137.7151.04`) for the core peer-to-peer connection.
* **Backend Logic:** **Firebase Cloud Functions (TypeScript)** for secure, server-side operations (sending notifications, managing call state, handling chat logic).
* **Database & Signaling:** **Firebase Firestore** for managing user data, chat messages, device tokens, and as a signaling channel for WebRTC (passing Offers, Answers, and ICE Candidates).
* **File Storage:** **Firebase Storage** for all chat media (images, videos, files, voice messages).
* **Push Notifications:** **Firebase Cloud Messaging (FCM)** for delivering call invitations, chat messages, and call commands.
* **Authentication:** **Firebase Authentication**.

---

## 👨‍💼 Looking for a Developer?

Hi! I'm the developer behind this project. I specialize in building high-quality, performant, and beautiful native Android applications with complex backend integrations.

If you're impressed by the architecture and quality of this app, I'm confident I can bring the same level of expertise to your project.

* **Email:** `ivsilan2005@gmail.com`

Let's build something great together.
