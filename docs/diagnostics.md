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
- для `Sync` response/page — `sync_mode` (`priming`, `full`, `incremental`),
  `window_size`, `response_bytes`, `response_empty`, `command_count`,
  `add_count`, `change_count`, `delete_count`, `more_available` и
  `key_advanced` без значений ключей;
- для bounded page/provider capacity — `capacity_kind` (`http_response_bytes`,
  `wbxml_document_bytes`, `wbxml_element_count`, `wbxml_depth`,
  `wbxml_inline_string_bytes`, `calendar_provider_transaction`), typed
  `capacity_command`, `capacity_outcome` (`window_reduction`,
  `minimum_window_block`, `terminal`), safe `capacity_problem`, текущий
  `window_size` и `reduced_window_size`, когда уменьшение возможно;
- для подготовки основной папки — `folder_preparation` (`cold_refresh`,
  `refresh`, `reuse`, `invalidated`); успешный `reuse` не сопровождается новым
  successful `FolderSync` request record, а причина refresh фиксируется до
  отдельного command outcome даже при неуспешном запросе;
- для owned-calendar/provider boundaries — `ownership_action` (`created`,
  `reused`, `repaired`, `deleted`, `unchanged`), `input_count`,
  `accepted_count`, `rejected_count`, `planned_operation_count`,
  `attempted_operation_count` и `applied_operation_count`;
- для cleanup — `cleanup_trigger` (`profile_activation`, `full_reset`,
  `disable`, `startup`, `permission_recovery`, `user_retry`), bounded row и
  operation counts, delete outcome и durable failure category;
- для checkpoint boundary — `checkpoint_outcome` (`committed`, `skipped`,
  `failed`);
- bounded exception/cause class graph и ограниченные stack frames; сообщения
  допускаются только на границах, где они могут быть безопасно очищены.

Все новые числовые progress-поля formatter ограничивает диапазоном
`0..1_000_000`. Полная запись ограничена 3000 символами, отдельное очищенное
строковое значение — 256 символами, exception graph — восемью объектами и
четырьмя stack frames на объект. Цикл помечается как `cycle`, остаток за пределом
лимита — как `truncated`.

Обычный путь расследования: найти terminal `failure`/`outcome`, затем собрать
строки с тем же `operation`. Для синхронизации дополнительно сопоставляются
`generation` и `run_token`. Идентификаторы operation начинаются заново после
перезапуска процесса и не являются persisted IDs.

`http_response_bytes`, `wbxml_document_bytes` и `wbxml_element_count` на обычной
Calendar page при window больше одного означают adaptive recovery, а не
malformed server data:
связанные записи показывают старое и уменьшенное window и неизменённый
checkpoint. `minimum_window_block` означает terminal `PROTOCOL_DATA` без
пропуска элемента. `wbxml_depth` и `wbxml_inline_string_bytes` остаются
terminal, а настоящий syntax/encoding/token/structure defect сохраняет
`MALFORMED_WBXML`. `folder_preparation` позволяет отличить cold/new-run refresh,
reuse в page/retry/continuation и invalidation, не раскрывая содержимое cache.
`calendar_provider_transaction` использует тот же typed window outcome, но
сохраняет terminal problem `CALENDAR_PROVIDER` при window один.

Уровень `INFO` отмечает начало, capability/phase и terminal
success/cancellation/obsolete. На нём же пишутся успешные `RESPONSE`, decoded
`CALENDAR_SYNC`, `OWNERSHIP`, `EVENT_MAP`, `PROVIDER_BATCH`, cleanup success и
checkpoint `committed`/`skipped`. `WARN` используется для rejected redirect,
protocol/HTTP validation, invalid event, recoverable retry/reset и checkpoint
`failed`. TLS/mTLS, block, критические локальные и неожиданные ошибки, а также
неуспешный или exception-based cleanup имеют `ERROR`.

## Запрещённые данные

Diagnostics не должны содержать:

- имена, значения или атрибуты cookie и заголовки `Cookie`/`Set-Cookie`;
- `Authorization`, email, `domain\login` или KeyChain alias;
- private keys, PEM/DER и другие raw certificate encodings;
- полный URL, user-info, query string, request/response body или WBXML;
- subject, body, location, attendees, organizer, timestamps collection и иной
  personal event/provider payload;
- значения FolderSync/collection SyncKey, primary collection ID, Calendar
  Provider row ID, account identity и любые timestamp-поля в progress summaries;
- raw exception output.

Progress summaries дополнительно не используют даже допустимый для точечной
ошибки opaque `server_id`: они содержат только перечисленные выше агрегаты,
booleans и enums. `response_bytes` — только ограниченный размер, а
`key_advanced` — только boolean сравнения; ни body, ни предыдущее/следующее
значение ключа в event model не передаются. Exception messages полностью
опускаются для `WBXML`, `FolderSync`, `CalendarSync`, event parse/map, Calendar
Provider и core synchronization stages; там остаются только классы и
ограниченные frames.

Capacity и folder-preparation records имеют более узкий formatter path: кроме
`component`, `stage`, process-local operation ID, `generation`/`run_token` он
принимает только перечисленные typed enums и bounded window values. Свободные
`command`/`reason`/`outcome`, host/path, `server_id` и exception text для этих
records не форматируются. Поэтому collection ID, folder name, FolderSync и
collection SyncKey, email, `domain\login`, payload и текст исключения не могут
попасть в эти записи даже при ошибочном заполнении общего event model.

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
