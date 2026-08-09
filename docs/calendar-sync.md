# Синхронизация календаря

## Граница функции

Приложение поддерживает одно одностороннее зеркало основного календаря
Exchange в один принадлежащий приложению календарь Android. Сервер является
источником истины: приложение не публикует локальные изменения, ответы на
приглашения или другие calendar commands обратно в Exchange. Профиль при
отключении синхронизации сохраняется.

Нормативные сценарии находятся в основной спецификации
[`calendar-sync`](../openspec/specs/calendar-sync/spec.md).

## ActiveSync flow

После capability discovery выбирается максимальная общая версия от 14.0 до
16.1. Клиент выполняет `FolderSync`, требует ровно одну папку default Calendar
типа 8, затем создаёт collection partnership через `SyncKey=0` и получает
страницы `Sync` с `GetChanges`. Date filter не используется: сохраняется вся
история и все будущие события, возвращённые сервером.
Успешный `Sync` с пустым HTTP body означает отсутствие изменений: текущий
collection SyncKey сохраняется, а run завершается без protocol-data ошибки.

WBXML декодируется с ограничениями размера, глубины, количества элементов и
inline strings; повторяющиеся singleton-поля отклоняются как malformed data.
HTTP body ограничивается до выделения полного массива. Если
страница `Sync` превышает этот предел, неизменённый checkpoint повторяется с
уменьшенным window так же, как после слишком большого provider batch. Для
14.0/14.1 initial и последующие запросы объявляют `Supported` properties. Для
16.0/16.1 omitted fields в `Change` объединяются только с clean snapshot
собственной строки Calendar Provider; dirty row требует fenced full reset,
чтобы локальная правка не стала новым server value. Explicit empty по-прежнему
очищает поле. Explicit response override recurrence exception сохраняется в
sync metadata exception row и переживает последующий partial `Change`. Structured
`AirSyncBase:Location` и `InstanceId` recurrence exceptions разбираются с учётом
версии.
Series-only `ResponseType` change обновляет только response presentation
унаследованных exception rows; их текст, время, attendees, reminders и deleted
state не пересоздаются, а explicit exception override остаётся неизменным.

Persisted checkpoints включают terminal endpoint, protocol version,
FolderSync key, primary collection ID, collection SyncKey и текущий window.
Calendar Provider page применяется до сохранения следующего SyncKey. Повтор той
же страницы идемпотентно upsert-ит ServerId и полностью заменяет только явно
присутствующие child collections.

## Представление событий

Переносятся UID/ServerId, время и all-day range, title, body, location,
organizer, attendees, sensitivity, reminder, recurrence и changed/deleted
exceptions. Windows time-zone blob преобразуется в representable Android time
zone; неоднозначные или непредставимые данные блокируют страницу безопасной
категорией protocol/provider problem, не сдвигая identity или время.

Для приглашений authoritative `ResponseType` имеет приоритет; при его
отсутствии допустим однозначный status attendee, соответствующего email
профиля. Непринятое или tentative приглашение записывается с tentative
`STATUS`, `AVAILABILITY` и self-attendee status и получает opaque цвет,
смешанный на 45% с белым. Исходная серверная availability сохраняется отдельно.
После accepted/organizer transition та же строка становится confirmed,
возвращает server availability и очищает event-color override. Declined и
cancelled состояния отображаются как cancelled. Эти правила применяются также
к attendee-only partial changes с ghosted meeting fields и к recurrence
exceptions с наследованием отсутствующих series fields.

## Изоляция Calendar Provider

Ownership определяется постоянными account name/type и внутренним именем, а не
display name или email пользователя. Calendar создаётся как local,
read-only/visible и изменяется только через sync-adapter-qualified URI.
Разрешение owned calendar ремонтирует дубликаты и воссоздаёт отсутствующую
строку. Новая строка может принять данные только до первой страницы full sync.
Если календарь исчез после committed full-sync page или во время incremental
run, adapter запрашивает fenced full reset и сброс checkpoint вместо применения
следующей страницы. Все event, attendee, reminder и exception operations содержат owned
calendar/event predicates; чужие календари не сканируются для ownership и не
включаются в batch или cleanup.

Provider mutation и generation/run-token invalidation сериализуются общим
mutex. Старый worker не может выполнить `resolveOwned`, batch или fenced cleanup
после замены профиля, cancel или disable. Если другая локальная компонента
удалит owned event, календарь целиком или изменит event так, что provider
пометит его dirty, последующая обработка обнаруживает разрыв зеркала,
ограждённо очищает owned calendar и запускает полную синхронизацию для
восстановления серверного представления.

## Фоновые задачи, retry и управление

WorkManager поддерживает одну network-constrained periodic work с интервалом 15
минут и одну unique execution chain. Profile Save, Enable и Sync Now создают
immediate execution; пагинация и slice limits добавляют continuation. Duplicate
triggers coalesce, а persisted `QUEUED` state повторно reconciles unique work
после process recreation. WorkManager operations дожидаются durable результата
enqueue. Merged/packaged manifest явно удаляет добавляемые WorkManager
`SystemForegroundService` и `FOREGROUND_SERVICE`: execution остаётся обычной
bounded worker chain без foreground path.

Transient failures используют exponential backoff с началом 30 секунд. После
пяти последовательных попыток сохраняется `TRANSIENT_EXHAUSTED`; следующий
период может начать новую попытку. Non-retryable TLS, certificate, access,
provisioning, primary-calendar, protocol и provider problems автоматически
каждые 15 минут не перезапускаются. Invalid SyncKey разрешает один fenced full
reset; Calendar Provider cleanup должен завершиться до публикации пустого
checkpoint и до нового сетевого запроса.

Экран показывает phase, последний успешный sync и safe problem category. Он
предоставляет Sync Now, Cancel, Disable и Enable. Cancel увеличивает run token и
сохраняет последний committed checkpoint. Disable сначала инвалидирует work,
отменяет schedules, затем очищает только owned calendar; при отозванном
разрешении cleanup остаётся persisted pending и продолжается после grant.

## Разрешения и уведомления

Для Calendar Provider запрашиваются `READ_CALENDAR` и `WRITE_CALENDAR`. При
первом profile activation/re-enable blocked generation Activity автоматически
открывает системный permission dialog один раз; ручное действие остаётся на
экране. Generation уже выполненного автоматического запроса сохраняется в
instance state Activity, поэтому rotation или system recreation не открывает
диалог повторно для той же generation. Пока Android показывает rationale,
ручное действие повторяет runtime request; после permanent denial оно открывает
application permission settings и повторно проверяет доступ при возвращении.
Разрешение также проверяется до сети и на provider boundary.

`POST_NOTIFICATIONS` не блокирует синхронизацию. При разрешении одна ongoing
notification со стабильным ID показывает локализованную безопасную категорию
постоянной проблемы и ведёт в настройки приложения. Reporter сверяет generation
и persisted problem перед post/clear, поэтому поздний worker не затрагивает
уведомление новой generation. Endpoint, login, response/event content,
certificate material и exception messages в notification не попадают.

## Проверка

State machine, codecs, mapping, page/batch planning, persistence, WorkManager
policy, notifications и presentation покрываются локальными unit-тестами.
Компиляция, Lint и debug assembly проверяют тонкие Android adapters. Живой
Exchange/mTLS server, настоящий Calendar Provider и фактическое 15-минутное
исполнение WorkManager требуют отдельного ручного прогона на Android 16. До
такого прогона документация не считает server-backed checklist подтверждённым.
