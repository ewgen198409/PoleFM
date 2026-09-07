<?php
/**
 * nowplaying.php — определение текущего трека (ICY/RDS-метаданные) для PoleFM.
 *
 * Порт эффективного потокового разбора из radio_meta.php (web_page_autoelectric):
 *   - сервер подписывается на поток и читает метаданные «на лету», без скачивания
 *     большого куска во временный файл, как это делает nowplaying.sh;
 *   - как только найден непустой StreamTitle, чтение потока немедленно
 *     останавливается (curl write-callback возвращает 0);
 *   - до двух попыток со свежим соединением (первый запрос иногда попадает
 *     в рекламный блок или пустой RDS);
 *   - белый список хостов защищает от использования скрипта как открытого прокси;
 *   - файловый кэш в /tmp с TTL 90 с смягчает нагрузку при опросе каждые 12 с.
 *
 * Запрос:  POST { "url": "http://...", "action": "get"|"clear" }
 *          (GET тоже поддержан: nowplaying.php?url=...)
 * Ответ:   {"ok":true,"title":"исполнитель - трек"} — совместим со старым nowplaying.sh
 *          (дополнительно добавлено {"debug":{...}} для диагностики).
 */

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

/* ── Настройки ─────────────────────────────────────────────────────────── */

/* Белый список хостов — динамически из stations.json, чтобы адаптироваться
   при добавлении/удалении станций через веб-интерфейс. Запасной вариант —
   жёстко заданный список (на случай, если stations.json недоступен). */
function radio_load_allowed_hosts() {
    static $hosts = null;
    if ($hosts !== null) return $hosts;

    $hosts = array();
    $stations_file = __DIR__ . '/stations.json';
    if (is_file($stations_file)) {
        $raw = @file_get_contents($stations_file);
        if ($raw !== false) {
            $stations = json_decode($raw, true);
            if (is_array($stations)) {
                foreach ($stations as $s) {
                    if (isset($s['url']) && is_string($s['url'])) {
                        $p = parse_url($s['url']);
                        if (isset($p['host'])) {
                            $hosts[] = strtolower($p['host']);
                        }
                    }
                }
            }
        }
    }
    /* Запасной список на случай, если stations.json недоступен */
    if (empty($hosts)) {
        $hosts = array(
            'rusradio.hostingradio.ru',
            'ep128.hostingradio.ru',
            'record128.hostingradio.ru',
            'air.unmixed.ru',
            'listen.vdfm.ru',
            'hitfm.hostingradio.ru',
            'mega.amgradio.ru',
            'dfm-disc90.hostingradio.ru',
            'stream06.pcradio.app',
            'radiorecord.hostingradio.ru',
        );
    }
    $hosts = array_unique($hosts);
    return $hosts;
}

$allowed_hosts = radio_load_allowed_hosts();

$cache_dir   = '/tmp';
$cache_ttl   = 90;          // секунды, срок жизни кэша заголовка
$time_budget = 10;          // секунды, бюджет одной попытки чтения потока
$max_blocks  = 40;          // максимум ICY-блоков за попытку
$max_tries   = 2;           // попыток со свежим соединением
$overall     = 24;          // общий бюджет времени на весь запрос, сек

/* ── Разбор запроса ────────────────────────────────────────────────────── */

$debug = array(
    'mode' => 'none', 'metaint' => 0, 'blocks' => 0, 'attempts' => 0,
    'duration' => 0, 'cached' => false, 'error' => '', 'url' => '',
);

/* Ответ JSON и выход */
function radio_reply($ok, $title) {
    global $debug;
    $payload = array('ok' => (bool)$ok, 'title' => (string)$title, 'debug' => $debug);
    echo json_encode($payload, JSON_UNESCAPED_UNICODE);
    exit;
}

$raw = file_get_contents('php://input');
if (is_string($raw) && $raw !== '') {
    $j = json_decode($raw, true);
    if (is_array($j)) {
        $url    = isset($j['url'])    ? trim((string)$j['url'])    : '';
        $action = isset($j['action']) ? trim((string)$j['action']) : '';
    } else {
        $url = ''; $action = '';
    }
} else {
    $url    = isset($_GET['url']) ? trim($_GET['url']) : '';
    $action = isset($_GET['action']) ? trim($_GET['action']) : '';
}

/* ── Ключ кэша ─────────────────────────────────────────────────────────── */

function radio_cache_key($u) {
    global $cache_dir;
    $k = md5($u);
    /* тот же формат файлов, что использует nowplaying.sh — кэш общий */
    return $cache_dir . '/radio_np_' . $k . '.txt';
}

/* ── action=clear: сброс кэша ──────────────────────────────────────────── */

if ($action === 'clear') {
    if ($url !== '') {
        @unlink(radio_cache_key($url));
    } else {
        foreach (glob($cache_dir . '/radio_np_*.txt') as $f) { @unlink($f); }
    }
    radio_reply(true, '');
}

if ($url === '') {
    $debug['error'] = 'no url';
    radio_reply(false, '');
}

/* ── Валидация URL и белый список ──────────────────────────────────────── */

$parts = parse_url($url);
if (!is_array($parts) || !isset($parts['scheme'], $parts['host'])) {
    $debug['error'] = 'bad url';
    radio_reply(false, '');
}
if (!in_array(strtolower($parts['scheme']), array('http', 'https'), true)) {
    $debug['error'] = 'bad scheme';
    radio_reply(false, '');
}
$host = strtolower($parts['host']);
if (!in_array($host, $allowed_hosts, true)) {
    $debug['error'] = 'host not allowed: ' . $host;
    radio_reply(false, '');
}

$debug['url'] = $url;

/* ── Извлечение StreamTitle из сырого блока метаданных ─────────────────── */

function radio_extract_title($meta) {
    if (preg_match("/StreamTitle='([^']*)'/is", $meta, $m)) return trim($m[1]);
    if (preg_match('/StreamTitle="([^"]*)"/is', $meta, $m))  return trim($m[1]);
    return '';
}

/* ── Одна попытка чтения потока (потоковый разбор ICY) ─────────────────── */

function probe_stream($url, $time_budget, $max_blocks) {
    global $debug;

    $st = array(
        'metaint'   => 0,
        'audioLeft' => 0,
        'metaLeft'  => -1,
        'metaBuf'   => '',
        'title'     => '',
        'done'      => false,
        'blocks'    => 0,
        'start'     => microtime(true),
    );

    $header_fn = function ($ch, $line) use (&$st) {
        if (stripos($line, 'icy-metaint:') !== false) {
            $st['metaint'] = (int)trim(substr($line, strpos($line, ':') + 1));
            /* первый аудио-блок идёт сразу после заголовков, его длину даёт metaint */
            $st['audioLeft'] = $st['metaint'];
        }
        return strlen($line);
    };

    $body_fn = function ($ch, $chunk) use (&$st, $time_budget, $max_blocks) {
        if ($st['done']) return 0;
        if ($st['metaint'] <= 0) { $st['done'] = true; return 0; } /* нет ICY-метаданных */
        if (microtime(true) - $st['start'] > $time_budget) { $st['done'] = true; return 0; }

        $len = strlen($chunk);
        $pos = 0;
        while ($pos < $len) {
            if ($st['metaLeft'] === -1) {
                if ($st['audioLeft'] > 0) {
                    $skip = min($st['audioLeft'], $len - $pos);
                    $st['audioLeft'] -= $skip;
                    $pos += $skip;
                } else {
                    $st['metaLeft'] = ord($chunk[$pos]) * 16; /* длина блока = первый байт * 16 */
                    $pos++;
                }
            } else {
                $need = $st['metaLeft'] - strlen($st['metaBuf']);
                $take = min($need, $len - $pos);
                $st['metaBuf'] .= substr($chunk, $pos, $take);
                $pos += $take;
                if (strlen($st['metaBuf']) >= $st['metaLeft']) {
                    $st['blocks']++;
                    $candidate = radio_extract_title($st['metaBuf']);
                    if ($candidate !== '') {
                        $st['title'] = $candidate;
                        $st['done'] = true;
                        return 0; /* нашли — немедленно останавливаем чтение */
                    }
                    /* пустой блок — переходим к следующему */
                    $st['metaLeft']  = -1;
                    $st['metaBuf']   = '';
                    $st['audioLeft'] = $st['metaint'];
                    if ($st['blocks'] >= $max_blocks) { $st['done'] = true; return 0; }
                }
            }
        }
        return strlen($chunk);
    };

    $ch = curl_init($url);
    curl_setopt_array($ch, array(
        CURLOPT_RETURNTRANSFER => false,
        CURLOPT_FOLLOWLOCATION => true,
        CURLOPT_MAXREDIRS      => 5,
        CURLOPT_CONNECTTIMEOUT => 8,
        CURLOPT_TIMEOUT        => $time_budget + 8,
        /* SSL-проверку отключаем: у публичных радиостримов бывают проблемы с
           цепочкой сертификатов, а домены ограничены белым списком */
        CURLOPT_SSL_VERIFYPEER => false,
        CURLOPT_SSL_VERIFYHOST => false,
        CURLOPT_HTTPHEADER     => array('Icy-MetaData: 1', 'User-Agent: Mozilla/5.0'),
        CURLOPT_HEADERFUNCTION => $header_fn,
        CURLOPT_WRITEFUNCTION  => $body_fn,
    ));
    curl_exec($ch);
    $errno  = curl_errno($ch);
    $errstr = curl_error($ch);
    curl_close($ch);

    /* errno 23 (CURLE_WRITE_ERROR) — штатная остановка через write-callback */
    if ($errno && empty($st['done'])) {
        $debug['error'] = 'curl: ' . $errstr . ' (errno ' . $errno . ')';
    } elseif ($errno && $st['done'] && $st['metaint'] <= 0 && $debug['error'] === '') {
        $debug['error'] = 'curl: поток без icy-metaint';
    }

    $debug['metaint']  = $st['metaint'];
    $debug['blocks']  += $st['blocks'];
    $debug['attempts']++;

    return $st['title'];
}

/* ── Основной сценарий ─────────────────────────────────────────────────── */

$CACHE_FILE  = radio_cache_key($url);
$start_all   = microtime(true);
$title       = '';

/* 1. Свежий кэш */
if (is_file($CACHE_FILE)) {
    $age = time() - (int)filemtime($CACHE_FILE);
    if ($age >= 0 && $age < $cache_ttl) {
        $cached = file_get_contents($CACHE_FILE);
        if ($cached !== false && trim($cached) !== '') {
            $title = trim($cached);
            $debug['mode']    = 'cache';
            $debug['cached']  = true;
            $debug['duration'] = 0;
            radio_reply(true, $title);
        }
    }
}

/* 2. Живое чтение потока: cURL */
if (function_exists('curl_init')) {
    $debug['mode'] = 'curl';
    $try = 1;
    while ($try <= $max_tries && $title === '' && (microtime(true) - $start_all) < $overall) {
        $budget = ($try === 1) ? $time_budget : $time_budget - 2;
        $title  = probe_stream($url, $budget, $max_blocks);
        $try++;
    }
} elseif (ini_get('allow_url_fopen')) {
    /* 2б. Запасной вариант: fopen */
    $debug['mode'] = 'fopen';
    $context = stream_context_create(array(
        'http' => array(
            'method'          => 'GET',
            'timeout'         => $time_budget + 5,
            'follow_location' => 1,
            'max_redirects'   => 5,
            'ignore_errors'   => true,
            'header'          => "Icy-MetaData: 1\r\nUser-Agent: Mozilla/5.0\r\n",
        ),
    ));
    $fp = @fopen($url, 'rb', false, $context);
    if ($fp) {
        stream_set_timeout($fp, $time_budget);
        $metaint = 0;
        if (isset($http_response_header) && is_array($http_response_header)) {
            foreach ($http_response_header as $h) {
                if (stripos($h, 'icy-metaint:') !== false) {
                    $metaint = (int)trim(substr($h, strpos($h, ':') + 1));
                }
            }
        }
        $debug['metaint'] = $metaint;
        $start = microtime(true);
        $blocks = 0;
        if ($metaint > 0) {
            $audio_left = $metaint;
            while ($blocks < $max_blocks && (microtime(true) - $start) < $time_budget) {
                $read = 0;
                while ($read < $audio_left) {
                    $chunk = @fread($fp, $audio_left - $read);
                    if ($chunk === false || $chunk === '') { break 2; }
                    $read += strlen($chunk);
                }
                $audio_left = $metaint;
                $len_byte = @fread($fp, 1);
                if ($len_byte === false || $len_byte === '') { break; }
                $meta_len = ord($len_byte) * 16;
                $meta = '';
                if ($meta_len > 0) {
                    while (strlen($meta) < $meta_len) {
                        $chunk = @fread($fp, $meta_len - strlen($meta));
                        if ($chunk === false || $chunk === '') { break; }
                        $meta .= $chunk;
                    }
                }
                $blocks++;
                $candidate = radio_extract_title($meta);
                if ($candidate !== '') { $title = $candidate; break; }
            }
        }
        fclose($fp);
        $debug['blocks'] = $blocks;
    } elseif ($debug['error'] === '') {
        $debug['error'] = 'fopen: не удалось открыть поток';
    }
} else {
    $debug['error'] = 'ни cURL, ни allow_url_fopen не доступны на сервере';
}

$debug['duration'] = round(microtime(true) - $start_all, 2);

/* 3. Кэшируем найденный заголовок */
if ($title !== '') {
    @file_put_contents($CACHE_FILE, $title);
} elseif ($debug['error'] === '') {
    $debug['error'] = 'за ' . $debug['duration'] . 'с (' . $debug['blocks'] . ' блоков) заголовков не найдено';
}

radio_reply(true, $title);
