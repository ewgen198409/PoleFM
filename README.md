# PoleFM — интернет-радио

![PoleFM](internet_radio_fm.png)

**PoleFM** — домашнее интернет-радио из двух частей:

1. **Веб-интерфейс** — одностраничный плеер (`index.html`) с списком станций, файловым менеджером музыки и редактированием станций под PIN-кодом. Требует веб-сервер с PHP (для RDS-метаданных); остальные функции — CGI-скрипты для любого CGI-совместимого HTTP-сервера (например, busybox httpd).
2. **Android-приложение** — нативная оболочка (WebView) вокруг веб-интерфейса с фоновым воспроизведением, медиа-клавишами гарнитуры и уведомлением плеера.

## Структура репозитория

```
├── index.html            # Веб-интерфейс плеера (SPA, без зависимостей)
├── stations.json         # Список радиостанций
├── music_stream.sh       # CGI: стриминг музыкальных файлов (поддержка HTTP Range)
├── music_list.sh         # CGI: файловый менеджер — JSON-список папок/файлов
├── nowplaying.php        # PHP: текущий трек (потоковый разбор ICY/RDS, как radio_meta.php)
├── save_stations.sh      # CGI: сохранение станций (защищено PIN-кодом)
└── app/                  # Android-приложение (Gradle, Java)
```

## Веб-часть

CGI-скрипты рассчитаны на окружение вроде busybox httpd (переменные `QUERY_STRING`, `REQUEST_METHOD`):

| Скрипт | Назначение |
|---|---|
| `music_stream.sh` | Отдача музыкальных файлов с поддержкой перемотки (Range-запросы) |
| `music_list.sh` | Навигация по музыкальной коллекции (JSON) |
| `nowplaying.php` | Что сейчас играет — RDS-метаданные (требуется PHP с cURL или `allow_url_fopen`) |
| `save_stations.sh` | Добавление/редактирование/удаление станций по PIN-коду |

Пути к музыкальному каталогу (`MUSIC_ROOT`) и файлу PIN-кода настраиваются в начале скриптов.

#### Определение текущего трека (RDS)

`nowplaying.php` — это порт потокового разбора ICY/RDS-метаданных из проекта
`web_page_autoelectric` (`radio_meta.php`). Сервер подписывается на поток и читает
метаданные «на лету»: как только найден непустой `StreamTitle`, чтение останавливается.
Это эффективнее прежнего CGI-варианта (`nowplaying.sh`), который скачивал большой кусок
потока во временный файл и разбирал его постфактум.

Особенности `nowplaying.php`:

- белый список хостов (синхронизирован со `stations.json`) — защита от использования как открытого прокси;
- до двух попыток со свежим соединением (первый запрос иногда попадает в рекламный блок);
- файловый кэш в `/tmp` (TTL 90 с);
- интерфейс: `POST {url}` → `{"ok":true,"title":"..."}`, поддержан `action:"clear"` для сброса кэша;
- поле `debug` в ответе (mode/metaint/blocks/duration) удобно для диагностики.

> **Требование:** нужен веб-сервер с PHP (5.4+) и расширением cURL либо `allow_url_fopen`.

> **Важно:** PIN-код хранится в файле **вне веб-корня** (см. комментарий в `save_stations.sh`) — иначе его можно скачать напрямую. В репозитории файла `pin` нет, он добавлен в `.gitignore`.

## Android TV-приложение

Нативное приложение на **Kotlin + Jetpack Compose for TV** (`androidx.tv:tv-material`).
Не WebView: приложение напрямую использует JSON API веб-сервера (`stations.json`,
`save_stations.sh`, `nowplaying.php`, `music_list.sh`, `music_stream.sh`), но новый
интерфейс полностью переработан под «10-foot UI» и управление пультом (D-pad без мыши).

### Возможности

- **Две вкладки**: «Онлайн радио» и «Моя музыка» (переключение по фокусу D-pad)
- **Now Playing**: статус, название, бегущая ICY/RDS-строка (опрос раз в 12 с),
  эквалайзер на **реальном спектре PCM** (Goertzel-анализ в `AudioProcessor` плеера,
  работает и там, где недоступен системный `Visualizer`), Play/Pause/Stop
- **Фокус**: стартует на кнопках Play/Pause и Stop; карточка Now Playing не перехватывает ОК
- **Громкость**: внутренний ползунок 0–100 (стрелки влево/вправо, сохранение)
  + аппаратные кнопки пульта управляют системным `STREAM_MUSIC`
- **Станции**: воспроизведение с реконнектом; долгое нажатие ОК на карточке открывает
  редактирование. Добавление/редактирование/удаление — под одним и тем же PIN
  (экранная цифровая клавиатура и цифровые кнопки пульта)
- **Недавно прослушанные**: горизонтальный ряд в приложении и канал «PoleFM —
  недавно прослушанные» на главном экране Android TV; выбор программы открывает
  приложение по deep-link `polefm://station?url=...` и сразу включает станцию
- **Баннер лаунчера** `res/drawable/banner.png` с названием PoleFM
- **Музыка**: файловый менеджер с breadcrumbs, перемотка, авто-next по списку папки
- **Фон**: `MediaSessionService` с уведомлением, медиаклавиши пульта;
  cleartext-трафик для HTTP-потоков станций включён (`usesCleartextTraffic`)
- `minSdk 26`, `targetSdk 34`, JVM target 17

### Сборка

Требования: JDK 17, Android SDK (platform 34, build-tools 34.0.0).

```bash
cd app
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleRelease
```

Готовый APK: `app/app/build/outputs/apk/release/PoleFM.apk`.

Установка на Android TV по ADB:

```bash
adb connect 192.168.1.235:5555
adb -s 192.168.1.235:5555 install -r app/app/build/outputs/apk/release/PoleFM.apk
adb -s 192.168.1.235:5555 shell am start -n com.polefm.radio/.MainActivity
```

### Подпись

Release подписывается новым ключом `app/polefm-release.keystore`; параметры —
в `app/keystore.properties` (оба файла исключены из git через `app/.gitignore`).

```properties
storeFile=polefm-release.keystore
storePassword=...
keyAlias=polefm
keyPassword=...
```

> ⚠️ Для PKCS12-keystore пароль ключа должен совпадать с паролем хранилища.
