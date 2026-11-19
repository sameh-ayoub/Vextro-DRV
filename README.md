# Vextro DRV

Discord voice routing for Android. It carries only Discord's traffic and adapts the first UDP packet of each voice stream instead of running a full-device VPN.

**DEV BY Sameh**

---

## English

### What it does

Vextro DRV opens an Android `VpnService` tunnel that includes a single package, `com.discord`. Every other app keeps the normal connection, so speeds, data usage and battery stay untouched.

Inside the tunnel:

- **UDP (voice)** — the first packet of a stream is followed by the packaged 1200-byte block, two marker bytes and a short pause before the original packet goes out. Later packets pass through unchanged, and anything that arrives during the pause is queued and sent in order.
- **TCP** — a local relay terminates each session and reconnects through a protected socket, so the gateway, chat and login keep working.
- **Return path** — responses are rebuilt with fresh IPv4/UDP headers and checksums and written back into the tunnel.

### Features

- Per-app tunnel: Discord only.
- Arabic and English, switchable from the header flags.
- Adjustable delay, strict 74-byte mode, optional decoy packet.
- Live counters and an in-app log.

### Requirements

Android 7.0 (API 24) or newer, and Discord installed from any source under the package name `com.discord`.

### Build

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

or with a standalone Gradle 8.7:

```bash
gradle assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/`.

`compileSdk 34`, `targetSdk 33`, `minSdk 24`. Java only, no third-party dependencies.

### Install

```bash
adb install -r Vextro-DRV-v1.1.apk
```

### Usage

1. Open the app and press **Start**.
2. Accept the VPN request. Only Discord is routed.
3. Join a voice channel. The counters should move within a few seconds.
4. Press **Stop** to close the tunnel.

Settings take effect after a restart of the tunnel.

### Known limits

- Android allows one VPN at a time; starting Vextro DRV disconnects any other VPN.
- IPv6 packets are skipped. Networks that carry voice over IPv6 only will not benefit.
- On Xiaomi/Redmi devices enable Autostart and set the battery saver to "No restrictions", otherwise the system may stop the service.

### License

MIT, see [LICENSE](LICENSE).

---

## العربية

### ما يفعله

يفتح Vextro DRV نفق `VpnService` يُدخل حزمة واحدة فقط هي `com.discord`. بقية تطبيقات الجهاز تبقى على اتصالها الطبيعي، فلا تتأثر السرعة ولا استهلاك البيانات ولا البطارية.

داخل النفق:

- **UDP (الصوت)** — الحزمة الأولى في كل مسار يتبعها الملف المجهّز بحجم 1200 بايت، ثم بايتان للعلامة، ثم وقفة قصيرة قبل خروج الحزمة الأصلية. بقية الحزم تمر كما هي، وما يصل أثناء الوقفة يُحفظ ويُرسل بالترتيب.
- **TCP** — وكيل محلي يُنهي كل جلسة ويعيد الاتصال عبر سوكت محمي، فتبقى البوابة والشات وتسجيل الدخول تعمل.
- **مسار العودة** — تُعاد بناء الردود برؤوس IPv4/UDP ومجاميع تحقق جديدة وتُكتب داخل النفق.

### المزايا

- نفق لتطبيق واحد: ديسكورد فقط.
- عربي وإنجليزي، يُبدَّل من العلمين في الأعلى.
- تأخير قابل للضبط، ووضع دقيق لحجم 74 بايت، وحزمة تمويه اختيارية.
- عدّادات حية وسجل داخل التطبيق.

### المتطلبات

أندرويد 7.0 (API 24) أو أحدث، وديسكورد مثبّت باسم الحزمة `com.discord`.

### البناء

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

الملف الناتج في `app/build/outputs/apk/debug/`.

### التثبيت

```bash
adb install -r Vextro-DRV-v1.1.apk
```

### الاستخدام

1. افتح التطبيق واضغط **تشغيل**.
2. اقبل طلب VPN. ديسكورد وحده يمر عبر النفق.
3. ادخل قناة صوتية وستتحرك العدّادات خلال ثوانٍ.
4. اضغط **إيقاف** لإغلاق النفق.

تُطبَّق الإعدادات بعد إعادة تشغيل النفق.

### حدود معروفة

- أندرويد يسمح بـ VPN واحد فقط، فتشغيل التطبيق يقطع أي VPN آخر.
- حزم IPv6 تُهمل، والشبكات التي تنقل الصوت عبر IPv6 فقط لن تستفيد.
- على أجهزة Xiaomi/Redmi فعّل «بدء تلقائي» واضبط البطارية على «بدون قيود».

### الحقوق

MIT، انظر [LICENSE](LICENSE).
