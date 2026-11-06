# LOVJA Prompter

Samsung Galaxy Z Fold 8 için Android prompter (metin okuma) uygulaması.
lovja.com sitesiyle hiçbir ilgisi yok; ayrı ve bağımsız bir proje.

## Kullanıcı
Atakan, diş hekimi ve laboratuvar sahibi, yazılımcı değil. Her şeyi sade,
gündelik Türkçe ile anlat; kod dili, terim ve teknik ayrıntı kullanma. Ondan bir
şey istenecekse adım adım, tıklanacak yeriyle birlikte söyle.

## Ne yapıyor
- Telefon **tamamen açık** duruyor. Bu tek doğru duruş: arka kameralar ile dış
  ekran (telefon kapalıyken kullanılan küçük ekran) aynı yöne, kullanıcıya bakıyor.
  L şekli ya da kapalı duruş işe yaramaz.
- **Arka kamera** kullanıcıyı videoya çeker (ön kamera değil, kalite için şart).
- **Dış ekranda** metin altyazı gibi akar, okunan satır vurgulu.
- Hedef: metin **konuşma hızına göre** akar (dinleyerek hangi kelimede olduğunu
  anlar; durunca durur, hızlanınca hızlanır). Kelime atlama, takılma, tekrar
  tolere edilmeli.
- Metin yapıştırılır, "Başla" denir, video telefona (Galeri) kaydedilir.
- Dil: Türkçe (ileride İngilizce).

## Arayüz (Atakan'ın isteği, 2026-11-06)
- **Her şey küçük (dış) ekranda**: düğmeler, kamera önizlemesi, yazı. Atakan
  kayıtta sadece küçük ekranı görüyor.
- Küçük ekran açıkken **büyük ekran kararır ve dokunmaya kapanır** (tamamen
  kapatmak uygulamaya izinli değil; en düşük parlaklık + siyah + dokunma yok).
  Sadece "Düzenle"ye basınca büyük ekranda metin düzenleme açılır.
- "Başla" → 3-2-1 geri sayım → kayıt; kayıtta ekranda **sadece yazı + küçük
  "Bitir" tuşu** (ve küçük süre).
- **Dikey ve yatay çekim**: videonun yönü telefonun tutuluşuna göre otomatik.
  Küçük ekrandaki yazı kendiliğinden dönmezse "↻" ile 90° döndürülür (ayar saklanır).

## Teknik kararlar
- Web sitesi olamaz; gerçek Android uygulaması (Kotlin). Web sayfası dış ekrana içerik gönderemez.
- Dış ekrana içerik: Jetpack WindowManager `WindowAreaController` +
  `presentContentOnWindowArea` (dual-screen / rear display presentation).
  1.2.0-beta03'te geldi; güncel sürüm kullanılmalı. Samsung Fold'larda resmi
  destek, şart Android 14+ (API 34) → `minSdk 34`.
- Readest geliştiricisinin uyarısı (Fold 7): açılıp kapanırken ve uygulama ilk
  açılırken sistem bir anlığına yanlış durum bildiriyor. Bildirilen durum
  **~300 ms değişmeden kalırsa** ona güven (kodda `collectLatest` + `delay(300)`).
- Fold 8 için doğrudan kayıt yok; ilk sürümün amacı bunu doğrulamak.
- Mikrofon riski: aynı anda video sesi kaydı + konuşma tanıma Android'de her zaman
  birlikte çalışmıyor. Plan: sesi tek yerden (AudioRecord) al, aynı sesi hem
  videoya yaz hem de **internetsiz, cihaz içi Türkçe tanımaya** (Vosk Türkçe
  modeli ya da Android cihaz içi tanıma) ver. Tanınan kelimeleri metindeki
  kelimelerle eşleştir, kaydırmayı buna göre yap.
- Kaydırma motoru (`PrompterEngine`) konumu **kelime sırası** olarak tutar; şimdilik
  sabit hızla ilerliyor, konuşma tanıma geldiğinde aynı konumu tanıma güncelleyecek.

## Kurulum ve derleme
- Play Store yok. Kullanıcıya APK verilir, kendisi yükler ("bilinmeyen kaynak"
  izni bir kez).
- Bulut ortamı Google'ın Android sunucularına (dl.google.com, maven.google.com)
  erişemiyor, yani APK burada derlenemiyor. APK **GitHub Actions** ile her
  `main` güncellemesinde derleniyor (`.github/workflows/build.yml`), "Releases"
  bölümüne de konuyor. APK'yı REST ile al (`gh api .../releases`, asset'i `Accept: application/octet-stream` ile indir; `gh release download` GraphQL kullandığı için engelli), `/mnt/project-files/apk/` altına koy ve kullanıcıya dosya olarak ver.
- APK, depodaki sabit `app/lovja-debug.keystore` ile imzalanır; böylece yeni sürüm
  eskisinin üstüne kaldırmadan kurulur. Anahtarı değiştirme.

## Sürümler
- v0.1: arka kamera kaydı + dış ekranda sabit hızla akan metin (riskli kısmı doğrulama).
- v0.1 sonucu (Fold 8): kayıt "kod 8" (ERROR_NO_VALID_DATA, kameradan görüntü gelmedi)
  verdi; 4K yerine Full HD'ye geçildi, hata mesajı ayrıntılandı.
- v0.2: tüm kontroller küçük ekranda, büyük ekran karartılmış, dikey/yatay.
- v0.3 (sıradaki): konuşmaya göre kaydırma (Türkçe, internetsiz).

## Kaynaklar
- https://developer.android.com/develop/adaptive-apps/guides/foldables/support-foldable-display-modes
- https://developer.samsung.com/sdp/blog/en/2024/09/26/unfold-the-potential-of-galaxy-fold-devices-with-windowmanagers-rear-display-mode
- https://github.com/readest/readest/issues/7133
- https://developer.android.com/jetpack/androidx/releases/window
