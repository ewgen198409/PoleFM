#!/bin/sh
# Файловый менеджер для музыки
# Возвращает JSON со списком папок и файлов

echo "Content-Type: application/json"
echo "Cache-Control: no-store"
echo

MUSIC_ROOT="/mnt/disk/Musick"

# Получаем путь из query string
QUERY_STRING="${QUERY_STRING:-}"
# Декодируем URL-кодированный путь
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
  *".."*) PATH_ARG="" ;;
esac

FULL_PATH="$MUSIC_ROOT"
if [ -n "$PATH_ARG" ]; then
  FULL_PATH="$MUSIC_ROOT/$PATH_ARG"
fi

# Проверяем, что путь существует и является директорией
if [ ! -d "$FULL_PATH" ]; then
  echo '{"ok":false,"error":"not_found","path":"'$PATH_ARG'"}'
  exit 0
fi

# Экранирование для JSON
json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

# Собираем JSON
{
  printf '{"ok":true,"path":"%s","items":[' "$(json_escape "$PATH_ARG")"

  FIRST=1
  # Сначала папки
  for d in "$FULL_PATH"/*/; do
    [ -d "$d" ] || continue
    NAME="$(basename "$d")"
    if [ $FIRST -eq 0 ]; then
      printf ','
    fi
    FIRST=0
    printf '{"type":"dir","name":"%s"}' "$(json_escape "$NAME")"
  done

  # Затем файлы (только аудио)
  for f in "$FULL_PATH"/*; do
    [ -f "$f" ] || continue
    case "$f" in
      *.mp3|*.MP3|*.flac|*.FLAC|*.ogg|*.OGG|*.wav|*.WAV|*.m4a|*.M4A|*.aac|*.AAC|*.opus|*.OPUS)
        NAME="$(basename "$f")"
        SIZE="$(stat -c %s "$f" 2>/dev/null || echo 0)"
        if [ $FIRST -eq 0 ]; then
          printf ','
        fi
        FIRST=0
        printf '{"type":"file","name":"%s","size":%s}' "$(json_escape "$NAME")" "$SIZE"
        ;;
    esac
  done

  printf ']}'
}