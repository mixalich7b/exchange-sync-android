# Синхронизация календаря

[Все документы](README.md)

Приложение переносит основной календарь Exchange в один собственный календарь
Android. Источник истины — сервер: локальные изменения и ответы на приглашения
обратно не отправляются. Отключение синхронизации сохраняет профиль.

Нормативные сценарии: [calendar-sync](../openspec/specs/calendar-sync/spec.md).
[Представление событий](event-mapping.md), [запись в Provider](calendar-provider.md)
и [фоновое выполнение](background-sync.md) описаны отдельно.

## Содержание

- [Полная и incremental синхронизация](#полная-и-incremental-синхронизация)
- [Страница и checkpoint](#страница-и-checkpoint)
- [Ограничения и уменьшение окна](#ограничения-и-уменьшение-окна)
- [Сеанс, папка и интервал запросов](#сеанс-папка-и-интервал-запросов)
- [Восстановление](#восстановление)

## Полная и incremental синхронизация

| Шаг | Что делает клиент |
|---|---|
| Capability discovery | Выбирает максимальную общую версию из 14.0, 14.1, 16.0, 16.1 |
| `FolderSync` | Требует ровно одну default Calendar папку типа 8 |
| Priming | Создаёт collection partnership запросом `SyncKey=0` |
| Full sync | Получает `Sync` pages с `GetChanges`, без `FilterType` и других фильтров дат |
| Pagination | При `MoreAvailable` запрашивает следующую страницу |
| Incremental sync | Продолжает от сохранённого collection SyncKey |

Сохраняются вся история и все будущие события, которые возвращает сервер.
Успешный `Sync` с пустым HTTP body означает отсутствие изменений: текущий
collection SyncKey сохраняется, run завершается без protocol-data ошибки.

Различия полей ActiveSync 14.x и 16.x описаны в
[partial Change](event-mapping.md#частичные-изменения).
Номер продукта Exchange `15.2.x` не является версией wire-протокола:
[согласование версий](transport.md#проверка-возможностей-activesync).

## Страница и checkpoint

![Получение страницы, атомарные пакеты записи и commit checkpoint](diagrams/calendar-page-processing.svg)

Checkpoint содержит terminal endpoint, protocol version, FolderSync key,
primary collection ID, collection SyncKey и текущее окно (`window`).

Порядок: декодировать страницу → построить план → применить все sub-batches →
сохранить следующий checkpoint. Новый SyncKey сохраняется только после полного
успеха записи и проверки актуальности fence.

Один provider-вызов атомарен; вся страница может состоять из нескольких вызовов.
После поздней или неоднозначной ошибки подтверждённый префикс может быть виден
локально, но checkpoint не продвигается. Повтор страницы идемпотентен по ServerId.
Подробности ссылок между пакетами и повторов:
[применение плана](calendar-provider.md#план-и-пакеты-записи).

## Ограничения и уменьшение окна

WBXML декодируется целиком в ограниченное дерево элементов. HTTP body
ограничивается до выделения полного массива. Повторяющиеся singleton-поля
считаются malformed data.

| Ограничение | Предел | Можно уменьшить Calendar window |
|---|---|---|
| WBXML document | 2 MiB | Да, для обычной Calendar `Sync` page |
| Число WBXML elements | 256 000 | Да, для обычной Calendar `Sync` page |
| Глубина дерева | 32 | Нет |
| Одна inline string | 256 KiB | Нет |
| Размер HTTP body | Ограничен транспортом | Да, для обычной Calendar `Sync` page |
| Provider transaction | Ограничение Calendar Provider/Binder | Да |
| Число операций в одном provider sub-batch | Не более 50 | План заранее делится на sub-batches |

При превышении подходящего лимита текущий checkpoint сохраняется, окно
уменьшается вдвое, но не ниже одного, и повторяется та же страница.
Успех продолжает pagination уже с уменьшенным окном.

| Условие | Результат |
|---|---|
| Remote page превышает лимит при window 1 | `BLOCKED / PROTOCOL_DATA` |
| Provider transaction слишком велика при window 1 | `BLOCKED / CALENDAR_PROVIDER` |
| Excessive depth, oversized inline string | Terminal failure без уменьшения окна |
| Malformed syntax, UTF-8, token или structure | Protocol-data failure; уменьшение окна не исправляет данные |
| Те же decoder limits в `FolderSync` | Не считаются восстанавливаемыми уменьшением Calendar window |

Данные не пропускаются, checkpoint не продвигается. Ограничения размера
документа и числа элементов типизированы отдельно от syntax/encoding/token
errors. Один recurring item с большими списками участников, включая changed
и deleted exceptions, проходит обычные parsing и planning, если укладывается
в эти границы. [Лимит материализации участников](event-mapping.md#участники)
действует позже и не обрезает входное дерево.

## Сеанс, папка и интервал запросов

`OPTIONS`, команды, retries и continuation используют
[HTTP-сеанс точного профиля](transport.md#http-сеанс-процесса).
После завершения процесса cookie и live capabilities теряются: перед
календарными командами нужен свежий `OPTIONS`. Если сохранённая версия всё ещё
объявляется, checkpoints продолжают использоваться; смена версии требует full reset.

### Подготовка основной папки

Полученные folder key, primary collection ID и terminal endpoint кэшируются
в сеансе с привязкой к protocol version, generation и run token.

| Ситуация | Что происходит с подготовкой папки |
|---|---|
| Первая Calendar page логического run | Выполняется `FolderSync` |
| Следующие pages, adaptive retries, continuation того же fence | Подготовка переиспользуется |
| Новый run token, другая версия или профиль, холодный процесс | Нужен refresh |
| Invalid key, full reset, неуспешное согласование папки | Состояние инвалидируется или обновляется |

Durable checkpoint меняется только после успешной Calendar page. Если процесс
завершился до commit, `FolderSync` безопасно повторяется от последнего
сохранённого key.

### Интервал запросов

| Условие | Поведение pacer |
|---|---|
| Первый top-level exchange холодного sync-сеанса | Отправляется сразу |
| Следующий exchange | Не раньше двух секунд после завершения или transport failure предыдущего |
| Локальная запись, continuation или длинный backoff уже заняли интервал | Дополнительная задержка не добавляется |
| HTTPS redirect hops | Считаются частью одного top-level exchange |
| Отмена или устаревший fence во время ожидания | Ожидающий запрос не отправляется |
| Проверка подключения вне синхронизации | Этот pacer не используется |

Pacer общий для discovery, `FolderSync`, priming и обычных `Sync` pages.
Ожидание использует monotonic clock и coroutine cancellation; непосредственно
перед transport dispatch снова проверяется generation/run-token fence.

## Восстановление

| Причина | Продолжение |
|---|---|
| Временный сетевой сбой | Повтор того же логического run с [backoff](background-sync.md#ошибки-и-повторы) |
| Invalid SyncKey | Один fenced full reset; cleanup должен завершиться до пустого checkpoint и нового сетевого запроса |
| Dirty или исчезнувшее зеркало | Fenced full reset по [правилам Provider](calendar-provider.md#восстановление-зеркала) |
| Ошибка применения страницы | Старый checkpoint; безопасный повтор либо блокировка по категории |
| Завершение процесса | Восстановление work и продолжение от durable checkpoint |

Логи страниц, capacity outcomes и checkpoint:
[диагностика](diagnostics.md#как-читать-результат).
Граница проверок: [архитектура](architecture.md#проверка-реализации).
