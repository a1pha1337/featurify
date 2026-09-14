# Локальный Kubernetes-стенд через Docker Compose

Из корня репозитория:

```powershell
docker compose -f docker-compose.k8s.yaml up --build -d --wait --wait-timeout 1200
```

Нужен только работающий Docker с Linux containers и Compose v2. JDK, Gradle, kind
и kubectl на хосте устанавливать не требуется. Первый запуск скачивает образы и
зависимости, собирает исходники и может занять несколько минут. Для Docker Desktop
желательно выделить не менее 4 CPU и 8 ГБ RAM.

| Адрес | Назначение | Доступ |
|---|---|---|
| http://localhost:18080 | Featurify UI и REST | `featurify` / `featurify` |
| http://localhost:18081 | Keycloak Admin Console | `admin` / `admin` |
| localhost:19090 | gRPC | Namespace-токен, созданный в UI |
| https://127.0.0.1:16443 | Kubernetes API | `.local/k8s/kubeconfig` |

Порты отличаются от обычного `docker-compose.yaml`, поэтому стенды можно запускать
одновременно. При необходимости задайте `LOCAL_APP_PORT`, `LOCAL_KEYCLOAK_PORT`,
`LOCAL_GRPC_PORT`, `LOCAL_KUBE_PORT` **перед первым запуском**. При смене HTTP-портов
у уже созданного realm также обновите redirect URI/web origins UI-клиента в Keycloak.

## Что запускается

Compose запускает один privileged контейнер `sandbox` с собственным Docker daemon.
В нём создаётся kind-кластер `featurify-local`, а в Kubernetes namespace `applications`:

- PostgreSQL 17: базы `featurify` и `keycloak`, StatefulSet с PVC;
- Keycloak с готовым realm, UI-пользователем и отдельным client credentials клиентом оператора;
- Featurify, собранный из текущего checkout;
- `featurify-k8s-operator`, его ServiceAccount, Role/RoleBinding и CRD;
- пример `FeaturifyNamespace/checkout` с BOOLEAN, ENUM и VECTOR.

Приложения работают именно внутри Kubernetes. Host Docker socket не монтируется;
вложенный daemon хранит кластер, PVC и кеш сборки в отдельном Compose volume `kind-data`.
Privileged режим нужен для Docker-in-Docker/kind. Это стенд для локальной разработки:
пароли фиксированы, опубликованные порты доступны только на localhost.

Bootstrap ожидает готовности PostgreSQL, Keycloak, Featurify и первого `checkout`.
Сообщение `Ready` в Compose и `healthy` у sandbox означают завершение первоначального запуска.
При повторном запуске образы пересобираются с кешем, приложения обновляются, данные сохраняются.
Пример применяется только при первой инициализации: перезапуск не отменяет ваши правки CR.

## Проверка и работа с оператором

Автоматическая проверка готовности, входа через Keycloak, REST и синхронизации `checkout`:

```powershell
docker compose -f docker-compose.k8s.yaml exec sandbox /lab/verify.sh
```

Она не меняет данные и рассчитана на существующий CR `checkout`.

```powershell
docker compose -f docker-compose.k8s.yaml logs -f sandbox
docker compose -f docker-compose.k8s.yaml exec sandbox kubectl -n applications get pods,pvc,ffns
docker compose -f docker-compose.k8s.yaml exec sandbox kubectl -n applications get ffns checkout -o yaml
docker compose -f docker-compose.k8s.yaml exec sandbox kubectl -n applications logs deployment/featurify-k8s-operator
```

В UI должен появиться namespace `Checkout` с тремя фичами. Начальные политики:
`Exclusive`, `Managed`, `Retain`. Поэтому значения в UI доступны только для чтения;
их источник — сохранённый Kubernetes CR.

Отредактируйте [`examples/checkout.yaml`](../../featurify-k8s-operator/examples/checkout.yaml)
на хосте, например смените `booleanValue` или удалите фичу, затем примените:

```powershell
docker compose -f docker-compose.k8s.yaml exec sandbox kubectl apply --server-side --field-manager=featurify-gitops -f /workspace/featurify-k8s-operator/examples/checkout.yaml
docker compose -f docker-compose.k8s.yaml exec sandbox kubectl -n applications get ffns checkout -o yaml
```

Дождитесь `Ready=True` и совпадения `status.observedGeneration` с `metadata.generation`,
обновите UI. Удалённая из манифеста фича исчезнет в Featurify. Для изменения значений
через UI установите в YAML `valuePolicy: InitialOnly` и примените документ.

Keycloak JWT оператора разрешает ключи Featurify `checkout` и `catalog`.
Для других ключей расширьте claim `featurify_namespace_keys` в protocol mapper клиента
`featurify-k8s-operator`. При первом запуске mapper создаётся автоматически. Realm import
не перезаписывает существующий realm после перезапуска.

Если kubectl установлен на хосте, можно использовать экспортированный kubeconfig:

```powershell
kubectl --kubeconfig .local/k8s/kubeconfig -n applications get ffns
```

Этот файл содержит доступ администратора **локального** кластера и исключён из Git.
Текущий kubeconfig и context пользователя не изменяются.

## Проверка удаления CR

```powershell
docker compose -f docker-compose.k8s.yaml exec sandbox kubectl -n applications delete ffns checkout
```

С `Retain` данные останутся в UI и перейдут в ручное управление. Для повторного создания
CR выставьте `adoptExisting: true` в YAML. Чтобы проверить полное удаление namespace,
сначала примените `deletionPolicy: Delete`, затем удалите CR. Подробности политик,
конфликтов и восстановления — в [README оператора](../../featurify-k8s-operator/README.md).

## Остановка и обновление

```powershell
# Остановить контейнеры, сохранить кластер, PVC и данные:
docker compose -f docker-compose.k8s.yaml down

# Запустить снова, пересобрать текущие исходники и обновить приложения:
docker compose -f docker-compose.k8s.yaml up --build -d --wait --wait-timeout 1200
```

Для обновления уже работающего стенда сначала выполните `down`, затем `up`:
повторный `up` без перезапуска sandbox сам по себе не запускает bootstrap заново.

Только для намеренного полного сброса локального стенда:

```powershell
docker compose -f docker-compose.k8s.yaml down -v
```

Последняя команда удаляет **весь локальный кластер, обе базы и кеш вложенного Docker**.
Она не затрагивает volumes обычного `docker-compose.yaml`. Bootstrap никогда не удаляет
данные сам ради запуска и не выполняет Flyway repair. Если V1 изменилась относительно
сохранённой БД, потребуется перенос схемы или осознанный сброс этого локального стенда.

При ошибке запуска смотрите `logs sandbox`. Внутри запущенного sandbox доступны
`kubectl`, `kind` и `docker`; внутренний daemon log — `/var/log/featurify-dockerd.log`.
Неудачная инициализация сохраняет volume, чтобы повторный `up` использовал кеш и данные.
