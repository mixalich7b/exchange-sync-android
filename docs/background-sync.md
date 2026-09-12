# Фоновая синхронизация и управление

[Все документы](README.md)

WorkManager выполняет синхронизацию независимо от Activity, Compose и
ViewModel. Закрытие или сворачивание UI не отменяет работу.

Нормативные сценарии:
[sync-scheduling](../openspec/specs/sync-scheduling/spec.md) и
[sync-problem-notifications](../openspec/specs/sync-problem-notifications/spec.md).

## Содержание

- [Запуск и продолжение](#запуск-и-продолжение)
- [Состояния и действия](#состояния-и-действия)
- [Ошибки и повторы](#ошибки-и-повторы)
- [Отключение и очистка](#отключение-и-очистка)
- [Разрешения](#разрешения)
- [Уведомление о проблеме](#уведомление-о-проблеме)

## Запуск и продолжение

![Запуск и продолжение фоновой работы](diagrams/worker-lifecycle-sequence.svg)

| Настройка | Реализация |
|---|---|
| Периодическая работа | Одна unique periodic work, 15 минут, требуется сеть |
| Выполнение | Одна unique execution chain |
| Немедленный запуск | Новый/изменённый Profile Save, Enable, Sync Now |
| Продолжение | Pagination и slice limits создают continuation |
| Дублирующий trigger | Coalesce; параллельный run не создаётся, сохраняется потребность в follow-up |
| Пересоздание процесса | Активная phase нормализуется в `QUEUED`; reconciliation восстанавливает unique work |
| Enqueue | Ожидается durable результат WorkManager operation |

После успешной страницы, если остаются изменения, проверяются лимиты slice:
десять страниц или четыре минуты. При достижении любого лимита создаётся
continuation с тем же generation/run token. Это не жёсткий deadline:
обработка текущей страницы может занять больше четырёх минут. Android может задерживать
15-минутную periodic work из-за Doze, battery optimization, quota или сети;
точное время запуска не обещается.

В merged/packaged manifest явно удалены добавляемые WorkManager
`SystemForegroundService` и `FOREGROUND_SERVICE`. Работа остаётся обычной
ограниченной worker chain без foreground path.

## Состояния и действия

![Состояния синхронизации и переходы между ними](diagrams/sync-state-machine.svg)

Экран показывает phase, последний успешный sync и безопасную категорию проблемы.

| Действие | Условия | Календарь и checkpoint | Фоновая работа |
|---|---|---|---|
| Sync Now | Сохранённый включённый профиль, `IDLE` или `BLOCKED`, run не активен | Сохраняются; уже установленный full-reset intent остаётся | Новая serialized попытка |
| Cancel | Queued/running | Последний committed checkpoint и зеркало сохраняются | Run token увеличивается; текущая execution отменяется, periodic остаётся |
| Disable | Синхронизация включена | Checkpoints очищаются; owned calendar удаляется | Работа инвалидируется, immediate и periodic отменяются |
| Enable | Сохранённый профиль, отключено, pending cleanup устранён | Новый full sync | Новая generation, immediate и periodic |
| Повтор очистки | Отключено, cleanup pending | Повтор удаления owned calendar | Сеть и schedules не включаются |

Sync Now из `BLOCKED` очищает только presentation прежней попытки и проходит
тот же serialized transition. Пока run queued/running, кнопка недоступна.
Повторная постоянная ошибка возвращает `BLOCKED` и разрешает более позднюю
ручную попытку.

Cancel не прерывает текущую атомарную границу. Проверки
[fence](calendar-provider.md#конкурентный-доступ) не допускают новых побочных
эффектов старого worker.

## Ошибки и повторы

| Ошибка | Поведение |
|---|---|
| Временная сеть, timeout, DNS, I/O, HTTP 408/429/5xx, Android interruption | Exponential backoff с началом 30 секунд; committed checkpoint сохраняется |
| Пять последовательных transient failures | `TRANSIENT_EXHAUSTED`; текущий run прекращает retry, следующий periodic может попробовать снова |
| TLS, certificate, access, provisioning, primary-calendar, постоянная protocol/provider problem | Блокировка без автоматического повтора каждые 15 минут |
| Invalid SyncKey | Один fenced full reset; cleanup до публикации пустого checkpoint и нового запроса |

После успешного завершения run счётчик последовательных ошибок и прежняя
проблема очищаются. Категории логов и причины восстановления:
[диагностика](diagnostics.md).

## Отключение и очистка

![Отмена запуска, отключение и повтор очистки](diagrams/worker-stop-sequence.svg)

Disable сначала инвалидирует работу, затем отменяет schedules и очищает только
owned calendar. Профиль сохраняется.

При permission/provider failure синхронизация остаётся отключённой;
cleanup intent и actionable problem сохраняются в DataStore. UI предлагает
отдельный retry очистки и не предлагает Enable до успеха.

User retry, startup reconciliation и permission recovery используют один
идемпотентный cleanup path без periodic/immediate network scheduling.
Успех оставляет профиль сохранённым и синхронизацию отключённой.

## Разрешения

| Разрешение | Если отсутствует |
|---|---|
| `READ_CALENDAR` и `WRITE_CALENDAR` | Sync блокируется до сети и provider mutations; проверенный профиль сохраняется |
| `POST_NOTIFICATIONS` | Sync продолжается; проблема видна в UI, системное уведомление не публикуется |

При первом profile activation/re-enable blocked generation Activity
автоматически открывает calendar permission dialog один раз. Generation запроса
хранится в instance state Activity, поэтому rotation и system recreation не
открывают диалог повторно для той же generation.

Пока Android показывает rationale, ручное действие повторяет runtime request.
После permanent denial оно открывает application permission settings и
повторно проверяет доступ при возвращении. Разрешения проверяются до сети и на
provider boundary. Выдача доступа позволяет продолжить pending full sync без
повторного Save.

При отказе в уведомлениях UI объясняет отсутствие фоновых alerts и позволяет
открыть системные настройки.

## Уведомление о проблеме

Одна ongoing notification со стабильным ID показывает локализованную безопасную
категорию постоянной проблемы и ведёт в настройки приложения.
Reporter сверяет generation и persisted problem перед post/clear:
поздний worker не затрагивает уведомление новой generation.

Успешная синхронизация очищает проблему и уведомление. При смене профиля или
отключении убирается уведомление прежней generation.

Endpoint, login, response/event content, certificate material и exception
messages в notification не попадают.
