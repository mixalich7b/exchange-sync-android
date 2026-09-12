# Представление событий

[Все документы](README.md)

Эта страница описывает преобразование серверных событий в Android Calendar
Provider. Нормативные сценарии:
[calendar-sync](../openspec/specs/calendar-sync/spec.md).

## Содержание

- [Переносимые данные](#переносимые-данные)
- [Участники](#участники)
- [Приглашения](#приглашения)
- [Частичные изменения](#частичные-изменения)
- [Явно пустые поля](#явно-пустые-поля)
- [Повторения и исключения](#повторения-и-исключения)
- [Восстановление времени из Provider](#восстановление-времени-из-provider)

## Переносимые данные

| Группа | Поля |
|---|---|
| Identity | UID / ServerId |
| Время | Начало, конец, all-day range, timezone |
| Содержимое | Title, body, location, sensitivity |
| Участие | Organizer, attendees, meeting/response state |
| Повторения | Recurrence, changed и deleted exceptions |
| Напоминания | Reminder |

Windows time-zone blob преобразуется в представимую Android time zone.
Неоднозначные или непредставимые данные блокируют страницу категорией
protocol/provider problem без сдвига identity, времени или checkpoint.

## Участники

| Effective non-organizer attendees | Запись в Provider |
|---|---|
| Не более 100 | Полный набор attendee rows |
| Более 100, organizer присутствует | Все non-organizer rows опускаются; остаётся отдельная organizer row |
| Более 100, organizer отсутствует | Attendee rows не создаются |

`HAS_ATTENDEE_DATA` при превышении порога отражает только наличие organizer.
Правило применяется независимо к series и каждой exception после наследования
её effective attendee list. Переход через порог в любом направлении полностью
заменяет соответствующую child collection.

Decoder и domain mapper сохраняют весь входной список, включая changed и
deleted exceptions. Ограничение действует только при материализации Provider
и не меняет классификацию приглашения, self-attendee status или identity exception.
High-fanout series, укладывающаяся в [WBXML limits](calendar-sync.md#ограничения-и-уменьшение-окна),
сохраняет organizer, recurrence и уникальные changed/deleted exception rows;
non-organizer rows для series и oversized non-deleted exceptions опускаются.

## Приглашения

Authoritative `ResponseType` имеет приоритет. Если его нет, допустим
однозначный status участника, соответствующего email профиля.

| Состояние | Представление Android |
|---|---|
| Непринятое или tentative | Tentative `STATUS`, `AVAILABILITY` и self-attendee status; opaque цвет, смешанный на 45% с белым |
| Accepted или organizer | Confirmed; восстановлена server availability; event-color override очищен |
| Declined или cancelled | Cancelled |

Исходная server availability сохраняется отдельно. Принятие обновляет ту же
строку по ServerId, без дубликата. Правила работают и для attendee-only partial
changes с отсутствующими (ghosted) meeting fields, и для exceptions с
унаследованными полями series.

## Частичные изменения

| Протокол / значение | Правило |
|---|---|
| ActiveSync 14.0/14.1 | Initial и последующие запросы объявляют `Supported` properties |
| ActiveSync 16.0/16.1, поле отсутствует в `Change` | Объединяется только с clean snapshot собственной строки Provider |
| Dirty row | Требуется fenced full reset: локальная правка не должна стать server value |
| Поле явно пусто | Очищается с учётом nullability целевой колонки |
| Поле явно задано сервером | Имеет приоритет над унаследованным значением |

`AirSyncBase:Location` в структурированном виде и `InstanceId` разбираются
с учётом версии. Explicit response override exception сохраняется в sync
metadata строки exception и переживает последующие partial `Change`.

## Явно пустые поля

Общее правило `Empty → NULL` неприменимо к обязательным provider columns.

| Целевая колонка | Значение для explicit empty |
|---|---|
| Nullable descriptive / optional | SQL `NULL` |
| `ALL_DAY` | `0` |
| `AVAILABILITY` | `BUSY` |
| `SELF_ATTENDEE_STATUS` | `NONE` |
| `ACCESS_LEVEL` | `DEFAULT` |

Так exception может отменить наследование series property без нарушения
`NOT NULL`. Полный page plan отклоняет `NULL` в известной обязательной
колонке до первого sub-batch. Если faithful значение непредставимо,
результат — `PROTOCOL_DATA`, без частичной записи и продвижения checkpoint.

## Повторения и исключения

В ActiveSync 16.0/16.1 `InstanceId` принимает только Compact DateTime UTC
`yyyyMMdd'T'HHmmss'Z'`. Extended, fractional и malformed значения отклоняют
всю страницу как protocol data без продвижения checkpoint.

### Ответ на приглашение для exception

| Вход exception | Как определяется response |
|---|---|
| Собственный `ResponseType` | Authoritative override |
| Нет `ResponseType`, ровно один attendee с email профиля и поддерживаемым `AttendeeStatus` | Override выводится из attendee |
| Нет совпадения, status отсутствует или совпадений несколько | `Absent`: новая exception наследует series; partial change сохраняет предыдущий explicit override |

Эта optional inference действует только для exception. Полученная series без
собственного `ResponseType` и без однозначного current-user attendee response
по-прежнему отклоняет всю страницу как `PROTOCOL_DATA`.

Series-only `ResponseType` change обновляет только presentation унаследованных
exception rows. Текст, время, attendees, reminders и deleted state не
пересоздаются; explicit exception override сохраняется.

## Восстановление времени из Provider

Во время обслуживания базы часовых поясов Provider может заменить отсутствующий
`DTEND` recurring series на epoch zero, сохранив `DTSTART`, `DURATION` и
`RRULE`. Это учитывается только при чтении clean snapshot для partial `Change`.

| Условие snapshot | Действие |
|---|---|
| Recurring row, epoch-zero end, start присутствует, duration положителен и строго `PT<n>S` | End считается отсутствующим; effective end = start + duration |
| Нет start; duration некорректен, нулевой, отрицательный или непредставим в provider epoch-millis | Inherited range недоверенный |
| Partial `Change` наследует недоверенный диапазон без полной замены | `PROTOCOL_DATA`; checkpoint не продвигается |
| Серверный `Delete` или явный валидный replacement range | Сохраняет приоритет |
| Non-recurring, dirty row или ненулевой provider end | Эта нормализация не применяется |

Нормализация создаёт только надёжное предыдущее состояние для отсутствующих
полей. Explicit `StartTime`/`EndTime` Exchange всегда имеют приоритет.
Явный равный или обратный серверный диапазон отклоняет всю страницу.
