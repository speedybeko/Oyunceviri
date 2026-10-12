# Oyun Çeviri (MVP)

İki uygulama:

- **app** (telefon): Xbox uygulamasının uzaktan oynatma yayınını ekran yakalamayla alır, alt bölgedeki
  İngilizce altyazıyı OCR ile okur (ML Kit), Türkçeye çevirir (ML Kit Translate, cihazda),
  telefonda gösterir, istenirse seslendirir ve istenirse **TV'ye gönderir**.
- **tv** (Android TV): Telefondan gelen metni, TV'de HDMI görüntüsünün üstüne **overlay** olarak çizer.

```
Xbox ──HDMI──────────────────────────► TV (oyun görüntüsü + overlay altyazı)
  │                                      ▲
  └─Remote Play (Wi-Fi)─► Telefon ──OCR─►Çeviri──WebSocket (yerel ağ)─┘
                           (NSD ile TV'yi kendisi bulur)
```

## Derleme

**Android Studio (önerilen):**
1. Android Studio'yu kur (https://developer.android.com/studio), aç.
2. File > Open ile bu klasörü (içinde `settings.gradle.kts` olan) seç. İlk açılışta SDK, Gradle ve
   kütüphaneleri kendisi indirir (birkaç dakika, internet gerekir). "Sync" bitene kadar bekle.
3. Menüden Build > Generate App Bundles or APKs > Generate APKs. Açılan pencerede önce `app`,
   sonra aynı adımı `tv` modülü için tekrarla.
4. APK'lar: `app/build/outputs/apk/debug/app-debug.apk` ve `tv/build/outputs/apk/debug/tv-debug.apk`.

Komut satırı: `./gradlew assembleDebug` (JDK 17 ve Android SDK gerekir).

**GitHub Actions:** projeyi bir GitHub deposuna yükle. "Build APK" çalışınca
"oyun-ceviri-apks" çıktısını indir.

## TV kurulumu (bir kerelik)

1. TV'de Ayarlar > Hakkında > "Derleme numarası"na 7 kez bas (Geliştirici seçenekleri açılır),
   sonra **USB/ağ hata ayıklamayı** aç. TV'nin IP'sini not et (Ayarlar > Ağ).
2. Bilgisayardan (aynı Wi-Fi):
   ```
   adb connect TV_IP:5555
   adb install tv-debug.apk
   adb shell appops set com.berkay.oyunceviri.tv SYSTEM_ALERT_WINDOW allow
   ```
   (Bilgisayar yoksa APK'yı USB belleğe koyup TV'deki dosya yöneticisiyle kurabilirsin,
   ama overlay izni için ADB gerekebilir.)
3. TV'de "Oyun Çeviri TV"yi aç, **Servisi başlat**.
4. **Önemli test:** "10 sn sonra test altyazısı göster"e bas, TV girişini hemen Xbox'ın HDMI'ına çevir.
   10 sn sonra altyazı görünmüyorsa bu TV HDMI üstüne overlay çizmeye izin vermiyor demektir.

## Kullanım

1. Telefonda uygulamayı aç, "Çeviriyi TV'ye de gönder"i aç, yazı boyutu/koyuluğu ayarla.
2. **Başlat**, izinleri ver. Telefon TV'yi kendiliğinden bulur ("TV bağlandı" mesajı çıkar).
3. Xbox'ta oyunu TV'de oyna, telefonda Xbox uygulamasından uzaktan oynatmayı aç.

## Protokol (WebSocket, JSON)

```
{"type":"sub","text":"Merhaba"}                      altyazıyı göster
{"type":"clear"}                                      gizle
{"type":"style","size":28,"bg":70,"shadow":true}      yazı boyutu (sp), arka plan %, gölge
```
Düz metin gönderirsen o da altyazı olarak çıkar (herhangi bir WebSocket test aracıyla denenebilir:
`ws://TV_IP:8765`).

## Bilinen sınırlar

- Yalnızca ekranda yazılı altyazıları okur (OCR). Konuşma tanıma yok. Kaynak dil İngilizce'ye sabit.
- Uçtan uca gecikme yaklaşık 1 sn: ağ aktarımı milisaniyeler sürer ama OCR ve "aynı metni 2 karede gör"
  kuralı süreyi belirler. Ayrıca Remote Play yayını TV'deki görüntüden biraz geridedir.
- Bağlantı doğrulama/şifreleme yok: aynı ağdaki herkes TV'ye altyazı gönderebilir (ev ağı için kabul edilebilir).
- Router'da "istemci izolasyonu / AP isolation" açıksa telefon TV'yi bulamaz.
