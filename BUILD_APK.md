# Build APK - Pattaya CCTV v2.0.0

## Android Studio
1. เปิดโฟลเดอร์ `PattayaCCTVViewer` ด้วย Android Studio
2. รอ Gradle Sync ให้เสร็จ
3. ไปที่ **Build > Build Bundle(s) / APK(s) > Build APK(s)**
4. ไฟล์ติดตั้งอยู่ที่ `app/build/outputs/apk/debug/app-debug.apk`

## GitHub Actions
โปรเจกต์มี `.github/workflows/build-apk.yml` ให้แล้ว
1. อัปโหลดโปรเจกต์ขึ้น GitHub
2. เปิดแท็บ **Actions** > **Build Pattaya CCTV APK** > **Run workflow**
3. ดาวน์โหลด Artifact ชื่อ `PattayaCCTV-v2.0.0-debug-apk`

Debug APK จะถูกเซ็นด้วย debug key ของระบบ build และติดตั้งบน Android ได้โดยเปิดอนุญาต Install unknown apps สำหรับแอปที่ใช้เปิดไฟล์ APK
