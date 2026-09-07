#!/bin/sh
# Стриминг музыки с поддержкой Range запросов

MUSIC_ROOT="/mnt/disk/Musick"

# Получаем путь из query string
QUERY_STRING="${QUERY_STRING:-}"
PATH_ARG="$(printf '%s' "$QUERY_STRING" | sed -n 's/.*path=\([^&]*\).*/\1/p')"

# URL-декодирование
url_decode() {
  local encoded="$1"
  local decoded=""
  local i=0
  local len=${#encoded}
  while [ $i -lt $len ]; do
    local c="${encoded:$i:1}"
    if [ "$c" = "%" ]; then
      local hex="${encoded:$((i+1)):2}"
      decoded="${decoded}$(printf "\\x$hex" 2>/dev/null)"
      i=$((i+3))
    elif [ "$c" = "+" ]; then
      decoded="${decoded} "
      i=$((i+1))
    else
      decoded="${decoded}${c}"
      i=$((i+1))
    fi
  done
  printf '%s' "$decoded"
}

PATH_ARG="$(url_decode "$PATH_ARG")"

# Защита от path traversal
case "$PATH_ARG" in
  *".."*) echo "Status: 403 Forbidden"; echo; exit 0 ;;
esac

FULL_PATH="$MUSIC_ROOT/$PATH_ARG"

# Проверяем, что файл существует
if [ ! -f "$FULL_PATH" ]; then
  echo "Status: 404 Not Found"
  echo "Content-Type: text/plain"
  echo
  echo "File not found"
  exit 0
fi

# Определяем MIME тип
case "$FULL_PATH" in
  *.mp3|*.MP3)   MIME="audio/mpeg" ;;
  *.flac|*.FLAC) MIME="audio/flac" ;;
  *.ogg|*.OGG)   MIME="audio/ogg" ;;
  *.wav|*.WAV)   MIME="audio/wav" ;;
  *.m4a|*.M4A)   MIME="audio/mp4" ;;
  *.aac|*.AAC)   MIME="audio/aac" ;;
  *.opus|*.OPUS) MIME="audio/ogg" ;;
  *)             MIME="application/octet-stream" ;;
esac

FILE_SIZE="$(stat -c %s "$FULL_PATH" 2>/dev/null || echo 0)"

# Обработка Range запроса
RANGE="${HTTP_RANGE:-}"

if [ -n "$RANGE" ]; then
  # Формат: bytes=start-end
  RANGE_VAL="$(printf '%s' "$RANGE" | sed -n 's/bytes=\([0-9]*\)-\([0-9]*\)/\1 \2/p')"
  START="$(printf '%s' "$RANGE_VAL" | awk '{print $1}')"
  END="$(printf '%s' "$RANGE_VAL" | awk '{print $2}')"

  # Если start пустой (bytes=-N) - последние N байт
  if [ -z "$START" ] && [ -n "$END" ]; then
    START=$((FILE_SIZE - END))
    [ "$START" -lt 0 ] && START=0
    END=$((FILE_SIZE - 1))
  fi

  # Если end пустой - до конца файла
  if [ -z "$END" ] || [ "$END" -ge "$FILE_SIZE" ]; then
    END=$((FILE_SIZE - 1))
  fi

  # Проверка валидности
  if [ "$START" -gt "$END" ] || [ "$START" -ge "$FILE_SIZE" ]; then
    echo "Status: 416 Requested Range Not Satisfiable"
    echo "Content-Range: bytes */$FILE_SIZE"
    echo
    exit 0
  fi

  LENGTH=$((END - START + 1))

  echo "Status: 206 Partial Content"
  echo "Content-Type: $MIME"
  echo "Content-Length: $LENGTH"
  echo "Content-Range: bytes $START-$END/$FILE_SIZE"
  echo "Accept-Ranges: bytes"
  echo "Cache-Control: no-store"
  echo

  # Отправляем только запрошенный диапазон (быстро через tail+head)
  tail -c +$((START + 1)) "$FULL_PATH" 2>/dev/null | head -c "$LENGTH" 2>/dev/null
else
  echo "Status: 200 OK"
  echo "Content-Type: $MIME"
  echo "Content-Length: $FILE_SIZE"
  echo "Accept-Ranges: bytes"
  echo "Cache-Control: no-store"
  echo

  cat "$FULL_PATH" 2>/dev/null
fi