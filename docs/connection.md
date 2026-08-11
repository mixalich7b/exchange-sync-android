# Настройка и проверка подключения

## Область capability

Capability `connection-settings` управляет единственным профилем подключения и
проверяет, что сервер доступен по HTTPS с выбранной mTLS identity и объявляет
необходимые возможности Exchange ActiveSync. Сам capability probe не читает
mailbox. После успешного первого или изменённого Save реализация `calendar-sync`
атомарно активирует новую generation, очищает owned calendar и создаёт фоновые
задачи; неизменённый recheck этого не делает.

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

Для загруженного профиля без несохранённых изменений доступна отдельная кнопка
повторной проверки. Она проверяет именно сохранённый профиль; изменение любого
из четырёх полей блокирует кнопку до восстановления исходных значений или
успешного Save. Повторная проверка не записывает DataStore.

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

## Validate-probe-commit и повторная проверка

Save выполняется в следующем порядке:

1. полная локальная валидация;
2. разрешение KeyChain alias;
3. создание TLS transport;
4. HTTPS/mTLS и ActiveSync capability probe;
5. атомарная замена профиля вместе с увеличением synchronization
   generation/run token и сбросом checkpoints;
6. очистка owned calendar и durable WorkManager handoff.

Любая ошибка до последнего шага оставляет предыдущий сохранённый профиль без
изменений. Введённый draft остаётся на экране для исправления.

Повторная проверка использует ту же validation/probe часть этого потока для
snapshot уже сохранённого профиля, но пропускает пятый шаг. Save и повторная
проверка используют общее состояние операции: пока любая из них выполняется,
UI показывает progress и не принимает редактирование, выбор сертификата, Save
или другую повторную проверку.

## ActiveSync capability probe

Первый запрос всегда имеет вид:

```http
OPTIONS https://<hostname>:443/Microsoft-Server-ActiveSync
```

Автоматические redirects OkHttp отключены. Общий `RedirectTracker` остаётся
единственным источником redirect policy для capability и command paths: он
обрабатывает коды 300, 301, 302, 303, 307 и 308, сохраняет исходный HTTP method
и, для `POST`, неизменное WBXML body. Разрешены относительные и cross-host HTTPS
destinations. Каждый destination проходит обычную проверку hostname и цепочки
сертификатов. Такой явный цикл нужен, чтобы OkHttp не переписал ActiveSync
method/body и чтобы каждый hop попал в общую проверку и diagnostics.

Probe завершается ошибкой redirect policy, если `Location` отсутствует или
некорректен, содержит embedded credentials, переводит соединение на HTTP,
повторяет уже посещённый URI или требует больше пяти redirects.

Успешный terminal response должен одновременно предоставить:

- HTTP 200;
- непустой `MS-ASProtocolVersions` хотя бы с одной версией `14.0`, `14.1`,
  `16.0` или `16.1`; сервер только с 12.1 отклоняется как несовместимый;
- `MS-ASProtocolCommands` с командами `FolderSync` и `Sync`.

Номер семейства сборок Exchange Server 2019 `15.2.x` и версия wire-протокола
ActiveSync — разные величины. Приложение не ищет product-version metadata и не
добавляет `15.2` в заголовок `MS-ASProtocolVersions`: endpoint семейства 15.2
совместим, когда объявляет хотя бы одну из `14.0`, `14.1`, `16.0` или `16.1`.
Значение `15.2`, предложенное только как protocol version, отклоняется.

Тело ответа `OPTIONS` не читается и не участвует в результате probe: для
capability check значимы только terminal status, обязательные заголовки и TLS
diagnostics.

Токены заголовков разделяются запятыми и очищаются от пробелов; имена команд
сопоставляются без учёта регистра. Выбранные KeyChain private key и certificate
chain настраиваются как единственная client identity TLS transport. После
успешного проверенного HTTPS response пустой `Handshake.localCertificates`
считается отсутствующей у Android/Conscrypt participation metadata, а не
отказом mTLS. Если provider всё же возвращает local chain, её leaf должна
совпадать с настроенной identity; наблюдаемое несовпадение остаётся ошибкой.
Недоступный KeyChain material, TLS/hostname/server-chain failure и HTTP
authentication rejection также сохраняют прежние устойчивые категории ошибок.
Diagnostics поэтому различают факт настройки fixed identity и доступность
platform evidence, но не заявляют недоказанное участие конкретных байтов.

После успешного terminal response приложение показывает эфемерную TLS-сводку:
конечный hostname и доступную из проверенного handshake цепочку server X.509
сертификатов от leaf к issuer. Для каждого сертификата отображаются RFC 2253
subject и issuer, серийный номер в шестнадцатеричном виде, период действия и
SHA-256 fingerprint. Это метаданные текущего результата: PEM/DER, client key
material и сертификатные байты не выводятся и не сохраняются. Если успешный
HTTPS response не даёт пригодной X.509-цепочки, проверка возвращает отдельную
категорию diagnostic error вместо частичного успешного результата.

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

## Process-local HTTP-сеанс

Один создаваемый `AppContainer` ActiveSync runtime обслуживает и проверку
подключения, и календарные команды. Для точной identity профиля — hostname,
email, `domain\login` и KeyChain alias — runtime держит отдельный потокобезопасный
сеанс. В нём находятся cookie jar и последний успешно установленный в текущем
процессе capability result: terminal HTTPS endpoint, выбранная версия и набор
поддерживаемых версий.

Cookie принимаются и заменяются по обычной identity name/domain/path,
просроченные и удалённые сервером значения отбрасываются. При последующих
`OPTIONS`, redirect, `FolderSync` и `Sync` отправляются только подходящие по
Secure, host/domain, path и expiry cookie. Поэтому cookie redirect destination
не уходит на посторонний host. Реестр ограничен четырьмя недавно использованными
профилями; вытеснение безопасно и приводит лишь к новому capability discovery.

Сеанс существует только до завершения процесса. Cookie, их имена, значения и
атрибуты не записываются в DataStore и не попадают в UI или diagnostics. После
холодного старта календарная синхронизация всегда выполняет свежий `OPTIONS` до
первой команды, даже если на диске есть checkpoints. Сохранённая protocol
version продолжает использоваться, если сервер по-прежнему её объявляет; иначе
выбирается максимальная общая версия и выполняется fenced full reset до
повторного использования protocol-dependent checkpoints.

## Ошибки

UI получает устойчивые категории, а не exception messages или response bodies:

- certificate alias недоступен;
- DNS, соединение или timeout;
- server trust, hostname, missing/invalid local CA или mTLS;
- access denied, endpoint mismatch, redirect policy или server error;
- ActiveSync protocol incompatibility;
- недоступны диагностические данные server TLS certificate;
- persistence или неизвестная ошибка.

Stack traces, закрытый ключ и другой key material в presentation data не
попадают.

Технические причины до их преобразования в эти категории пишутся в системный
Logcat с тегом `ExchangeSync`. Правила полей, редактирования чувствительных
данных и точные команды ADB описаны в [диагностике](diagnostics.md).

## Хранение и границы безопасности

DataStore сохраняет четыре поля профиля и отдельную non-secret sync metadata:
generation/run token, phase, safe problem, device ID и ActiveSync checkpoints.
Process-local cookie и live capability state в DataStore не сохраняются.
TLS-диагностика существует только в памяти текущего ViewModel и после
пересоздания приложения не восстанавливается. Пароль в модели отсутствует.
`OPTIONS` probe не создаёт HTTP Authorization header; календарные ActiveSync
команды передают `domain\login` как percent-encoded `User` query parameter и
используют ту же выбранную mTLS identity.

Неуспешная проверка не изменяет профиль и не запускает cleanup/work. Успешный
изменённый Save активирует полный sync; успешный неизменённый recheck не меняет
профиль, generation, календарь или WorkManager schedules.

## Принятые ограничения

- `OPTIONS` подтверждает endpoint capabilities, но не доказывает доступ к
  mailbox или основному календарю.
- Автоматические тесты не подключаются к живому серверу и не исполняют Android
  KeyChain; связка Android KeyChain, mTLS и живого Exchange Server проверена
  вручную на Xiaomi 17 с Android 16.
- Синхронное создание trust managers, `SSLContext` и OkHttp-клиента выполняется
  вне Main dispatcher, но не имеет жёсткого deadline и может превысить
  номинальный probe timeout при блокировке security provider.
- Парольная HTTP-аутентификация не реализована; calendar-команды используют
  mTLS и передают `domain\login` только как ActiveSync query-параметр `User`.
- Автоматическая бесшовная смена server certificates и certificate pinning не
  реализованы.
