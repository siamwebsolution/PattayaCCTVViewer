# Pattaya CCTV Viewer (Android)

แอป Android สำหรับเข้าถึงข้อมูล **CCTV Streaming เมืองพัทยา** ได้สะดวกขึ้น พร้อม Dashboard ข้อมูลเมืองและหน้ากิจกรรมเมืองพัทยา

## เวอร์ชันปัจจุบัน

- Version: **2.7.0**
- Version Code: **11**
- Package: `com.pattayacctv.viewer`
- minSdk: 24 (Android 7.0+)
- targetSdk: 35
- Source CCTV: https://livestream.pattaya.go.th/
- Events API: https://khunsri.com/public/api/pattaya_events.php

## ฟังก์ชันหลัก

### CCTV เมืองพัทยา
- ดูกล้องสดภายในแอป
- ค้นหากล้อง
- แผนที่จุดกล้อง
- บันทึกกล้องโปรด
- ดึงชื่อสถานที่ของกล้องจากหน้า CCTV ต้นฉบับ
- แสดงกล้องดูล่าสุด
- บันทึก Thumbnail ภาพล่าสุดของกล้องไว้แสดงใน Dashboard
- รองรับ Video Fullscreen
- Pull to Refresh
- เปิดหน้าปัจจุบันด้วย Browser ภายนอก

### Dashboard เมืองพัทยา
- สภาพอากาศพัทยา
- ราคาน้ำมัน
- ราคาทอง
- เมนูตรวจสอบการจราจร
- เมนูตรวจสอบพื้นที่/น้ำท่วมจากกล้อง
- Light / Dark / System Theme

### เบอร์สำคัญ
มีเมนู **เบอร์สำคัญเมืองพัทยา** และแตะเพื่อเปิดแอปโทรศัพท์ได้ทันที เช่น:
- Pattaya Contact Center 1337
- ศาลาว่าการเมืองพัทยา
- ศูนย์ข้อมูล CCTV
- 191
- 1669
- 199
- 1155
- 1784

### กิจกรรมเมืองพัทยา
ดึงข้อมูลจาก:
`https://khunsri.com/public/api/pattaya_events.php`

รองรับ:
- รูปปกกิจกรรม
- ชื่อและรายละเอียดกิจกรรม
- วันที่เริ่ม / สิ้นสุด
- สถานที่จัดงาน
- ปฏิทินรายเดือน
- กรองตามวันที่
- กรองตามหมวดหมู่
- ปุ่มดูรายละเอียด
- ปุ่มนำทาง Google Maps
- รองรับ latitude / longitude หรือชื่อสถานที่
- ปุ่มอัปเดตข้อมูลจาก API

ตัวอ่าน API รองรับชื่อ field ได้หลายรูปแบบ เช่น:
- `title`, `event_title`, `event_name`
- `category`, `category_name`
- `start_date`, `end_date`, `event_date`
- `location`, `venue`, `address`
- `image_url`, `cover_image`, `thumbnail`, `banner`
- `latitude`, `longitude`, `lat`, `lng`
- `map_url`, `google_maps_url`

## เปิดโปรเจกต์

1. เปิด Android Studio
2. เลือก **Open**
3. เลือกโฟลเดอร์ `PattayaCCTVViewer`
4. รอ Gradle Sync
5. ติดตั้ง Android SDK 35 หากยังไม่มี
6. Run บน Android Device หรือ Emulator

## Build APK

### Android Studio
ไปที่:

**Build > Build Bundle(s) / APK(s) > Build APK(s)**

ไฟล์จะอยู่ที่:

`app/build/outputs/apk/debug/app-debug.apk`

### GitHub Actions
Repository มี workflow:

`.github/workflows/build-apk.yml`

ทุกครั้งที่ Push เข้า `main` หรือ `master` ระบบจะ Build APK อัตโนมัติ

Artifact ปัจจุบัน:

`PattayaCCTV-v2.7.0-debug-apk`

หรือสามารถเปิดแท็บ **Actions > Build Pattaya CCTV APK > Run workflow** เพื่อสั่ง Build เองได้

## โครงสร้างสำคัญ

- `MainActivity.kt` — UI หลัก / CCTV / Dashboard / Favorites / Recents / Events
- `PattayaEventsRepository.kt` — เชื่อม Events API
- `LiveInfoRepository.kt` — ข้อมูลอากาศ น้ำมัน และทอง
- `ThemeHelper.kt` — Light / Dark / System Theme
- `activity_main.xml` — Layout หลัก
- `.github/workflows/build-apk.yml` — Build APK บน GitHub Actions

## หมายเหตุ

แอปเชื่อมต่อข้อมูล CCTV จากบริการสาธารณะของเมืองพัทยา และไม่ได้เป็นระบบกล้องต้นทางเอง

หากเผยแพร่ในลักษณะที่อาจทำให้ผู้ใช้เข้าใจว่าเป็นแอปทางการ ควรตรวจสอบสิทธิ์การใช้ตรา โลโก้ รูปบุคคล และข้อมูลของหน่วยงานที่เกี่ยวข้องให้เหมาะสม

ไม่ควรเพิ่มฟังก์ชันดาวน์โหลดหรือเผยแพร่ภาพ CCTV ต่อ หากไม่มีสิทธิ์หรือไม่ได้รับอนุญาตจากเจ้าของข้อมูล

## Languages

รองรับ 3 ภาษาในแอป:
- 🇹🇭 ไทย
- 🇬🇧 English
- 🇨🇳 中文（简体）

เปลี่ยนภาษาได้จาก **เพิ่มเติม > ภาษา / Language / 中文** และแอปจะจำภาษาที่เลือกไว้ในเครื่อง

หาก Events API มีฟิลด์ภาษา เช่น `title_en`, `title_zh`, `description_en`, `description_zh` แอปจะเลือกใช้ตามภาษาที่ตั้งไว้ และ fallback ไปข้อมูลหลักเมื่อไม่มีคำแปล

## Changelog

### v2.7.0
- เพิ่มภาษาไทย อังกฤษ และจีนตัวย่อ
- เพิ่มเมนูเลือกภาษาและบันทึกภาษาที่เลือก
- แปล Dashboard, CCTV, กิจกรรม, เบอร์สำคัญ และเมนูหลัก
- Events API รองรับฟิลด์แยกภาษาแบบ fallback อัตโนมัติ


### v2.6.0
- เพิ่มรูปปกกิจกรรม
- เพิ่มปฏิทินรายเดือน
- กรองกิจกรรมตามวันที่
- กรองกิจกรรมตามหมวดหมู่
- เพิ่ม Google Maps Navigation
- รองรับพิกัด latitude / longitude
- ปรับ Events API parser ให้ยืดหยุ่นขึ้น

### v2.5.0
- เพิ่มหน้ากิจกรรมเมืองพัทยา
- เชื่อม API `pattaya_events.php`

### v2.4.0
- เพิ่มชื่อสถานที่ของกล้องโปรด
- เพิ่มเบอร์สำคัญเมืองพัทยาและแตะเพื่อโทร

### v2.3.0
- เพิ่ม Thumbnail กล้องดูล่าสุด
- ปรับ Bottom Navigation ให้รองรับ Android System Navigation

### v2.2.x
- Dashboard สภาพอากาศ น้ำมัน ทอง
- เพิ่มเมนูตรวจสอบสถานการณ์

### v2.0.0
- Splash Screen
- Home ใหม่
- ดูกล้องสด / ค้นหา / กล้องโปรด
- Dark Mode
- Responsive Layout
