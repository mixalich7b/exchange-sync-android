# Архитектура приложения

## Текущая граница

Сейчас реализованы настройка единственного профиля Exchange и проверка
подключения. Приложение собирает параметры, выбирает установленный в Android
клиентский сертификат, проверяет HTTPS, mTLS и совместимость ActiveSync, после
чего сохраняет подтверждённый профиль.

Чтение календаря с Exchange, запись через Android Calendar Provider, фоновая
синхронизация, перенос напоминаний и системные уведомления не реализованы. Они
остаются границей последующих OpenSpec changes.

Нормативное поведение описано в
[`openspec/specs/`](../openspec/specs/), прежде всего в спецификациях
[`connection-settings`](../openspec/specs/connection-settings/spec.md) и
[`project-bootstrap`](../openspec/specs/project-bootstrap/spec.md).

## Модули и зависимости

Разрешённое направление зависимостей:

```text
:app -> :feature:settings -> :core
     -> :infrastructure  -> :core
```

| Модуль | Ответственность |
|---|---|
| `:app` | Launcher Activity, manifest, Android certificate chooser и ручная композиция зависимостей |
| `:core` | Android-независимые модели профиля, валидация, категории ошибок, общая проверка draft и сценарий сохранения |
| `:feature:settings` | Immutable UI state, ViewModel, Compose-экран, повторная проверка и отображение типизированных результатов |
| `:infrastructure` | DataStore, Android KeyChain, загрузка локальных CA, TLS trust managers и ActiveSync HTTP probe с TLS-метаданными |

`:feature:settings` не зависит от `:infrastructure`, а `:core` остаётся чистым
Kotlin/JVM-модулем без Android и HTTP API. Такое разделение оставляет доменную
политику и переходы состояния доступными для локальных JVM unit-тестов.

## Композиция приложения

`MainActivity` создаёт `AppContainer`, а контейнер вручную связывает:

- единственный `DataStoreConnectionProfileRepository`;
- Android-адаптер проверки ActiveSync;
- один core-сценарий `VerifyConnection`, используемый и `SaveConnection`, и
  ручной повторной проверкой;
- core-сценарий `SaveConnection`;
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
5. После полного успеха core одним вызовом заменяет единственный профиль в
   DataStore.
6. ViewModel показывает подключённое состояние либо сохраняет введённый draft
   и отображает типизированную ошибку.

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

Получение материала из KeyChain и создание TLS transport выполняются вне Main
dispatcher. Создание trust managers, `SSLContext` и OkHttp-клиента синхронно и
не получает отдельный жёсткий deadline. Если security provider блокируется,
Save может оставаться активным дольше номинального timeout; это принятый
trade-off текущей реализации.

## Хранение данных

Preferences DataStore содержит ровно один профиль:

- email;
- имя в формате `domain\login`;
- hostname сервера;
- непрозрачный KeyChain alias.

Пароль, закрытый ключ, байты сертификата, ответы сервера, TLS-диагностика и
ошибки не сохраняются. Успешная TLS-диагностика содержит только public metadata
terminal peer chain: hostname, subject/issuer, serial, validity и SHA-256
fingerprint. Android продолжает владеть закрытым ключом, а backup приложения
отключён.

## Проверка реализации

Автоматическая граница ограничена JVM unit-тестами. Pure policy, persistence
codec, TLS-композиция, ActiveSync policy, классификация ошибок и ViewModel
проверяются с fakes. Android KeyChain, реальный TLS provider и сервер остаются
тонкими интеграционными границами, которые подтверждаются компиляцией, Android
Lint, debug-сборкой и при необходимости ручной проверкой на Android 16.

Актуальные команды находятся в [`AGENTS.md`](../AGENTS.md).
