#!/bin/sh
STATIONS_FILE="/www/radio/stations.json"
# Файл с PIN-кодом для добавления/удаления/редактирования станций.
# ВАЖНО: путь НЕ должен находиться внутри веб-корня! Веб-корень — это
# каталог, из которого отдаётся index.html (у вас это /www/radio).
# Если положить файл сюда же (напр. /www/radio/pin), его можно будет
# скачать напрямую как https://домен/pin. Поэтому файл храним ВНЕ
# раздаваемой папки, например в /www/pin или /etc/radio_pin.
PIN_FILE="/www/pin_radio"

echo "Content-Type: application/json"
echo "Cache-Control: no-store"
echo

[ "$REQUEST_METHOD" = "POST" ] || { echo '{"ok":false,"error":"method_not_allowed"}'; exit 0; }

# ── Проверка PIN-кода ──
# PIN передаётся заголовком "X-Pin" (в CGI доступен как HTTP_X_PIN)
# или query-параметром "?pin=...".
PIN_PROVIDED="$HTTP_X_PIN"
if [ -z "$PIN_PROVIDED" ]; then
  PIN_PROVIDED="$(printf '%s' "$QUERY_STRING" | sed -n 's/.*pin=\([^&]*\).*/\1/p')"
fi

# Ожидаемый PIN читаем из файла (игнорируем пробелы/переводы строк)
PIN_EXPECTED=""
if [ -f "$PIN_FILE" ]; then
  PIN_EXPECTED="$(tr -d ' \t\r\n' < "$PIN_FILE")"
fi

if [ -z "$PIN_EXPECTED" ]; then
  echo '{"ok":false,"error":"pin_not_configured"}'
  exit 0
fi

if [ -z "$PIN_PROVIDED" ]; then
  echo '{"ok":false,"error":"pin_required"}'
  exit 0
fi

if [ "$PIN_PROVIDED" != "$PIN_EXPECTED" ]; then
  echo '{"ok":false,"error":"invalid_pin"}'
  exit 0
fi

BODY="$(cat)"
echo "$BODY" | grep -q '^[[:space:]]*\[' || { echo '{"ok":false,"error":"invalid_json"}'; exit 0; }

TMP_FILE="$(mktemp /tmp/stations.XXXXXX.json 2>/dev/null)"
[ -n "$TMP_FILE" ] || TMP_FILE="/tmp/stations.$$.$RANDOM.json"
trap 'rm -f "$TMP_FILE"' EXIT
printf "%s\n" "$BODY" > "$TMP_FILE" || { echo '{"ok":false,"error":"write_failed"}'; exit 0; }
mv "$TMP_FILE" "$STATIONS_FILE" || { echo '{"ok":false,"error":"move_failed"}'; exit 0; }
trap - EXIT

echo '{"ok":true}'
