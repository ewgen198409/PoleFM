#!/bin/sh
# Чтение ICY/RDS-метаданных радиостанции (StreamTitle).
#
# Эффективная схема (как radio_meta.php в web_page_autoelectric):
#   - ОДНО соединение с потоком станции;
#   - заголовок icy-metaint парсится из того же ответа (без отдельного HEAD-запроса);
#   - поток разбирается на лету в awk (аудио пропускается, мета-блоки читаются);
#   - как только заголовок найден — awk завершается, curl получает SIGPIPE
#     и загрузка обрывается (обычно 15-30 КБ вместо 450 КБ);
#   - без временных файлов и dd bs=1 (по байту за системный вызов).

echo "Content-Type: application/json"
echo "Cache-Control: no-store"
echo

[ "$REQUEST_METHOD" = "POST" ] || {
  echo '{"ok":false,"title":""}'
  exit 0
}

BODY="$(cat)"
BODY_ONE_LINE="$(printf '%s' "$BODY" | tr -d '\r\n')"
ACTION="$(printf '%s' "$BODY_ONE_LINE" | sed -n 's/.*"action"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
URL="$(printf '%s' "$BODY_ONE_LINE" | sed -n 's/.*"url"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"

key_for_url() {
  local u="$1"
  local k
  k="$(printf '%s' "$u" | md5sum 2>/dev/null | awk '{print $1}')"
  [ -n "$k" ] || k="$(printf '%s' "$u" | cksum | awk '{print $1}')"
  printf '%s' "$k"
}

if [ "$ACTION" = "clear" ]; then
  if [ -n "$URL" ]; then
    KEY="$(key_for_url "$URL")"
    rm -f "/tmp/radio_np_${KEY}.txt"
  else
    rm -f /tmp/radio_np_*.txt
  fi
  echo '{"ok":true}'
  exit 0
fi

[ -n "$URL" ] || {
  echo '{"ok":false,"title":""}'
  exit 0
}

# Автоматическая очистка устаревших кэш-файлов (старше 1 минуты).
# Активные станции обновляют кэш каждые 12 секунд, поэтому их файлы не удаляются.
for f in /tmp/radio_np_*.txt; do
  [ -f "$f" ] || continue
  AGE_SEC="$(($(date +%s) - $(stat -c %Y "$f" 2>/dev/null || echo 0)))"
  case "$AGE_SEC" in
    ''|*[!0-9]*) rm -f "$f"; continue ;;
  esac
  if [ "$AGE_SEC" -gt 60 ]; then
    rm -f "$f"
  fi
done

KEY="$(key_for_url "$URL")"
CACHE_FILE="/tmp/radio_np_${KEY}.txt"
TITLE=""

# ── Одно соединение: качаем поток с Icy-MetaData и парсим на лету ──
# awk работает в LC_ALL=C (бинарная безопасность), байты 0x0A, скрытые
# awk-разделителем записей, восстанавливаются "фантомным" шагом после
# каждой записи. Состояния: 0=заголовки, 1=аудио, 2=байт длины, 3=мета.
TITLE="$(curl -s -i -L -m 12 -H 'Icy-MetaData: 1' -H 'User-Agent: Mozilla/5.0' "$URL" 2>/dev/null \
  | LC_ALL=C awk -v MAXB=40 '
function step(b) {
  ch = sprintf("%c", b)
  lc = ch
  if (b >= 65 && b <= 90) lc = sprintf("%c", b + 32)   # нижний регистр
  if (phase == 1) {                          # аудио: пропускаем metaint байт
    if (--audio_left <= 0) phase = 2
    return
  }
  if (phase == 2) {                          # байт длины мета-блока (N*16)
    meta_need = b * 16
    meta_have = 0
    if (meta_need == 0) { phase = 1; audio_left = metaint }
    else phase = 3
    return
  }
  if (phase == 3) {                          # тело мета-блока
    mb[++meta_have] = b
    if (meta_have >= meta_need) {
      # ищем "StreamTitle=\x27" (83 116 114 101 97 109 84 105 116 108 101 61 39)
      for (j = 1; j + 12 <= meta_have; j++) {
        if (mb[j] == 83 && mb[j+1] == 116 && mb[j+2] == 114 && mb[j+3] == 101 && mb[j+4] == 97 && mb[j+5] == 109 && mb[j+6] == 84 && mb[j+7] == 105 && mb[j+8] == 116 && mb[j+9] == 108 && mb[j+10] == 101 && mb[j+11] == 61 && mb[j+12] == 39) {
          out = ""
          for (k = j + 13; k <= meta_have && mb[k] != 39; k++)
            out = out sprintf("%c", mb[k])
          gsub(/^[ \t]+|[ \t]+$/, "", out)
          if (out != "") {
            printf "%s", out
            exit 0                           # найдено — рвём поток (SIGPIPE -> curl)
          }
          break                              # пустой титул — ждём следующий блок
        }
      }
      split("", mb)                          # пустой блок — к следующему
      if (++blocks >= MAXB) exit 1
      phase = 1
      audio_left = metaint
      return
    }
    return
  }
  if (phase == 4) {                          # метаданные в потоке: ищем первый StreamTitle=
    if (++scan_bytes > 65536) exit 1
    sw = substr(sw lc, length(sw lc) - 12)   # последние 13 символов
    if (b == 39 && sw == STPAT) { phase = 5; out = "" }
    return
  }
  if (phase == 5) {                          # собираем титул до закрывающей кавычки
    if (b == 39) {
      if (out == "") exit 1
      printf "%s", out
      exit 0
    }
    out = out sprintf("%c", b)
    if (length(out) > 2048) exit 1           # защита от мусора
    return
  }
  # phase 0 — сканируем заголовки ответа (если они есть)
  # Параллельно сырой скан StreamTitle= (curl не пишет HTTP-заголовки в stdout,
  # поэтому у некоторых станций метаданные видны сразу с первого байта).
  sc = substr(sc lc, length(sc lc) - 12)     # последние 13 символов
  if (b == 39 && sc == STPAT) { phase = 5; out = ""; sc = "" }
  if (++hdr_bytes > 16384) exit 1            # поток без ICY-метаданных
  if (hst == 11) {
    # metaint уже получен — ждём конца заголовков
  } else if (hst == 10) {                    # собираем цифры после "icy-metaint:"
    if (b == 13) { metaint = digits + 0; hst = 11 }
    else if (b >= 48 && b <= 57) digits = digits ch
    else if (b != 32) { hst = 0; digits = "" }
  } else {
    wl = substr(wl lc, length(wl lc) - 11)   # последние 12 символов заголовка
    if (b == 58 && wl == "icy-metaint:") { hst = 10; digits = "" }
    if (b == 97 && wl == "icy-metadata") has_meta = 1
  }
  e = substr(e lc, length(e lc) - 3)         # последние 4 символа (для \r\n\r\n)
  if (b == 10 && e == HEND) {
    if (hst == 11 && metaint >= 1 && metaint <= 1000000) {
      phase = 1
      audio_left = metaint
    } else if (has_meta) {
      phase = 4                              # интервал не объявлен — сканируем поток
      sw = ""; scan_bytes = 0; out = ""
    } else exit 1                             # конец заголовков без ICY-метаданных
  }
}
BEGIN {
  for (i = 0; i < 256; i++) N[sprintf("%c", i)] = i             # байт -> число
  HEND = sprintf("%c%c%c%c", 13, 10, 13, 10)
  STPAT = "streamtitle=" sprintf("%c", 39)
  phase = 0; hst = 0
  metaint = 0; audio_left = 0
  meta_need = 0; meta_have = 0
  blocks = 0; hdr_bytes = 0
  has_meta = 0; scan_bytes = 0
  wl = ""; e = ""; sw = ""; digits = ""; out = ""; sc = ""
}
{
  L = length($0)
  for (i = 1; i <= L; i++) step(N[substr($0, i, 1)])
  step(10)                                   # фантомный \n (съедается RS)
}
')"

if [ -n "$TITLE" ]; then
  printf '%s' "$TITLE" > "$CACHE_FILE"
elif [ -f "$CACHE_FILE" ]; then
  # Используем кэш только если он свежий (не старше 30 секунд)
  CACHE_AGE="$(($(date +%s) - $(stat -c %Y "$CACHE_FILE" 2>/dev/null || echo 0)))"
  if [ "$CACHE_AGE" -lt 30 ]; then
    TITLE="$(cat "$CACHE_FILE")"
  else
    rm -f "$CACHE_FILE"
  fi
fi

ESCAPED="$(printf '%s' "$TITLE" | sed 's/\\/\\\\/g; s/"/\\"/g')"
printf '{"ok":true,"title":"%s"}\n' "$ESCAPED"