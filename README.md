# FamFlow SMS Forwarder APK

An Android app that listens for Airtel Bank SMS notifications and forwards them to your FamFlow webhook.

## Features
- 🔔 Listens for all incoming SMS in real-time
- 🔍 Filters by sender ID (e.g., `AX-AIRTEL`, `BW-AIRTEL`, `AIRTEL`)
- 📡 Forwards SMS body + sender + timestamp as JSON to your webhook
- 🔄 Auto-restarts after phone reboot
- 🌙 Dark themed UI with live logs
- 🧪 Built-in test button to verify webhook connection

## Webhook Payload
When an Airtel SMS is received, the app POSTs this JSON to your configured URL:
```json
{
  "sender": "AX-AIRTEL",
  "body": "Your A/c XX1234 debited Rs.500.00 on 01-10-26. UPI Ref: 123456789.",
  "timestamp": 1727805182000,
  "source": "android_sms"
}
```

## Getting the APK

### Via GitHub Actions (Recommended)
1. Push this folder to a GitHub repo
2. Go to **Actions** tab → click the latest workflow run
3. Download **famflow-sms-forwarder-debug** artifact
4. Install APK on your Android phone

### Build locally
Requires Android Studio / JDK 17 + Android SDK:
```bash
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

## Setup on Android
1. Install the APK
2. Open app → grant **SMS** and **Notification** permissions
3. Set webhook URL: `https://famflow.cyou/api/sms/airtel`
4. Set filter senders: `AX-AIRTEL,BW-AIRTEL,AIRTEL,AIRINB`
5. Tap **Save**
6. Tap **Test** to verify connection
7. Done! 🎉

## Next Steps
- Build the `POST /api/sms/airtel` Next.js API route to parse and store the payment data
