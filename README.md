# POC Read Receipt AI (Spring Boot + Vertex AI Gemini)

โปรเจกต์นี้เป็น Spring Boot Command-Line Application สำหรับอ่านรูปใบเสร็จ (Receipt Image) แล้วดึงข้อมูลออกมาเป็นโครงสร้าง JSON ด้วย Google Cloud Vertex AI (Gemini)

รองรับ:
- อ่านไฟล์รูปใบเสร็จจากเครื่อง (`.jpg`, `.jpeg`, `.png`, `.webp`)
- ส่งรูป + prompt ไปยัง Gemini ผ่าน Vertex AI SDK
- แปลงผลลัพธ์ JSON เป็น Java object
- แสดงผลใน Console
- Export ผลลัพธ์เป็นไฟล์ `.txt` อัตโนมัติ โดยชื่อไฟล์รูปแบบ:
  - `<store_name>+datetime+<recieptNo>.txt`

---

## 1) Tech Stack

- Java 17
- Spring Boot 3.3.4
- Maven
- Google Cloud Vertex AI SDK (`google-cloud-vertexai`)
- Gson

---

## 2) โครงสร้างข้อมูลที่ดึงจากใบเสร็จ

แอปคาดหวังผล JSON ในรูปแบบนี้:

```json
{
  "storeName": "String",
  "receiptNumber": "String",
  "date": "String",
  "totalAmount": 0.0,
  "lineItems": [
    {
      "productName": "String",
      "productCode": "String",
      "quantity": 0,
      "productPrice": 0.0
    }
  ]
}
```

---

## 3) การตั้งค่า Credential (สำคัญ)

แอปใช้ Service Account JSON ผ่าน argument `--credentialsPath` และตั้งค่า `GOOGLE_APPLICATION_CREDENTIALS` ก่อนเรียก Vertex AI

ตัวอย่างไฟล์ key (ห้าม commit ขึ้น public):
- `my-receipt-api-xxxx.json`

ไฟล์ `.gitignore` ถูกตั้งค่าเพื่อกันไฟล์เหล่านี้แล้ว เช่น:
- `my-receipt-api-*.json`
- `*credentials*.json`
- `env.txt`
- ไฟล์ผลลัพธ์ `*+*+*.txt`

---

## 4) Maven Dependencies

ดูใน [pom.xml](pom.xml)

หลัก ๆ ที่ใช้:

```xml
<dependency>
    <groupId>com.google.cloud</groupId>
    <artifactId>google-cloud-vertexai</artifactId>
    <version>1.15.0</version>
</dependency>

<dependency>
    <groupId>com.google.code.gson</groupId>
    <artifactId>gson</artifactId>
    <version>2.11.0</version>
</dependency>
```

Encoding ที่ตั้งไว้ใน Maven:

```xml
<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
<project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
```

---

## 5) Model / Region ที่ใช้งานในโค้ด

ในโค้ดปัจจุบัน ([src/main/java/com/example/receipt/ReceiptExtractorApplication.java](src/main/java/com/example/receipt/ReceiptExtractorApplication.java)):

- Default location: `us-central1`
- Model: `gemini-2.5-flash`

สามารถแก้ model ได้ที่บรรทัดสร้าง `GenerativeModel`:

```java
GenerativeModel model = new GenerativeModel("gemini-2.5-flash", vertexAI);
```

> ถ้าโปรเจกต์ยังไม่สามารถใช้โมเดลนี้ได้ จะเจอ `NOT_FOUND` หรือ `PERMISSION_DENIED`

---

## 6) วิธี Build และ Run

### 6.1 Compile

```powershell
mvn -q -DskipTests compile
```

### 6.2 Run (ปกติ)

```powershell
$env:GOOGLE_APPLICATION_CREDENTIALS = (Resolve-Path .\my-receipt-api-65cf36b6482b.json).Path
mvn -q spring-boot:run "-Dspring-boot.run.arguments=--projectId=my-receipt-api --imagePath=test.jpg --credentialsPath=my-receipt-api-65cf36b6482b.json"
```

Arguments ที่ต้องมี:
- `--projectId`
- `--imagePath`
- `--credentialsPath`

Optional:
- `--location` (ถ้าไม่ส่ง จะใช้ค่า default ในโค้ด)

---

## 7) ให้ Console แสดงภาษาไทย (Windows)

ใช้คำสั่งนี้ก่อนรัน:

```powershell
chcp 65001
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
$OutputEncoding = [System.Text.UTF8Encoding]::new()
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8'
```

จากนั้นค่อยรัน `mvn spring-boot:run ...`

---

## 8) รูปแบบไฟล์ Export ผลลัพธ์

หลังจาก parse สำเร็จ ระบบจะสร้างไฟล์ `.txt` อัตโนมัติที่ root โปรเจกต์:

```text
<store_name>+datetime+<recieptNo>.txt
```

ตัวอย่าง:

```text
7-Eleven+20260315110751+R#0000872886.txt
MBC SAWAENGHA+20260315111006+103102135.txt
```

รายละเอียด:
- `store_name` มาจาก `storeName`
- `datetime` ใช้เวลาตอนรัน รูปแบบ `yyyyMMddHHmmss`
- `recieptNo` ใช้ค่าจาก `receiptNumber`
- ระบบ sanitize ตัวอักษรต้องห้ามของชื่อไฟล์อัตโนมัติ (`\ / : * ? " < > |`)

---

## 9) ตัวอย่างผลลัพธ์ใน Console

แอปจะแสดง:
- Store name
- Receipt number
- Date
- Total amount
- Line items (แยกรายการสินค้า)
- Parsed JSON แบบ pretty

---

## 10) Troubleshooting

### 10.1 `NOT_FOUND: Publisher Model ... was not found`
สาเหตุ:
- โมเดลไม่พร้อมใช้งานในโปรเจกต์/region
- project ยังไม่ได้รับสิทธิ์ใช้งาน model เวอร์ชันนั้น

แนวทางแก้:
- ตรวจว่าเปิด Vertex AI API แล้ว
- ตรวจว่า Service Account มีสิทธิ์อย่างน้อย `Vertex AI User` (`roles/aiplatform.user`)
- เปลี่ยน model หรือ region ให้ตรงกับที่โปรเจกต์เข้าถึงได้

### 10.2 `PERMISSION_DENIED: aiplatform.endpoints.predict`
สาเหตุ:
- IAM ไม่พอ

แนวทางแก้:
- เพิ่มสิทธิ์ `roles/aiplatform.user` ให้ service account
- ตรวจ IAM policy / org policy เพิ่มเติม

### 10.3 ภาษาไทยขึ้น `????`
แนวทางแก้:
- ใช้ UTF-8 settings ตามหัวข้อ 7
- ใช้ terminal/font ที่รองรับ UTF-8

---

## 11) Security Best Practices

- ห้าม commit service account key ขึ้น public repo
- ควร rotate key ทันทีถ้าเคยแชร์/หลุด
- พิจารณาใช้ Workload Identity หรือ Secret Manager ใน production

---

## 12) ไฟล์สำคัญในโปรเจกต์

- แอปหลัก: [src/main/java/com/example/receipt/ReceiptExtractorApplication.java](src/main/java/com/example/receipt/ReceiptExtractorApplication.java)
- Maven config: [pom.xml](pom.xml)
- เอกสารนี้: [README.md](README.md)

---

## 13) อธิบายโค้ดแบบละเอียด (Code Walkthrough)

ไฟล์หลักของแอปอยู่ที่ [src/main/java/com/example/receipt/ReceiptExtractorApplication.java](src/main/java/com/example/receipt/ReceiptExtractorApplication.java)

### 13.1 โครงสร้างคลาส

- `ReceiptExtractorApplication` implements `CommandLineRunner`
  - ทำให้แอปรันงานทันทีหลัง Spring Boot start
- `CliArgs` (record)
  - เก็บ argument ที่ parse แล้ว (`projectId`, `location`, `imagePath`, `credentialsPath`)
- `ReceiptData`
  - mapping JSON หลักจาก model (`storeName`, `receiptNumber`, `date`, `totalAmount`, `lineItems`)
- `LineItem`
  - mapping รายการสินค้า (`productName`, `productCode`, `quantity`, `productPrice`)

### 13.2 ลำดับการทำงานใน `run(...)`

1. Parse arguments ด้วย `parseArgs(args)`
2. ตรวจไฟล์ credential ว่ามีอยู่จริง
3. โหลด credential จากไฟล์และตั้งค่า `GOOGLE_APPLICATION_CREDENTIALS`
4. ตรวจไฟล์รูปใบเสร็จว่ามีอยู่จริง
5. อ่าน bytes ของรูป และหา MIME type (`image/jpeg`, `image/png`, ...)
6. สร้าง `Part imagePart` แบบ `inlineData`
7. สร้าง prompt แบบ strict ให้โมเดลตอบเป็น raw JSON เท่านั้น
8. สร้าง `Content` โดยส่งทั้ง text prompt + image part
9. เปิด `VertexAI(projectId, location)` แล้วสร้าง `GenerativeModel`
10. เรียก `generateContent(content)`
11. ดึง text response ด้วย `ResponseHandler.getText(response)`
12. แปลง JSON -> `ReceiptData` ด้วย Gson
13. แสดงผล console ด้วย `printReceiptData(...)`
14. Export ลงไฟล์ `.txt` ด้วย `exportResultAsTextFile(...)`

### 13.3 จุดสำคัญในแต่ละเมธอด

- `parseArgs(String[] args)`
  - อ่านค่าจากรูปแบบ `--key=value`
  - บังคับ argument สำคัญไม่ให้ว่าง
  - กำหนด location default เป็น `us-central1` ถ้าไม่ส่งมา

- `resolveMimeType(Path imagePath)`
  - พยายามหา MIME type จากระบบก่อน (`Files.probeContentType`)
  - fallback ด้วยนามสกุลไฟล์ (`.jpg`, `.jpeg`, `.png`, `.webp`)
  - ถ้าไม่รองรับจะ throw error

- `printReceiptData(ReceiptData receiptData, String rawJson)`
  - แสดงค่า header ของใบเสร็จ
  - loop รายการสินค้าแบบอ่านง่าย
  - แสดง JSON pretty เพื่อตรวจสอบความถูกต้อง

- `exportResultAsTextFile(...)`
  - สร้างชื่อไฟล์ตาม format `<store_name>+datetime+<recieptNo>.txt`
  - ใช้เวลา format `yyyyMMddHHmmss`
  - เขียนทั้ง summary และ JSON ลงไฟล์
  - แจ้ง path ไฟล์ที่ export สำเร็จทาง console

- `sanitizeFileNamePart(String value)`
  - ป้องกันชื่อไฟล์ invalid บน Windows (`\\ / : * ? " < > |`)
  - แทนที่ด้วย `_` และกันค่าว่างด้วย `UNKNOWN`

### 13.4 Prompt Engineering ที่ใช้ในโปรเจกต์นี้

ใน prompt มีข้อกำหนดสำคัญเพื่อให้ parse ได้เสถียร:

- ให้ตอบเป็น "raw JSON เท่านั้น"
- ห้าม markdown / triple backticks
- กำหนด schema ชัดเจนทั้ง field name และชนิดข้อมูล
- ระบุ fallback behavior:
  - scalar ที่ไม่พบให้เป็น `null`
  - `lineItems` ถ้าไม่พบให้เป็น `[]`

แนวคิดนี้ช่วยลดความเสี่ยงกรณี model ตอบแบบ narrative แล้ว parse JSON ไม่ได้

### 13.5 Error Handling

- จับ `ApiException`
- ถ้าเป็น `NOT_FOUND` (model not found/access denied by availability)
  - พิมพ์คำอธิบาย model/project/location เพื่อ debug ได้เร็ว
- ข้อผิดพลาดอื่น ๆ จะ throw ต่อเพื่อให้เห็น root cause

### 13.6 การรองรับภาษาไทย

- ฝั่ง build/report ตั้ง UTF-8 ใน Maven แล้ว
- ฝั่ง runtime ให้ตั้ง terminal และ JVM เป็น UTF-8 (ดูหัวข้อ 7)
- เมื่อทั้งสองส่วนถูกต้อง ไทยใน console และไฟล์ export จะอ่านได้ปกติ

---

## 14) Quick Start (สั้นที่สุด)

```powershell
mvn -q -DskipTests compile

chcp 65001
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
$OutputEncoding = [System.Text.UTF8Encoding]::new()
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8'
$env:GOOGLE_APPLICATION_CREDENTIALS = (Resolve-Path .\my-receipt-api-65cf36b6482b.json).Path

mvn -q spring-boot:run "-Dspring-boot.run.arguments=--projectId=my-receipt-api --imagePath=test2.jpg --credentialsPath=my-receipt-api-65cf36b6482b.json"
```
