# Build APK - Pattaya CCTV v2.7.0

## ข้อมูลเวอร์ชัน

- Version Name: **2.6.0**
- Version Code: **11**
- Package: `com.pattayacctv.viewer`
- minSdk: 24
- targetSdk: 35

## Android Studio

1. เปิดโฟลเดอร์ `PattayaCCTVViewer` ด้วย Android Studio
2. รอ Gradle Sync ให้เสร็จ
3. ไปที่ **Build > Build Bundle(s) / APK(s) > Build APK(s)**
4. ไฟล์ติดตั้งจะอยู่ที่:

`app/build/outputs/apk/debug/app-debug.apk`

## GitHub Actions

โปรเจกต์มี:

`.github/workflows/build-apk.yml`

Workflow จะทำงานอัตโนมัติเมื่อ Push เข้า:
- `main`
- `master`

หรือสั่ง Build เองได้จาก:

**Actions > Build Pattaya CCTV APK > Run workflow**

Artifact ปัจจุบัน:

`PattayaCCTV-v2.7.0-debug-apk`

ภายใน ZIP จะมี:

`app-debug.apk`

## หมายเหตุเรื่องการติดตั้ง

Debug APK ถูกเซ็นด้วย Debug Key ของระบบ Build

หาก Android แจ้ง:
- Install unknown apps
- Google Play Protect
- App not installed

ให้ตรวจสอบแหล่งดาวน์โหลดและสิทธิ์ติดตั้ง APK ให้ถูกต้อง

หากต้องการเผยแพร่ให้ผู้ใช้ทั่วไปในระยะยาว แนะนำทำ **Release APK/AAB ที่เซ็นด้วย Signing Key ถาวร** เพื่อให้อัปเดตเวอร์ชันใหม่ทับเวอร์ชันเดิมได้สม่ำเสมอ
