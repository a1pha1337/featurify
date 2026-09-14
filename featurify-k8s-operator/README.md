# Featurify Kubernetes Operator

Оператор на Java Operator SDK синхронизирует `FeaturifyNamespace` с административным
API Featurify. Один Kubernetes CR (Custom Resource) описывает один namespace Featurify:
его название, глобальные фичи, группы, типы и значения фич.

Оператор запускается отдельным процессом на Java 25. Он не зависит от Spring,
серверных модулей или клиентских gRPC-библиотек. Версия JOSDK указана в `build.gradle.kts`.
Сервер Featurify должен содержать административный API оператора из этой же версии проекта.

## Быстрый пример

Полный документ: [`examples/checkout.yaml`](examples/checkout.yaml).

```yaml
apiVersion: featurify.io/v1alpha1
kind: FeaturifyNamespace
metadata:
  name: checkout
  namespace: applications
spec:
  key: checkout
  displayName: Checkout
  management:
    ownershipPolicy: Exclusive
    valuePolicy: Managed
    deletionPolicy: Retain
    adoptExisting: false
  features:
    - key: new-checkout
      type: BOOLEAN
      booleanValue: false
  groups:
    - key: payments
      displayName: Payments
      features:
        - key: provider
          type: ENUM
          enumOptions: [stripe, paypal]
          enumValue: stripe
        - key: methods
          type: VECTOR
          vectorValues:
            CARD: true
            CASH: false
```

`metadata.namespace` — область Kubernetes и RBAC. `spec.key` — ключ namespace
в Featurify; эти значения не обязаны совпадать. Отсутствующие `features` и `groups`
означают пустые списки: ранее управляемые ресурсы из них будут удалены.
Отсутствие `management` включает значения по умолчанию из примера.

## Установка

Нужен Kubernetes с поддержкой CRD CEL validation (рекомендуется 1.29+), работающий
Featurify и Keycloak. Манифест Deployment рассчитан на один Kubernetes namespace
`applications` и одну реплику. Leader election в первой версии не включён;
стратегия `Recreate` предотвращает перекрытие Pod при обновлении. Сервер дополнительно
сериализует операции и проверяет поколения, поэтому повторные запросы безопасны.

### 1. Подготовить сервер и Keycloak

Сначала разверните обновлённый Featurify. В соответствии с правилами репозитория
таблицы владения и аудита добавлены в существующую миграцию
`V1__create_feature_toggle_schema.sql`. Она предназначена для **новой базы**:
уже применённая старая V1 даст несовпадение checksum. Обновление существующей БД
требует отдельного согласованного переноса данных; оператор не выполняет Flyway repair,
не пересоздаёт рабочую БД и не удаляет volumes.

В realm Featurify создайте отдельный confidential OIDC client `featurify-k8s-operator`:

1. Включите client authentication и service accounts (`client_credentials`).
2. Отключите interactive login и direct access grants для этого клиента.
3. Создайте client scope `featurify.operator`, включите его в token scope и подключите
   к клиенту как **default client scope**.
4. Добавьте в access token claim `featurify_namespace_keys` типа JSON — массив
   разрешённых ключей, например `["checkout", "catalog"]`. В Keycloak это можно
   сделать hardcoded claim mapper с JSON claim type. Не добавляйте этот claim
   обычному UI-клиенту. Маска `*` не поддерживается.
5. Сохраните client secret в Kubernetes Secret. JWT должен содержать стабильный `sub`;
   он также участвует в проверке владельца. Пересоздание Keycloak service account
   потребует освобождения старого владения, а не только замены секрета.

Оператор использует административный Keycloak JWT. Namespace-токены публичного
REST/gRPC здесь не подходят. JWT оператора не допускается к обычным административным
API: запись проходит через endpoint оператора с проверкой `featurify_namespace_keys`.
Право создавать CR в `applications` делегирует доступ к ключам из JWT этого оператора.
Для другой группы доверия используйте отдельный Deployment, Kubernetes namespace и client.

### 2. Собрать образ

Из корня репозитория (PowerShell; на Linux замените Wrapper на `./gradlew`):

```powershell
.\gradlew.bat :featurify-k8s-operator:installDist
docker build -t featurify-k8s-operator:0.0.1-SNAPSHOT featurify-k8s-operator
```

Загрузите образ в registry вашего кластера либо импортируйте его в локальный кластер.
В [`deploy/operator.yaml`](deploy/operator.yaml) укажите нужный image, URL Featurify,
token endpoint Keycloak и **стабильный уникальный** `FEATURIFY_CLUSTER_ID`.
Смена cluster ID меняет владельца: это не настройка, которую можно менять при каждом релизе.
URL и cluster ID должны оставаться привязанными к одному окружению.

### 3. Установить CRD, credentials и Deployment

```powershell
kubectl create namespace applications
kubectl apply -f featurify-k8s-operator/deploy/crd.yaml
kubectl wait --for=condition=Established crd/featurifynamespaces.featurify.io --timeout=60s
kubectl -n applications create secret generic featurify-operator-keycloak --from-file=client-secret=/secure/path/client-secret
kubectl apply -f featurify-k8s-operator/deploy/operator.yaml
kubectl -n applications rollout status deployment/featurify-k8s-operator
kubectl apply --server-side --field-manager=featurify-gitops -f featurify-k8s-operator/examples/checkout.yaml
kubectl -n applications wait --for=condition=Ready featurifynamespace/checkout --timeout=120s
kubectl -n applications get ffns checkout -o yaml
```

Если namespace уже существует, пропустите его создание. Путь к secret-файлу — пример;
файл должен содержать только client secret. Не коммитьте секрет в Git. Secret монтируется
как файл, токен обновляется автоматически; новая версия файла читается при обновлении JWT.
Для production используйте HTTPS и доверенный JVM truststore; отключения проверки TLS нет.
Оператор не требует Kubernetes API разрешений на чтение Secrets: файл монтирует kubelet.

Используйте одного field manager для spec. При server-side apply Kubernetes объединяет
списки по `key`; поля, принадлежащие другому manager, могут остаться в сохранённом CR.
Оператор синхронизирует именно сохранённый `spec`, который виден через `kubectl get -o yaml`.

## Полный цикл синхронизации

1. Kubernetes проверяет документ по CRD: ключи, типы, допустимые поля значений,
   обязательные параметры и уникальность ключей в каждом списке.
2. JOSDK добавляет finalizer `featurify.io/namespace-cleanup` **до** первой записи в Featurify.
3. Оператор получает Keycloak JWT и отправляет весь spec вместе с cluster ID, CR UID,
   Kubernetes namespace/name и `metadata.generation` в Featurify.
4. Сервер проверяет право доступа к ключу, владельца, поколение, весь документ и конфликты.
   Все изменения и аудит выполняются в одной транзакции PostgreSQL. При любой ошибке
   транзакция откатывается, включая уже обработанные группы и фичи.
5. Сервер создаёт недостающее, обновляет отличия и удаляет исчезнувшие управляемые ресурсы.
   Полное состояние читается из PostgreSQL, серверного кеша конфигурации нет.
6. При успехе оператор устанавливает `Ready=True` и `status.observedGeneration`.
   При ошибке — `Ready=False` с причиной; последнее успешно применённое поколение сохраняется.
7. Изменение spec запускает новую сверку. Дополнительно сверка повторяется каждые 30 секунд,
   в том числе после ошибок: оператор замечает изменения вне Kubernetes и восстановление сервиса.

Повтор одного состояния не меняет версии фич и не создаёт новые записи аудита.
Обычные административные изменения и применение манифеста берут одну транзакционную
блокировку namespace. Optimistic locking фич и групп по `version` сохраняется.
Устаревшие поколения отвергаются; разные spec одного поколения тоже конфликтуют.
Изменения значений попадают в обычную историю фичи, применение spec и освобождение владения —
в `namespace_manifest_audit`. История фичи удаляется вместе с фичей по существующему контракту;
журнал применения манифестов сохраняется.

## Политики и работа в UI

| Поле | Значение | Поведение |
|---|---|---|
| `ownershipPolicy` | `Exclusive` (default) | Все группы и фичи описываются в CR; создавать ручные запрещено |
| `ownershipPolicy` | `Shared` | CR управляет своими ресурсами; ручные ресурсы сохраняются |
| `valuePolicy` | `Managed` (default) | Значения всегда приводятся к YAML; ручное редактирование запрещено |
| `valuePolicy` | `InitialOnly` | YAML задаёт начальные значения при создании; существующими значениями управляют UI/API |
| `deletionPolicy` | `Retain` (default) | При удалении CR данные остаются, владение освобождается |
| `deletionPolicy` | `Delete` | При удалении CR удаляются управляемые данные; детали ниже |
| `adoptExisting` | `false` (default) | Совпадение с ручным ресурсом вызывает конфликт |
| `adoptExisting` | `true` | Разрешено принять существующий namespace и перечисленные ручные ресурсы под управление |

`ownershipPolicy` и `spec.key` неизменяемы. `valuePolicy`, `deletionPolicy` и `adoptExisting`
можно менять. Переключение `InitialOnly → Managed` немедленно возвращает значения из YAML;
`Managed → InitialOnly` сохраняет текущие значения.

В UI управляемые фичи помечены `Kubernetes`; недоступны удаление и перенос, а также
редактирование описания. При `Managed` также недоступно изменение значения.
При `InitialOnly` inline-переключатели и редактирование значения работают.
Ограничения проверяет сам сервис, поэтому REST API не позволяет обойти UI.
Управляемый namespace и управляемую группу нельзя удалить обычным admin API.
Кнопки «Закрепить» и неявных исключений нет.

### Изменение структуры

- Удаление фичи из YAML удаляет её при обеих политиках значения, включая `InitialOnly`.
- Удаление группы удаляет её управляемые фичи. Если в ней есть ручные фичи (`Shared`),
  весь apply отклоняется: сначала перенесите ручные фичи в другую группу/Global.
- Ключ фичи уникален внутри группы; в Global действует отдельная область ключей.
- Идентичность: `(namespace, group, key)`. Перемещение между разделами YAML — удаление
  и создание новой фичи, с новым ID/версией и начальным значением. История старой удаляется.
- Изменение типа существующей фичи вызывает конфликт. Для смены типа сначала примените
  удаление и дождитесь успеха, затем создайте фичу заново отдельным применением.
- Для ENUM можно менять `enumOptions`. В `InitialOnly` удаление текущего выбранного
  значения отклоняется; сначала выберите в UI допустимый вариант.
- Для VECTOR набор элементов всегда берётся из YAML. В `InitialOnly` состояния
  существующих элементов сохраняются, новые получают начальные значения, исчезнувшие удаляются.
- Лимиты сервера: 100 групп, 1000 фич на CR, 100 ENUM-опций/элементов VECTOR на фичу.
  Пустой VECTOR не допускается. Namespace `default` не может управляться оператором.

## Принятие существующих данных

Один целевой namespace может принадлежать только одному CR, в том числе в `Shared`.
Владение хранится в БД с привязкой к cluster ID, CR UID, имени/namespace CR и JWT subject.
Совпадение `metadata.name` после пересоздания CR не означает прежнего владельца.

Для принятия ручного namespace укажите `adoptExisting: true` и перечислите нужные ресурсы.
В `Exclusive` нужно перечислить **все** существующие группы и фичи: лишние ручные ресурсы
вызывают конфликт, а не удаляются при первом принятии. В `Shared` неперечисленные остаются ручными.
При `Managed` значения принятых ресурсов приводятся к YAML; при `InitialOnly` сохраняются.
Владение другого активного CR не перехватывается даже с `adoptExisting: true`.

После успешного принятия рекомендуется вернуть `adoptExisting: false`, чтобы будущие
совпадения ключей вновь требовали явного решения. Автоматический takeover потерянного владельца
и ручное отсоединение отдельной фичи в этой версии не предусмотрены.

## Удаление и восстановление

`deletionPolicy` управляет **удалением CR**, а не pruning элементов из spec.

```powershell
kubectl -n applications delete featurifynamespace checkout
```

При `Retain` сервер сохраняет namespace, группы, фичи и их значения, освобождает владение,
после чего оператор снимает finalizer. Данные становятся доступны для ручного управления.
При `Delete + Exclusive` удаляется namespace и всё содержимое, включая namespace-токены.
При `Delete + Shared` удаляются только управляемые фичи и группы; namespace и ручные данные остаются.
Ручная фича внутри удаляемой группы блокирует всю очистку до переноса или выбора `Retain`.

Если Featurify/Keycloak недоступен, CR остаётся `Terminating`, finalizer сохраняется,
очистка повторяется. `Retain` тоже требует связи с сервером, чтобы корректно освободить владение.
Возвращённый из spec актуальный `deletionPolicy` используется даже если последнее apply
завершилось конфликтом; поэтому зависшее удаление можно перевести в `Retain` обновлением spec.
После удаления tombstone в БД отвергает запоздавшие запросы старого UID.

Для смены владельца/политики владения: выставьте `Retain`, удалите CR, дождитесь завершения,
создайте новый CR с `adoptExisting: true`. Перезапуск Pod не меняет владение и не требует adoption.
При удалении самого оператора сначала удалите его CR и дождитесь обработки finalizer,
затем удаляйте Deployment и CRD. Не снимайте finalizer вручную как обычный способ удаления:
это оставляет серверную привязку, и новый CR получит конфликт.

## Настройки и локальный запуск

| Переменная | Назначение |
|---|---|
| `WATCH_NAMESPACE` | Обязательный Kubernetes namespace для наблюдения |
| `FEATURIFY_CLUSTER_ID` | Обязательный стабильный уникальный ID кластера |
| `FEATURIFY_URL` | Обязательный базовый URL Featurify, например `https://featurify.example.com` |
| `KEYCLOAK_TOKEN_URL` | Обязательный полный URL token endpoint |
| `KEYCLOAK_CLIENT_ID` | Обязательный confidential client ID |
| `KEYCLOAK_CLIENT_SECRET_FILE` | Обязательный путь к файлу client secret |
| `RECONCILE_INTERVAL_SECONDS` | Интервал сверки и повторов, default `30`, диапазон `5..3600` |

Настройте переменные, kubeconfig и выполните из корня `.\gradlew.bat :featurify-k8s-operator:run`.
Fabric8 использует kubeconfig локально и ServiceAccount внутри Pod.
HTTP connect timeout — 10 секунд, token timeout — 20 секунд, Featurify timeout — 30 секунд.
После HTTP 401 токен обновляется с однократным повтором запроса. Redirects не выполняются.
Секреты и токены не включаются в статус или сообщения ошибок.

Диагностика:

```powershell
kubectl -n applications get ffns
kubectl -n applications get ffns checkout -o yaml
kubectl -n applications logs deployment/featurify-k8s-operator
```

`Ready=False`: `InvalidManifest` — исправить поля; `Conflict` — проверить владельца,
поколение, ENUM или ручные фичи; `AuthenticationFailed` — проверить scope, grant ключа,
client credentials; `ServiceUnavailable` — проверить сеть и доступность зависимостей.
После изменения CR сравнивайте `status.observedGeneration` с `metadata.generation`:
одно старое `Ready=True` до обработки нового события ещё не подтверждает применение новой версии.

## Серверный контракт и проверки

- `PUT /api/v1/operator/namespaces/{key}`: `{owner, generation, spec}` → `{observedGeneration, changed}`.
- `POST /api/v1/operator/namespaces/{key}/cleanup`: `{owner, generation, deletionPolicy}` → `204`.
- `owner`: `{clusterId, uid, kubernetesNamespace, name}`. UID берётся из Kubernetes metadata.
- Ошибки используют существующий `application/problem+json`; 400 — валидация,
  403 — scope/namespace grant, 409 — конфликт владения, поколения или структуры.

```powershell
.\gradlew.bat :featurify-k8s-operator:test :featurify-service:test
.\gradlew.bat ktlintCheck
# Только с отдельной тестовой PostgreSQL:
$env:FEATURIFY_DB_TESTS = "true"
$env:DB_URL = "jdbc:postgresql://localhost:55432/featurify_operator_test"
$env:DB_USERNAME = "featurify_test"
$env:DB_PASSWORD = "featurify_test"
.\gradlew.bat :featurify-service:test
```

Java-тесты проверяют чтение YAML, HTTP/OAuth, статусы и finalizer. PostgreSQL-тесты
проверяют атомарность, идемпотентность, аудит, pruning, adoption, политики значения,
защиту ручных данных, авторизацию и устаревшие запросы. Они включаются только через
`FEATURIFY_DB_TESTS=true`. Обычный `test` не подтверждает их выполнение.
