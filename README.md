# Aura Timer ⏳✨

A beautiful, modern, premium Android timer application that runs in the background. Designed with visual excellence and user experience in mind.

## 🚀 Download Link

You can download the compiled APK directly here:
👉 **[Download Aura Timer APK](https://github.com/K1NGMR/TimerAPP/raw/main/apk/timer-app.apk)** 👈

---

## ✨ Features

- **Background Support**: The timer continues running even when the app is in the background, closed, or the screen is locked.
- **Interactive Notification**: Real-time status update in the notification drawer with built-in **Stop** and **Restart** action buttons.
- **Modern Premium Design**: Dark theme with custom gradients (Purple, Rose, and Orange), glowing progress indicator, and smooth material ripple animations.
- **Quick Presets**: Fast capsule-style buttons to launch popular durations (1 Min, 5 Min, 10 Min, 15 Min, 30 Min, 1 Hour) instantly.
- **Custom Duration Input**: Intuitive text pickers for custom Hours, Minutes, and Seconds.
- **Alarm Alert**: Ringtone alarm and waveform vibrations trigger immediately upon timer completion.

---

## 🛠️ Build & Architecture

- **Foreground Service (`TimerService`)**: Standard native Android service to manage the active countdown, notification channel updates, and alerts safely.
- **Broadcast Receiver (`TimerReceiver`)**: Handles events from the notification actions without needing to open the app.
- **Gradle & OpenJDK**: Built using Gradle 8.5 and Temurin OpenJDK 21.

---

## 👨‍💻 Author

Developed for [K1NGMR](https://github.com/K1NGMR) 🚀
