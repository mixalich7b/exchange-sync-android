# Архитектура приложения

## Текущая граница

Реализованы настройка единственного профиля Exchange, проверка HTTPS/mTLS и
совместимости ActiveSync, а также одностороннее зеркало основного календаря
Exchange в отдельный локальный календарь Android. Синхронизация переносит
историю и будущие события, напоминания, attendees, recurrence и exceptions.
Непринятые приглашения сохраняются как tentative и получают бледный
event-color override; после принятия та же запись обновляется без смены
ServerId identity и без дубликата.

Ручная и 15-минутная периодическая синхронизация выполняются через WorkManager,
продолжаются без Activity и используют retry/backoff. Постоянные проблемы
сохраняются в DataStore и, при наличии разрешения, показываются одним системным
уведомлением. Реализация не регистрирует Android Exchange account или
SyncAdapter и не отправляет локальные изменения обратно на сервер.

Нормативное поведение описано в
[`openspec/specs/`](../openspec/specs/), прежде всего в спецификациях
[`connection-settings`](../openspec/specs/connection-settings/spec.md),
[`calendar-sync`](../openspec/specs/calendar-sync/spec.md)
и [`project-bootstrap`](../openspec/specs/project-bootstrap/spec.md).

## Модули и зависимости

Разрешённое направление зависимостей:

```text
:app -> :feature:settings -> :core
     -> :infrastructure  -> :core
```

| Модуль | Ответственность |
|---|---|
| `:app` | Application/Activity, manifest, permission и certificate launchers, ресурсы уведомления и ручная композиция |
| `:core` | Android-независимые модели профиля/календаря, sync state machine, fencing, mapping и use cases |
| `:feature:settings` | Immutable UI state, ViewModel, Compose-форма, статус синхронизации и управляющие действия |
| `:infrastructure` | DataStore, KeyChain/TLS, ActiveSync/WBXML, Calendar Provider, WorkManager, permissions и notifications |

`:feature:settings` не зависит от `:infrastructure`, а `:core` остаётся чистым
Kotlin/JVM-модулем без Android и HTTP API. Такое разделение оставляет доменную
политику и переходы состояния доступными для локальных JVM unit-тестов.

## Композиция приложения

`ExchangeSyncApplication` создаёт один `AppContainer` на процесс, а контейнер
вручную связывает:

- общий Preferences DataStore для профиля и sync metadata;
- один process-wide ActiveSync runtime для проверки и календарных команд с
  profile-scoped cookie/capability sessions;
- owned-only Calendar Provider adapter;
- WorkManager scheduler и ручной `WorkerFactory`;
- permission port и generation-aware notification reporter;
- Logcat-backed diagnostics adapter и Android-free `SyncDiagnosticsPort`;
- один core-сценарий `VerifyConnection`, используемый и `SaveConnection`, и
  ручной повторной проверкой;
- core-сценарии `SaveConnection`, lifecycle, manual/periodic trigger и bounded
  execution slice;
- `SettingsViewModel` через lifecycle-aware `ViewModelProvider`.

Dependency-injection framework не используется. Android KeyChain chooser
остаётся на уровне Activity, поэтому feature-модуль получает только callback и
alias выбранного сертификата.

## Потоки проверки профиля

Сохранение реализовано как validate-probe-commit:

1. Compose-экран передаёт текущий draft во ViewModel.
2. Core валидирует все четыре значения без сетевого доступа.
3. Infrastructure разрешает KeyChain alias в закрытый ключ и цепочку
   сертификатов только на время проверки.
4. Infrastructure создаёт объединённый TLS-контекст и выполняет ActiveSync
   `OPTIONS` probe.
5. После полного успеха profile replacement и новая synchronization generation
   фиксируются одной DataStore-транзакцией.
6. В non-cancellable post-commit handoff очищается только owned calendar,
   восстанавливаются periodic/immediate work и запускается полный sync.
7. ViewModel показывает подключённое состояние либо сохраняет введённый draft
   и отображает типизированную ошибку.

TLS transport предоставляет только выбранную fixed client identity. Успешный
проверенный response не зависит от того, публикует ли Android provider локальную
цепочку через handshake metadata; опубликованное несовпадение при этом
отклоняется. Redirects для `OPTIONS` и ActiveSync `POST` выполняет общий
application-controlled tracker при отключённых automatic redirects OkHttp, так
что HTTPS-only, cycle/five-hop policy и сохранение method/body едины на обоих
путях.

Редактирование формы и выбор сертификата сами по себе не запускают сеть и не
изменяют сохранённый профиль. Повторный Save блокируется до завершения текущей
проверки.

Для неизменённого загруженного профиля ViewModel также запускает общий
`VerifyConnection` без `SaveConnection`: результат проходит те же validation,
mTLS, TLS, redirect и ActiveSync checks, но не вызывает repository replacement.
После успеха ViewModel показывает terminal TLS certificate diagnostics; после
изменения формы или неуспешной попытки эти diagnostics очищаются и не
persistятся.

## Android и конкурентные границы

Android API сосредоточены в `:app` и `:infrastructure`. Начальная загрузка
профиля представлена отдельным состоянием: до её завершения поля, certificate
chooser и Save заблокированы, поэтому позднее чтение DataStore не может
перезаписать пользовательский draft.

ViewModel хранит private snapshot последнего загруженного или успешно
сохранённого профиля. Только равный ему draft может пройти ручную повторную
проверку. Save и повторная проверка представлены одним operation state, поэтому
во время любой проверки заблокированы все поля, chooser и оба действия; поздний
результат не может быть показан для другого draft.

Provider mutations и изменения generation/run token сериализуются общим
`SynchronizationMutationLock`. Поэтому старый worker либо завершает атомарный
Calendar Provider batch до profile/cancel/disable fence, либо после fence
становится obsolete до `resolveOwned`, cleanup или `applyBatch`. Calendar page
фиксируется provider-first; новый SyncKey сохраняется только после успешного
batch, поэтому повтор после crash идемпотентен по ServerId.

Получение материала из KeyChain и создание TLS transport выполняются вне Main
dispatcher. Создание trust managers, `SSLContext` и OkHttp-клиента синхронно и
не получает отдельный жёсткий deadline. Если security provider блокируется,
Save может оставаться активным дольше номинального timeout; это принятый
trade-off текущей реализации.

Созданный один раз на процесс ActiveSync runtime разделяет только внутри точной
profile identity потокобезопасный cookie jar и live capability result. Реестр
ограничен четырьмя LRU entries. Новые verifier/remote-calendar transports для
того же профиля получают этот сеанс, но продолжают создавать TLS client с
выбранной mTLS identity и объединённым server trust. После process death сеанс
пуст: перед календарной командой выполняется новый `OPTIONS`; persisted protocol
version сохраняется только если остаётся в свежем advertised set.

## Хранение данных

Preferences DataStore содержит ровно один профиль:

- email;
- имя в формате `domain\login`;
- hostname сервера;
- непрозрачный KeyChain alias.

В том же DataStore, под отдельным `sync.` namespace, находятся non-secret
generation/run token, phase, safe problem category, device ID, last-success и
ActiveSync endpoint/version/folder/collection checkpoints. Пароль, закрытый
ключ, байты сертификата, ответы сервера, event payload, exception text,
TLS-диагностика и stack trace не сохраняются. Успешная TLS-диагностика содержит только public metadata
terminal peer chain: hostname, subject/issuer, serial, validity и SHA-256
fingerprint. Android продолжает владеть закрытым ключом, а backup приложения
отключён.

Process-local cookie/capability sessions и структурированные diagnostic records
также не сохраняются в DataStore. Diagnostics идут только в системный Logcat с
тегом `ExchangeSync`; приложение не создаёт архив, экран просмотра или канал
upload. Корреляция сетевых, protocol, provider и worker boundaries выполняется
process-local operation ID и, для sync, generation/run token. Безопасные поля и
ADB-команды описаны в [диагностике](diagnostics.md).

## Проверка реализации

Автоматическая граница ограничена JVM/Android-local unit-тестами без Robolectric
и instrumentation. Pure policy, persistence codec, WBXML/ActiveSync fixtures,
mapping, provider batch planning, worker policy, TLS, notifications и ViewModel
проверяются с fakes. Android KeyChain, настоящий Calendar Provider, WorkManager
runtime и живой Exchange/mTLS server остаются тонкими интеграционными границами:
код для них проверяется компиляцией, Android Lint и debug-сборкой. Полный
server-backed checklist должен отдельно выполняться вручную на Android 16; эта
документация не утверждает, что такой прогон уже состоялся.

Актуальные команды находятся в [`AGENTS.md`](../AGENTS.md).
