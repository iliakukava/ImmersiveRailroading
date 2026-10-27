# Карта проекта Immersive Railroading

Мод собирается одним исходником против Universal Mod Core. Версии игры (1.12.2–1.21.4) различаются в UMC, не в этом репозитории. Точка входа: `umc.json` → `cam72cam.immersiverailroading.ImmersiveRailroading`.

## Запуск

`ImmersiveRailroading.commonEvent` регистрирует сущности, пакеты и блоки на `CONSTRUCT`, затем на `INITIALIZE` вызывает `DefinitionManager.initDefinitions()`. Клиентский рендер вешается в `clientEvent`.

## Подвижной состав

| Слой | Классы | Роль |
| --- | --- | --- |
| Определение | `registry/EntityRollingStockDefinition` и наследники (`LocomotiveSteamDefinition`, `CarFreightDefinition`, …) | JSON/CAML пака: физика, текстуры, скрипт, модель |
| Загрузка паков | `registry/DefinitionManager`, `registry/PackLoadReport` | Индексы `rolling_stock/stock.json` и `track/track.json` из всех ресурс-паков, явный отчёт ошибок |
| Сущность | `entity/EntityRollingStock` → `EntityBuildableRollingStock` → `EntityRidableRollingStock` → `EntityMoveableRollingStock` → `EntityCoupleableRollingStock` | NBT, пассажиры, физика, сцепка |
| Типы | `Freight`, `FreightTank`, `CarPassenger`, `Tender`, `HandCar`, `Locomotive`, `LocomotiveSteam`, `LocomotiveDiesel` | Поведение конкретного типа |
| Физика | `entity/physics`, `physics/TickPos` | Состояние симуляции и пакет `MRSSyncPacket` |

## Модель

| Слой | Классы | Роль |
| --- | --- | --- |
| Загрузчик UMC | `ModelLoader.load` | OBJ → один атлас на вариант и канал, плюс LOD |
| Бюджет текстур | `model/TextureBudget`, `model/TextureMemory` | Какие листы оставить в памяти после сборки атласа |
| Разбор групп | `model/components/ComponentProvider`, `ModelComponent`, `library/ModelComponentType` | Имена групп OBJ (`WHEEL_DRIVER_1`, `DOOR_1`, …) |
| Состояние кадра | `model/ModelState` | Матрицы, видимость, свет; скриптовая поза домножается здесь |
| Сборка | `model/StockModel` и `LocomotiveModel` / `SteamLocomotiveModel` | Колёса, двери, контролы, LOD, тик скрипта |
| Детали | `model/part/*` | `Control`, `Door`, `Seat`, `Bogey`, `ValveGear`, `LightFlare`, … |
| Путь | `track/*`, `model/TrackModel`, `tile/TileRail*` | Укладка и рендер пути |

`StockModel` один на определение и общий для всех вагонов этого типа. Выбор LOD считается по дистанции конкретного вагона до игрока и передаётся в `ModelConfig.lod`, а не сканированием всех сущностей.

## Сеть

Пакеты лежат в `net/`. Позиция вагона синхронизируется `MRSSyncPacket`. Позиция игрока внутри вагона хранится UMC в `ModdedEntity.passengerPositions` и рассылается `PassengerPositionsPacket`, но сервер не видит `Player.xxa/zza`. Клиент шлёт `RiderMotionPacket`, сервер применяет его в `EntityRidableRollingStock.onPassengerUpdate`.

## Скрипты моделей

`script/lua` — интерпретатор подмножества Lua 5.1 без зависимостей. `script/ModelScript` связывает его с контролами, дверями, сцепкой, пассажирами и позой групп. Поле определения: `script`. Описание API: `docs/lua-model-scripts.md`.

## Что не трогать без UMC

Рендер OpenGL, загрузка OBJ и синхронизация сущностей живут в Universal Mod Core (`cam72cam.mod.model.common`, `cam72cam.mod.entity.ModdedEntity`). Этот репозиторий только выбирает LOD, отпускает лишние листы и сообщает серверу ввод ходьбы.
