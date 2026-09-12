# Справочник диагностических полей

[Все документы](../README.md)

Практические команды и интерпретация результатов:
[диагностика](../diagnostics.md). Нормативные сценарии:
[diagnostic-logging](../../openspec/specs/diagnostic-logging/spec.md).

## Содержание

- [Общие поля](#общие-поля)
- [Страницы и capacity](#страницы-и-capacity)
- [Provider и cleanup](#provider-и-cleanup)
- [Snapshots при ошибках](#snapshots-при-ошибках)
- [Ограничения формата](#ограничения-формата)
- [Политика раскрытия данных](#политика-раскрытия-данных)
- [Запрещённые данные](#запрещённые-данные)
- [Узкие пути форматирования](#узкие-пути-форматирования)

## Общие поля

Каждая строка начинается с разрешённых `component` и `stage`.
Остальные поля зависят от границы операции.

| Группа | Поля и условия |
|---|---|
| Корреляция | `operation` и `operation_kind` — уникальная в текущем процессе связь шагов. |
| Sync / worker | `generation`, `run_token`, `trigger`, `phase` и `attempt` для sync/worker. |
| HTTP | HTTP `method`, ActiveSync `command`, `host` и `path` без query, `status` и `timeout_ms`. |
| Протокол и результат | безопасные protocol versions/commands, `reason`, `failure` и `outcome`. |
| Отклонённое событие | opaque `server_id` для отклонённого события. |
| Сертификаты | local-CA filename, длину certificate chain, алгоритм публичного ключа и SHA-256 fingerprint, когда эти metadata доступны. |

## Страницы и capacity

| Группа | Поля и условия |
|---|---|
| Sync page | для `Sync` response/page — `sync_mode` (`priming`, `full`, `incremental`), `window_size`, `response_bytes`, `response_empty`, `command_count`, `add_count`, `change_count`, `delete_count`, `more_available` и `key_advanced` без значений ключей. |
| Подготовка папки | для подготовки основной папки — `folder_preparation` (`cold_refresh`, `refresh`, `reuse`, `invalidated`); успешный `reuse` не сопровождается новым successful `FolderSync` request record, а причина refresh фиксируется до отдельного command outcome даже при неуспешном запросе. |
| Checkpoint | для checkpoint boundary — `checkpoint_outcome` (`committed`, `skipped`, `failed`). |

| Поле capacity | Значения |
|---|---|
| `capacity_kind` | `http_response_bytes`, `wbxml_document_bytes`, `wbxml_element_count`, `wbxml_depth`, `wbxml_inline_string_bytes`, `calendar_provider_transaction` |
| `capacity_command` | Типизированная команда |
| `capacity_outcome` | `window_reduction`, `minimum_window_block`, `terminal` |
| `capacity_problem` | Безопасная категория проблемы |
| `window_size`, `reduced_window_size` | Текущее окно и уменьшенное, когда уменьшение возможно |

## Provider и cleanup

| Группа | Поля и условия |
|---|---|
| Ownership / planning | для owned-calendar/provider boundaries — `ownership_action` (`created`, `reused`, `repaired`, `deleted`, `unchanged`), `input_count`, `accepted_count`, `rejected_count`, `planned_operation_count`, `attempted_operation_count` и `applied_operation_count`. |
| Подавление участников | для attendee suppression — `attendee_limit`, `attendee_input_count`, `attendee_omitted_count` и безопасное `attendee_representation` (`organizer_only`, `empty`). |
| Page / sub-batch | для provider page/sub-batch — `provider_operation_count`, `sub_batch_count`, `sub_batch_ordinal`, `sub_batch_operation_count`, cumulative `confirmed_operation_count` и `provider_call_outcome` (`confirmed`, `unknown`), а для ошибки — типизированный `provider_failure_cause` без текста исключения. |
| Cleanup | для cleanup — `cleanup_trigger` (`profile_activation`, `full_reset`, `disable`, `startup`, `permission_recovery`, `user_retry`), bounded row и operation counts, delete outcome и durable failure category. |

## Snapshots при ошибках

### Отклонённое событие

Только при отклонении calendar Add/Change или локального представления — `snapshot=calendar_failure`, command kind, opaque `server_id`, стабильный validation/planning `rule`, `failed_field`, event/exception path, безопасный attendee index и типизированные поля со source `response`, `prior`, `effective`, `exception` или `derived`; attendee subfields сохраняют только presence, а число совпадений с текущим пользователем — только bounded count.

### Ошибка активного provider call

Только после ошибки активного Calendar Provider call — `snapshot=provider_operation` для каждой attempted операции: global/local index, operation kind, target, существующая row/back-reference/sync identity, состояния известных columns и разрешённые политикой значения.

### Ошибка до отправки

Ошибка построения provider request до `applyBatch` использует те же безопасные snapshots как `unsubmitted_operation`, но не выставляет `provider_call_outcome` и не называет операцию attempted.

Для исключений доступен ограниченный граф классов exception/cause и stack
frames. Сообщения допускаются только на границах, где их можно безопасно очистить.

## Ограничения формата

Большой failure snapshot разбивается на детерминированные записи с тем же
`operation`, `generation`, `run_token` и ordinal `chunk=N/M`.
Каждая разрешённая field/column detail сохраняется ровно в одном chunk.

| Объект | Предел |
|---|---|
| Числовые progress-поля | `0..1_000_000` |
| Полная запись | 3000 символов |
| Очищенное строковое значение | 256 символов |
| Exception graph | 8 объектов |
| Обход / ожидающие объекты graph | 32 |
| Stack frames | 4 на объект |
| Итоговое представление graph | 1024 символа |

Цикл помечается `cycle`, остаток за пределом лимита — `truncated`.

## Политика раскрытия данных

Подробные calendar/provider values появляются только при соответствующей ошибке.
Успешные parse/map/planning/provider операции дают только агрегаты.

| Данные | Разрешённое представление в failure snapshot |
|---|---|
| UID/protocol identifiers, location, время, duration/relationship, all-day, timezone, recurrence, exception identity, non-narrative metadata | Полное очищенное значение |
| Meeting/response state, availability, sensitivity, reminder, provider row/back-reference identity, технические provider columns | Полное очищенное значение |
| Subject/title, body/description, attendees, organizer | Только `absent` / `empty` / `present` и bounded count; без submitted значений |
| Неизвестный тип provider value | Стабильное имя типа, без произвольного `toString()` |
| Неизвестная provider column | Анонимная structural presence под ключом `<unknown>`, без исходного имени и значения |
| Raw WBXML/application tree, event export и другие payload containers | Не передаются из boundary projector в event model или throwable graph |

Completeness-проверка требует явной policy-классификации каждого нового
`CalendarProviderField`.

Каждое разрешённое строковое значение всё равно проходит общий sanitizer:
email/account/header редактируется, абсолютный URI с корректным `scheme:`
заменяется целиком, query component удаляется, control characters очищаются, а
длина ограничивается до chunking. Канонический fixed-offset provider timezone
вида `GMT±HH:MM` сохраняется как разрешённое timezone value и не считается URI.

## Запрещённые данные

- имена, значения или атрибуты cookie и заголовки `Cookie`/`Set-Cookie`;
- `Authorization`, email, `domain\login` или KeyChain alias;
- private keys, PEM/DER и другие raw certificate encodings;
- полный URL, user-info, query string, request/response body, WBXML или raw
  application/event payload;
- значения subject/title и body/description, значения attendees и organizer;
- значения FolderSync/collection SyncKey, primary collection ID, account
  identity и любые calendar/provider values в progress summaries;
- raw exception output.

Progress summaries дополнительно не используют даже допустимый для точечной
ошибки opaque `server_id`: они содержат только перечисленные выше агрегаты,
booleans и enums. `response_bytes` — только ограниченный размер, а
`key_advanced` — только boolean сравнения; ни body, ни предыдущее/следующее
значение ключа в event model не передаются. Exception messages полностью
опускаются для `WBXML`, `FolderSync`, `CalendarSync`, event parse/map, Calendar
Provider и core synchronization stages; там остаются только классы и
ограниченные frames.

## Узкие пути форматирования

Capacity, folder-preparation и attendee-suppression records
имеют более узкий formatter path: кроме `component`, `stage`, process-local
operation ID, `generation`/`run_token` он принимает только соответствующие
typed enums, bounded counts/window values и allow-listed failure/outcome. Свободные
`command`/`reason`, host/path, `server_id`, provider row ID и exception text для этих
records не форматируются. Поэтому collection ID, folder name, FolderSync и
collection SyncKey, email, `domain\login`, payload и текст исключения не могут
попасть в эти записи даже при ошибочном заполнении общего event model.

Call sites передают только типизированные разрешённые поля. Дополнительный
централизованный formatter ограничивает длину и глубину exception graph,
обрывает циклы, удаляет control characters, query/user-info, header-like
credentials и account-like text. Cookie session также не предоставляет свои
данные diagnostics.

Failure-only formatter paths не расширяют доступ к collection commands:
FolderSync key, primary collection ID, collection SyncKey и payload команд
`FolderSync`/`Sync` остаются исключёнными. Calendar application command kind
ограничен enum `add`/`change`; provider detail использует только перечисленные
operation kinds и column policies.
