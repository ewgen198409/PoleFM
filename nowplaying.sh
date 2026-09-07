#!/bin/sh

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

METAINT="$(curl -m 7 -sI -H 'Icy-MetaData: 1' "$URL" 2>/dev/null \
  | tr -d '\r' \
  | awk -F': ' 'tolower($1)=="icy-metaint"{print $2; exit}')"

case "$METAINT" in
  ''|*[!0-9]*)
    TMP="$(mktemp /tmp/icy.XXXXXX 2>/dev/null)"
    [ -n "$TMP" ] || TMP="/tmp/icy.$$.$RANDOM.raw"
    trap 'rm -f "$TMP"' EXIT
    if curl -m 10 -s -L -H 'Icy-MetaData: 1' --range "0-450000" "$URL" > "$TMP" 2>/dev/null \
      || curl -m 12 -s -L -H 'Icy-MetaData: 1' "$URL" 2>/dev/null | dd bs=1 count=450000 of="$TMP" 2>/dev/null; then
      TITLE="$(grep -a -o "StreamTitle='[^']*'" "$TMP" 2>/dev/null | sed -n "s/StreamTitle='\\(.*\\)'/\\1/p" | sed '/^$/d' | head -n1)"
    fi
    ;;
  *)
    TMP="$(mktemp /tmp/icy.XXXXXX 2>/dev/null)"
    [ -n "$TMP" ] || TMP="/tmp/icy.$$.$RANDOM.bin"
    trap 'rm -f "$TMP"' EXIT
    MAX_BLOCKS=30
    RANGE_END=$(((METAINT + 1) * MAX_BLOCKS + 8192))
    if curl -m 10 -s -L -H 'Icy-MetaData: 1' --range "0-${RANGE_END}" "$URL" > "$TMP" 2>/dev/null; then
      i=0
      while [ "$i" -lt "$MAX_BLOCKS" ]; do
        OFF=$((i * (METAINT + 1)))
        LEN_POS=$((OFF + METAINT))
        LEN_BYTE="$(dd if="$TMP" bs=1 skip="$LEN_POS" count=1 2>/dev/null | od -An -tu1 | tr -d ' ')"
        case "$LEN_BYTE" in
          ''|*[!0-9]*) i=$((i + 1)); continue ;;
        esac
        META_LEN=$((LEN_BYTE * 16))
        if [ "$META_LEN" -le 0 ]; then
          i=$((i + 1))
          continue
        fi
        RAW_META="$(dd if="$TMP" bs=1 skip=$((LEN_POS + 1)) count="$META_LEN" 2>/dev/null | tr '\000' '\n')"
        TITLE="$(printf '%s' "$RAW_META" | sed -n "s/.*StreamTitle='\\([^']*\\)'.*/\\1/p" | head -n1)"
        [ -n "$TITLE" ] && break
        i=$((i + 1))
      done
    fi
    ;;
esac
trap - EXIT
rm -f "$TMP"

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
