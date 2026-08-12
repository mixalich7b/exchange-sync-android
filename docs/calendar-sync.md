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

Границы remote parsing, Calendar Provider mutations и durable checkpoint
показаны на
[схеме обработки одной страницы календаря](diagrams/calendar-page-processing.puml).

После capability discovery выбирается максимальная общая версия от 14.0 до
16.1. Клиент выполняет `FolderSync`, требует ровно одну папку default Calendar
типа 8, затем создаёт collection partnership через `SyncKey=0` и получает
страницы `Sync` с `GetChanges`. Priming request содержит `SyncKey=0`, а
последующий full sync не содержит `FilterType` или другого date filter:
сохраняется вся история и все будущие события, возвращённые сервером.
Успешный `Sync` с пустым HTTP body означает отсутствие изменений: текущий
collection SyncKey сохраняется, а run завершается без protocol-data ошибки.

WBXML декодируется с ограничениями размера документа, глубины, количества
элементов и размера отдельной inline string; повторяющиеся singleton-поля
отклоняются как malformed data. Ограничения размера WBXML-документа и числа
элементов типизированы отдельно от syntax/encoding/token errors и считаются
page-scaled только для обычной Calendar `Sync` page. Вместе с bounded HTTP body
и слишком большой provider transaction они сохраняют committed checkpoint,
уменьшают window вдвое с минимумом один и повторяют ту же страницу. Каждая
remote page преобразуется в один упорядоченный provider plan и применяется
последовательными вызовами не более чем по 50 операций. Успешная меньшая
страница продолжает `MoreAvailable` pagination уже с уменьшенным window. Если
remote page остаётся больше лимита
при window 1, run блокируется как `PROTOCOL_DATA`; provider batch при window 1
блокируется как `CALENDAR_PROVIDER`. Данные не пропускаются и checkpoint не
продвигается. Excessive depth, oversized отдельная inline string, malformed
syntax/UTF-8/token/structure и те же decoder limits в `FolderSync` не становятся
recoverable от уменьшения Calendar window. HTTP body ограничивается до
выделения полного массива. Для 14.0/14.1 initial и последующие запросы объявляют
`Supported` properties. Для
16.0/16.1 omitted fields в `Change` объединяются только с clean snapshot
собственной строки Calendar Provider; dirty row требует fenced full reset,
чтобы локальная правка не стала новым server value. Explicit empty по-прежнему
очищает поле. Explicit response override recurrence exception сохраняется в
sync metadata exception row и переживает последующий partial `Change`. Structured
`AirSyncBase:Location` и `InstanceId` recurrence exceptions разбираются с учётом
версии.
Для ActiveSync 16.0/16.1 `InstanceId` принимает только Compact DateTime UTC
`yyyyMMdd'T'HHmmss'Z'`. Extended/fractional и malformed значения отклоняют всю
страницу как protocol data, поэтому checkpoint такой страницы не продвигается.
Series-only `ResponseType` change обновляет только response presentation
унаследованных exception rows; их текст, время, attendees, reminders и deleted
state не пересоздаются, а explicit exception override остаётся неизменным.

Exchange Server product build `15.2.x` не является версией ActiveSync
wire-протокола. Такой сервер обслуживается через объявленные `14.0`, `14.1`,
`16.0` или `16.1`; protocol-only значение `15.2` не согласуется.

Persisted checkpoints включают terminal endpoint, protocol version,
FolderSync key, primary collection ID, collection SyncKey и текущий window.
Calendar Provider page применяется до сохранения следующего SyncKey. Provider
plan сохраняет канонический порядок операций; ссылки на insert внутри текущей
группы становятся локальными back-reference, а ссылки на event из уже
подтверждённой группы используют возвращённый provider row ID. Если более
поздний вызов завершился ошибкой или его результат неоднозначен, уже
подтверждённый префикс может быть видим локально, но SyncKey не меняется. Повтор
той же страницы идемпотентно upsert-ит ServerId и полностью заменяет только явно
присутствующие child collections, поэтому сходится без дубликатов.

Capability discovery и все календарные команды точного сохранённого профиля
используют один process-local HTTP-сеанс. Подходящие cookie, установленные
`OPTIONS`, разрешённым HTTPS redirect или командой, доступны последующим
`FolderSync`/`Sync`, retries и continuation slices; другие профили и
неподходящие redirect destinations их не получают. Cookie не сохраняются на
диск. Поэтому после recreation процесса синхронизация сначала выполняет
`OPTIONS`, даже имея persisted checkpoints. Если сохранённая protocol version
ещё предлагается, ключи продолжают использоваться; при смене версии сначала
запрашивается существующий fenced full reset.

Успешно подготовленное состояние основной папки также хранится только в этом
profile session и привязано к protocol version, generation и run token. Один
логический run выполняет `FolderSync` перед первой Calendar page и повторно
использует полученные folder key, primary collection ID и terminal endpoint для
остальных страниц, adaptive window retries и continuation workers того же
fence. Новый run token, другая version/profile, cold process, invalid key,
full reset или неуспешное согласование основной папки требуют refresh либо
очищают это состояние. Durable checkpoint по-прежнему меняется только после
успешного применения Calendar page; process death до commit безопасно повторяет
`FolderSync` от последнего сохранённого key.

Внутри активной синхронизации capability discovery, `FolderSync`, priming и
обычные `Sync` pages используют общий profile-session pacer. Первый top-level
exchange в cold session отправляется сразу; каждый следующий начинается не
раньше чем через две секунды после завершения или transport failure предыдущего.
Время локального применения, continuation и более длинного backoff засчитывается
в интервал и не получает дополнительную задержку. Все разрешённые HTTPS redirect
hops входят в один top-level exchange. Ожидание использует monotonic clock и
coroutine cancellation; непосредственно перед transport dispatch повторно
проверяется generation/run-token fence, поэтому отменённый или устаревший run не
посылает ожидающий запрос. Connection verification вне синхронизации сохраняет
прежнее поведение без этого pacer.

## Представление событий

Переносятся UID/ServerId, время и all-day range, title, body, location,
organizer, attendees, sensitivity, reminder, recurrence и changed/deleted
exceptions. Windows time-zone blob преобразуется в representable Android time
zone; неоднозначные или непредставимые данные блокируют страницу безопасной
категорией protocol/provider problem, не сдвигая identity или время.

Для события с не более чем 100 участниками, не считая organizer, создаётся
полный набор attendee rows. Если таких участников больше 100, они все
опускаются, organizer сохраняется отдельной organizer row, а
`HAS_ATTENDEE_DATA` отражает только её наличие. При отсутствии organizer в
oversized-событии attendee rows не создаются. Правило применяется независимо к
series и каждой recurrence exception после наследования её effective attendee
list; переходы через порог полностью заменяют child collection. ActiveSync
decoder и domain mapper при этом сохраняют весь входной список: ограничение
действует только при материализации Calendar Provider и не влияет на
классификацию приглашения или self-attendee status.

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

У recurrence exception собственный `ResponseType` является authoritative
override. Если он отсутствует, override выводится только из ровно одного
attendee с email текущего профиля и поддерживаемым `AttendeeStatus`.
Отсутствующий status, отсутствие совпадения или несколько совпадений оставляют
exception response как `Absent`: новое occurrence наследует presentation
series, а partial change сохраняет ранее синхронизированный explicit override.
Эта optional inference действует только для exception. Полученная series без
собственного `ResponseType` и без однозначного current-user attendee response
по-прежнему отклоняет всю страницу как `PROTOCOL_DATA`, не продвигая checkpoint.

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
включаются в provider plan, sub-batch или cleanup.

Ровно одна owned calendar row определяется полным внутренним ownership tuple.
Удаление использует collection URI Calendar Provider с sync-adapter query
parameters и повторяет в selection provider `_id`, account name, account type и
internal name; результат проверяется по числу удалённых строк. OEM-календари и,
в частности, строки с `account_name_local` не принимаются за owned calendar, не
ремонтируются и не удаляются.

Provider mutation и generation/run-token invalidation сериализуются общим
mutex на всю Calendar page. Непосредственно перед каждым provider-вызовом
проверяются coroutine cancellation и generation/run-token fence. Текущий
sub-batch атомарен, но вся page не обязана быть атомарной: старый worker не
начинает следующий sub-batch, `resolveOwned` или fenced cleanup после замены
профиля, cancel или disable. Если другая локальная компонента
удалит owned event, календарь целиком или изменит event так, что provider
пометит его dirty, последующая обработка обнаруживает разрыв зеркала,
ограждённо очищает owned calendar и запускает полную синхронизацию для
восстановления серверного представления.

## Фоновые задачи, retry и управление

Общая state machine и взаимодействие с WorkManager вынесены в
[диаграмму состояний синхронизации](diagrams/sync-state-machine.puml) и
[sequence-схему жизненного цикла фоновых workers](diagrams/worker-lifecycle-sequence.puml).

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

Экран показывает phase, последний успешный sync и safe problem category. Для
сохранённого включённого профиля Sync Now доступен в `IDLE` и `BLOCKED`, пока
run не активен. Повтор из `BLOCKED` проходит тот же serialized transition,
очищает только presentation предыдущей попытки и сохраняет committed calendar,
checkpoint и уже установленный full-reset intent. Пока попытка queued/running,
кнопка недоступна; повторная постоянная ошибка снова возвращает `BLOCKED` и
разрешает более позднюю ручную попытку.

Cancel увеличивает run token, сохраняет последний committed checkpoint и не
удаляет зеркало. Disable сначала инвалидирует work, отменяет schedules, затем
очищает только owned calendar. При permission/provider failure синхронизация
остаётся отключённой, а cleanup intent и actionable problem сохраняются в
DataStore. Экран показывает отдельный retry очистки и не предлагает Enable до
её успеха. User retry, startup reconciliation и permission recovery используют
тот же идемпотентный cleanup path без periodic/immediate network scheduling;
успех оставляет профиль сохранённым и синхронизацию отключённой.

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

## Диагностика

Сетевые и protocol failures, отклонённые события, операции Calendar Provider,
sync phases/retries/resets/terminal outcomes и границы WorkManager связываются
в Logcat по generation, run token и process-local operation ID. Безопасные
агрегаты отдельно показывают suppression oversized attendee list и прогресс
provider sub-batches, включая неоднозначный outcome активного вызова. Записи не
содержат event content, WBXML, provider values или profile identity. Полный
перечень безопасных полей и команды сбора приведены в
[руководстве по диагностике](diagnostics.md).

## Проверка

State machine, codecs, mapping, page/batch planning, persistence, WorkManager
policy, notifications и presentation покрываются локальными unit-тестами.
Компиляция, Lint и debug assembly проверяют тонкие Android adapters.
Server-backed checklist успешно пройден вручную на Xiaomi 17 с Android 16 и
реальным Exchange Server: проверены HTTPS/mTLS и ActiveSync, настоящий Calendar
Provider и фактическое 15-минутное исполнение periodic work через WorkManager.
Автоматическая проверка при этом по-прежнему ограничена локальными unit-тестами.
