# Диагностика через Logcat

[Все документы](README.md)

Приложение пишет структурированные записи с тегом `ExchangeSync` в системный
Logcat. Начните со сбора, найдите конечный результат операции, затем переходите
к записям соответствующей подсистемы.

Нормативные сценарии: [diagnostic-logging](../openspec/specs/diagnostic-logging/spec.md).
Точные поля и правила раскрытия:
[справочник диагностики](reference/diagnostic-fields.md).

## Содержание

- [Сбор через ADB](#сбор-через-adb)
- [Корреляция записей](#корреляция-записей)
- [Как читать результат](#как-читать-результат)
- [Ошибка Calendar Provider](#ошибка-calendar-provider)
- [Уровни логирования](#уровни-логирования)
- [Граница реализации](#граница-реализации)

## Сбор через ADB

Убедитесь, что Android 16 device подключено:

```shell
adb devices -l
```

Для чтения уже удерживаемых записей:

```shell
adb logcat -d -v threadtime -s 'ExchangeSync:V' '*:S'
```

Для наблюдения в реальном времени запустите фильтр и воспроизведите ошибку:

```shell
adb logcat -v threadtime -s 'ExchangeSync:V' '*:S'
```

При необходимости чистого воспроизведения можно предварительно очистить буфер.
**Команда ниже очищает общий Logcat устройства**, включая записи других приложений.
Не выполняйте её, если предыдущие записи ещё нужны:

```shell
adb logcat -c
```

Фильтр по tag намеренно не привязан к PID: в сборе остаются записи до и после
пересоздания процесса. Перед передачей логов проверьте корреляцию и соблюдение
[политики раскрытия данных](reference/diagnostic-fields.md#политика-раскрытия-данных).

## Корреляция записей

1. Найдите terminal `failure` / `outcome`.
2. Соберите записи с тем же `operation` внутри этого процесса.
3. Для синхронизации дополнительно сопоставьте `generation` и `run_token`.
4. Для большого snapshot соберите все части `chunk=N/M`.
5. Проверьте результат checkpoint: наличие provider progress ещё не означает commit.

| Поле | Назначение |
|---|---|
| `component`, `stage` | Подсистема и граница операции |
| `operation`, `operation_kind` | Связь шагов внутри процесса |
| `generation`, `run_token` | Поколение и логический sync-run |
| `chunk=N/M` | Порядок частей большого failure snapshot |

Operation IDs начинаются заново после перезапуска процесса и не являются
persisted IDs. Нельзя объединять разные процессы только по совпавшему `operation`.

## Как читать результат

| Сигнал | Значение / следующий шаг |
|---|---|
| `capacity_outcome=window_reduction` | Восстанавливаемое превышение размера: окно уменьшено, checkpoint сохранён |
| `minimum_window_block` | Лимит превышен даже при window 1; элемент не пропущен |
| `MALFORMED_WBXML` | Настоящая syntax/encoding/token/structure ошибка |
| `folder_preparation=reuse` | Подготовка папки переиспользована; нового успешного `FolderSync` request record нет |
| `cold_refresh` / `refresh` / `invalidated` | Причина обновления записывается до command outcome, включая неуспешный refresh |
| `attendee_suppression` | Превышен лимит non-organizer rows; итог `organizer_only` или `empty` |
| `checkpoint_outcome=committed` | Страница полностью применена, checkpoint сохранён |
| `checkpoint_outcome=skipped` / `failed` | Нельзя считать новый checkpoint сохранённым |

`http_response_bytes`, `wbxml_document_bytes` и `wbxml_element_count`
на обычной Calendar page при window > 1 обозначают adaptive recovery.
При window 1 remote capacity блокирует run как `PROTOCOL_DATA`;
`calendar_provider_transaction` — как `CALENDAR_PROVIDER`.
`wbxml_depth` и `wbxml_inline_string_bytes` остаются terminal.

`wbxml_element_count` возникает только при попытке прочитать 256 001-й
элемент. Документ с не более чем 256 000 элементов может пройти обычные decode,
attendee suppression, provider batches и commit.

Полные [лимиты и правила восстановления](calendar-sync.md#ограничения-и-уменьшение-окна),
[правила участников](event-mapping.md#участники).

## Ошибка Calendar Provider

`provider_batch` сначала фиксирует общий размер plan, затем подтверждённые
sub-batches: номер, размер и cumulative число применённых операций.

| Результат | Как интерпретировать |
|---|---|
| Успешный вызов | Только aggregate records |
| Ошибка активного Binder call | Сначала aggregate outcome, затем detail каждой переданной операции; `provider_call_outcome=unknown` |
| Неоднозначный вызов | `applied_operation_count` отсутствует; известный префикс отражён только в `confirmed_operation_count` |
| Ошибка построения request до Binder | `unsubmitted_operation`, без `provider_call_outcome` и без утверждения о попытке записи |

Detail failed call не доказывает, какая операция вызвала ошибку или применилось
ли что-либо внутри неоднозначного вызова. Ранее подтверждённый префикс остаётся
посчитанным. В обоих случаях ошибки checkpoint страницы не считается committed.

Concrete cause ограничен enum: например, `remote`, `operation_application`,
`invalid_result`, `transaction_too_large`. При capacity failure следующая
запись того же generation/run token показывает `window_reduction` либо
`minimum_window_block`; остальные permanent failures связываются с terminal block.

## Уровни логирования

| Уровень | События |
|---|---|
| `INFO` | Начало, capability/phase, terminal success/cancellation/obsolete; успешные RESPONSE, CALENDAR_SYNC, OWNERSHIP, EVENT_MAP, PROVIDER_BATCH, cleanup; checkpoint committed/skipped |
| `WARN` | Rejected redirect, protocol/HTTP validation, invalid event, recoverable retry/reset, checkpoint failed |
| `ERROR` | TLS/mTLS, block, критические локальные и неожиданные ошибки; неуспешный или exception-based cleanup |

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
