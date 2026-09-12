# Схемы документации

[Все документы](../README.md)

PlantUML-файлы — редактируемые исходники. SVG рядом с ними — изображения,
встроенные в Markdown. Изменяйте исходник и обновляйте SVG одним изменением:
правки изображения вручную потеряются при следующем рендеринге.

## Карта схем

| Исходник | Что объясняет | Где используется |
|---|---|---|
| [module-dependencies.puml](module-dependencies.puml) | Разрешённые зависимости Gradle | [Архитектура](../architecture.md) |
| [architecture-components.puml](architecture-components.puml) | Композиция и внешние системы | [Архитектура](../architecture.md) |
| [connection-verification-sequence.puml](connection-verification-sequence.puml) | Проверка и граница commit профиля | [Подключение](../connection.md) |
| [tls-trust-boundaries.puml](tls-trust-boundaries.puml) | Клиентская identity и доверие серверу | [Транспорт](../transport.md) |
| [calendar-page-processing.puml](calendar-page-processing.puml) | Страница, пакеты записи и checkpoint | [Синхронизация](../calendar-sync.md), [Provider](../calendar-provider.md) |
| [sync-state-machine.puml](sync-state-machine.puml) | Состояния и переходы | [Фоновая работа](../background-sync.md) |
| [worker-lifecycle-sequence.puml](worker-lifecycle-sequence.puml) | Запуск и продолжение workers | [Фоновая работа](../background-sync.md) |
| [worker-stop-sequence.puml](worker-stop-sequence.puml) | Cancel, Disable и повтор cleanup | [Фоновая работа](../background-sync.md) |

## Локальный рендеринг

Для изображений используется PlantUML **1.2025.4** и Graphviz **15.1.1**,
с Java из окружения проекта. JAR и инструменты устанавливаются вне репозитория;
они не входят в зависимости Android-приложения. Графы рендерятся локально,
без отправки исходников внешнему серверу.

Из корня репозитория укажите путь к уже установленному JAR и выполните:

```shell
export PLANTUML_JAR=/absolute/path/to/plantuml-1.2025.4.jar
java -jar "$PLANTUML_JAR" -checkonly -charset UTF-8 docs/diagrams/*.puml
java -jar "$PLANTUML_JAR" -tsvg -nometadata -charset UTF-8 docs/diagrams/*.puml
```

`-nometadata` исключает встроенную копию исходника из SVG. Раскладка и шрифты
могут отличаться между окружениями; для одинакового результата используйте
одинаковые версии инструментов и доступные шрифты.

## Проверка после изменения

1. Убедитесь, что PlantUML завершился без ошибок.
2. Откройте SVG и соответствующую Markdown-страницу: проверьте подписи, стрелки
   и читаемость при обычной ширине окна.
3. Сверьте условия переходов с текстом и основными OpenSpec-спецификациями.
4. Проверьте, что каждый исходник имеет актуальный SVG и ссылку из документа.

Схема показывает один процесс или отношение. Числовые лимиты, полный перечень
ошибок и пограничные случаи остаются в таблицах тематического документа.
