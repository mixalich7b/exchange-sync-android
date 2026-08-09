# Диагностика через Logcat

## Граница реализации

Приложение пишет структурированные on-device records в системный Logcat под
стабильным тегом `ExchangeSync`. Они охватывают проверку подключения, DNS/TCP,
HTTPS, TLS/mTLS, KeyChain, local CA, redirect и capability negotiation,
`FolderSync`/`Sync`, разбор и представление событий, Calendar Provider,
синхронизацию и WorkManager.

Отдельного режима диагностики, файла, DataStore-архива, in-app viewer, upload,
telemetry или analytics нет. Доступны только records, которые ещё удерживает
системный log buffer Android; после очистки или ротации восстановить их из
приложения нельзя. Ошибка форматирования или записи diagnostics не меняет
результат пользовательской операции.

## Поля и корреляция

Каждая строка начинается с allow-listed `component` и `stage`. В зависимости от
границы она может содержать:

- `operation` и `operation_kind` — уникальная в текущем процессе связь шагов;
- `generation`, `run_token`, `trigger`, `phase` и `attempt` для sync/worker;
- HTTP `method`, ActiveSync `command`, `host` и `path` без query, `status` и
  `timeout_ms`;
- безопасные protocol versions/commands, `reason`, `failure` и `outcome`;
- opaque `server_id` для отклонённого события;
- local-CA filename, длину certificate chain, алгоритм публичного ключа и
  SHA-256 fingerprint, когда эти metadata доступны;
- bounded exception/cause class graph и ограниченные stack frames; сообщения
  допускаются только на границах, где они могут быть безопасно очищены.

Обычный путь расследования: найти terminal `failure`/`outcome`, затем собрать
строки с тем же `operation`. Для синхронизации дополнительно сопоставляются
`generation` и `run_token`. Идентификаторы operation начинаются заново после
перезапуска процесса и не являются persisted IDs.

Уровень `INFO` отмечает начало, capability/phase и terminal
success/cancellation/obsolete. `WARN` используется для rejected redirect,
protocol/HTTP validation, invalid event и recoverable retry/reset.
TLS/mTLS, block, критические локальные и неожиданные ошибки имеют `ERROR`.

## Запрещённые данные

Diagnostics не должны содержать:

- имена, значения или атрибуты cookie и заголовки `Cookie`/`Set-Cookie`;
- `Authorization`, email, `domain\login` или KeyChain alias;
- private keys, PEM/DER и другие raw certificate encodings;
- полный URL, user-info, query string, request/response body или WBXML;
- subject, body, location, attendees, organizer, timestamps collection и иной
  personal event/provider payload;
- raw exception output.

Call sites передают только типизированные разрешённые поля. Дополнительный
централизованный formatter ограничивает длину и глубину exception graph,
обрывает циклы, удаляет control characters, query/user-info, header-like
credentials и account-like text. Cookie session также не предоставляет свои
данные diagnostics.

## Сбор через ADB

Подключите Android 16 device и убедитесь, что оно видно:

```shell
adb devices -l
```

Чтобы получить чистый воспроизводимый фрагмент, очистите текущие системные log
buffers непосредственно перед сценарием:

```shell
adb logcat -c
```

Для наблюдения в реальном времени запустите фильтр, затем воспроизведите ошибку:

```shell
adb logcat -v threadtime -s 'ExchangeSync:V' '*:S'
```

Для однократного чтения уже удерживаемых записей используйте:

```shell
adb logcat -d -v threadtime -s 'ExchangeSync:V' '*:S'
```

`-c` очищает общий Logcat buffer устройства, поэтому не запускайте эту команду,
если нужны ранее накопленные записи других приложений. Фильтр по tag намеренно
не привязан к PID: так в одном сборе остаются события до и после recreation
процесса. После сбора проверьте корреляцию и отсутствие всех данных из списка
выше, прежде чем передавать лог другому человеку.
