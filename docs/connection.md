# Настройка и проверка подключения

## Область capability

Capability `connection-settings` управляет единственным профилем подключения и
проверяет, что указанный сервер доступен по HTTPS с выбранной mTLS identity и
объявляет необходимые возможности Exchange ActiveSync. Она не читает mailbox
или календарь и не создаёт фоновые задачи.

Нормативные сценарии находятся в
[`openspec/specs/connection-settings/spec.md`](../openspec/specs/connection-settings/spec.md).

## Профиль и состояние формы

Профиль содержит четыре значения:

- email;
- учётное имя `domain\login`;
- hostname сервера без схемы, пути и порта;
- alias клиентского сертификата из Android KeyChain.

Протокол всегда HTTPS, порт всегда 443, путь ActiveSync задаётся приложением.
Поддерживается ровно один профиль: новое успешное сохранение атомарно заменяет
предыдущий.

При открытии экрана ViewModel сначала загружает сохранённый профиль. Пока чтение
не завершено, UI показывает progress и блокирует редактирование, выбор
сертификата и Save. Успешно загруженный профиль отображается как подключённый;
отсутствующий профиль открывает пустую редактируемую форму.

## Локальная валидация

Перед сетью проверяются все поля одновременно:

- email должен содержать ровно один `@`, непустые части до и после него и не
  содержать пробелы;
- `domain\login` должен содержать ровно один обратный слеш, непустые domain и
  login и не содержать пробелы;
- hostname должен состоять из допустимых DNS labels, иметь длину не более 253
  символов и не содержать пробелы, схему, путь, query, fragment или явный порт;
- KeyChain alias должен быть выбран и не быть пустым.

Невалидный draft возвращает ошибки всех затронутых полей, не разрешает KeyChain
material, не выполняет HTTP и не изменяет DataStore.

## Клиентский сертификат

`MainActivity` вызывает системный `KeyChain.choosePrivateKeyAlias` для текущего
HTTPS hostname и порта 443. Ограничения по issuer и key type не задаются, чтобы
не исключать сертификаты частного развёртывания. Текущий alias передаётся как
предварительно выбранный; отмена chooser сохраняет прежнее значение.

Приложение хранит только alias. Перед probe infrastructure вызывает
`KeyChain.getPrivateKey` и `KeyChain.getCertificateChain` на I/O dispatcher и
создаёт key manager, который предоставляет TLS только выбранные ключ и цепочку.
Закрытый ключ не сериализуется, не логируется и не передаётся в presentation
layer. Если alias удалён, отозван или недоступен, проверка прекращается до HTTP
и UI запрашивает повторный выбор.

## Validate-probe-commit

Save выполняется в следующем порядке:

1. полная локальная валидация;
2. разрешение KeyChain alias;
3. создание TLS transport;
4. HTTPS/mTLS и ActiveSync capability probe;
5. атомарная замена профиля в DataStore.

Любая ошибка до последнего шага оставляет предыдущий сохранённый профиль без
изменений. Введённый draft остаётся на экране для исправления. Пока Save
выполняется, UI показывает progress и не принимает второй Save.

## ActiveSync capability probe

Первый запрос всегда имеет вид:

```http
OPTIONS https://<hostname>:443/Microsoft-Server-ActiveSync
```

Автоматические redirects OkHttp отключены. Verifier самостоятельно обрабатывает
коды 300, 301, 302, 303, 307 и 308, повторяя `OPTIONS` и сохраняя ту же mTLS
конфигурацию. Разрешены относительные и cross-host HTTPS destinations. Каждый
destination проходит обычную проверку hostname и цепочки сертификатов.

Probe завершается ошибкой redirect policy, если `Location` отсутствует или
некорректен, содержит embedded credentials, переводит соединение на HTTP,
повторяет уже посещённый URI или требует больше пяти redirects.

Успешный terminal response должен одновременно предоставить:

- HTTP 200;
- непустой `MS-ASProtocolVersions` хотя бы с одной версией `12.1`, `14.0`,
  `14.1`, `16.0` или `16.1`;
- `MS-ASProtocolCommands` с командами `FolderSync` и `Sync`.

Токены заголовков разделяются запятыми и очищаются от пробелов; имена команд
сопоставляются без учёта регистра. Terminal TLS handshake также должен содержать
leaf выбранного клиентского сертификата, иначе mTLS считается неподтверждённым.

## Доверие TLS

Server chain проверяется объединением двух независимых источников:

1. системный Android trust store;
2. опциональный локальный trust manager из X.509 PEM/DER-файлов в
   `infrastructure/src/main/assets/tls/`.

Локальный каталог полностью игнорируется Git и может отсутствовать. Поэтому
чистая сборка продолжает доверять публичным CA, включая Let's Encrypt, а
частные anchors не устанавливаются в системное хранилище Android.

Проверка выполняется system-first: успех любого trust manager принимает цепочку.
Trust-all fallback, отключение chain validation, собственный hostname verifier
и certificate pinning не используются.

Если оба trust manager отклоняют цепочку, классификатор анализирует
структурированные причины. Только системный `PKIXReason.NO_TRUST_ANCHOR`
разрешает категории missing или invalid local CA. При таком результате состояние
локальных assets намеренно приоритетнее более детального отказа локального
validator: сначала разработчик должен исправить packaged CA material. Прочие
ошибки сертификата остаются server-trust, а hostname mismatch имеет отдельную
категорию.

## Ошибки

UI получает устойчивые категории, а не exception messages или response bodies:

- certificate alias недоступен;
- DNS, соединение или timeout;
- server trust, hostname, missing/invalid local CA или mTLS;
- access denied, endpoint mismatch, redirect policy или server error;
- ActiveSync protocol incompatibility;
- persistence или неизвестная ошибка.

Stack traces, закрытый ключ и другой key material в presentation data не
попадают.

## Хранение и границы безопасности

DataStore сохраняет только четыре поля профиля. Пароль в модели отсутствует, а
`domain\login` в текущем capability не превращается в HTTP Authorization header:
probe подтверждает mTLS и возможности endpoint. Calendar operations, workers,
reminders и notifications при любом исходе Save не запускаются.

## Принятые ограничения

- `OPTIONS` подтверждает endpoint capabilities, но не доказывает доступ к
  mailbox или основному календарю.
- Автоматические тесты не подключаются к живому серверу и не исполняют Android
  KeyChain; интеграционный риск остаётся для ручной проверки на устройстве.
- Синхронное создание trust managers, `SSLContext` и OkHttp-клиента выполняется
  вне Main dispatcher, но не имеет жёсткого deadline и может превысить
  номинальный probe timeout при блокировке security provider.
- Парольная аутентификация и передача `domain\login` серверу не реализованы.
- Автоматическая бесшовная смена server certificates и certificate pinning не
  реализованы.
