# Lua-скрипты моделей

Скрипт задаётся в JSON или CAML определения подвижного состава полем `script`.

Путь внутри ресурс-пака:

```json
"script": "rolling_stock/locomotives/my_engine.lua"
```

Либо сам текст, если строка начинается с `--`, `function`, `local ` или содержит перевод строки. Ошибка разбора попадает в отчёт загрузки паков (`/immersiverailroading packreport`) и в лог с именем файла и номером строки. Остальные модели пака при этом продолжают грузиться.

Интерпретатор понимает Lua 5.1 в объёме, нужном моделям: `local`, функции, `if/elseif/else`, `while`, `repeat`, числовой и обобщённый `for`, таблицы, `and/or/not`, конкатенацию, вызовы `obj.method()` и `obj:method()`. Нет `goto`, побитовых операторов и метатаблиц. Один вызов события ограничен 250000 шагами.

## Когда вызываются события

| Событие | Сторона | Аргументы |
| --- | --- | --- |
| `on_load(stock)` | обе, один раз на сущность | таблица состава |
| `on_tick(stock)` | обе | таблица состава |
| `on_client_tick(stock)` | клиент, после `on_tick` | таблица состава |
| `on_control(stock, group, value, previous)` | сервер | имя контрола и значения 0..1 |
| `on_door(stock, group, open)` | сервер | имя двери и boolean |
| `on_couple(stock, side)` | сервер | `"front"` или `"back"` |
| `on_uncouple(stock, side)` | сервер | сторона |
| `on_mount(stock, uuid)` | сервер | UUID пассажира |
| `on_dismount(stock, uuid)` | сервер | UUID пассажира |

`on_tick` выполняется и на сервере, и на клиенте, у каждого своя сессия позы. `stock.server()` отличает сторону. `set_control` и `set_door` на клиенте ничего не меняют: положение контролов уже синхронизируется модом. Поворот и видимость групп, заданные в `on_tick`, видны на клиенте, потому что клиент выполняет тот же код.

После пяти ошибок выполнения скрипт этого определения отключается до перезагрузки.

## Таблица `stock`

Вызовы можно писать через точку или двоеточие.

| Метод | Действие |
| --- | --- |
| `id()` `name()` `uuid()` `texture()` | строковые свойства |
| `speed()` | скорость, км/ч (`Speed.metric`) |
| `gauge()` | колея в метрах |
| `tick()` | возраст сущности в тиках |
| `passengers()` | число пассажиров |
| `server()` | `true` на сервере |
| `control(group)` `pressed(group)` | положение и флаг нажатия контрола |
| `set_control(group, value)` | сервер, значение зажимается в 0..1 |
| `door_open(group)` | `control(group) > 0.75` |
| `set_door(group, open)` | сервер, пишет 1 или 0 в контрол двери |
| `coupled(side)` | сцепка спереди или сзади |
| `rotate_group(group, x, y, z)` | градусы, поверх штатной анимации |
| `translate_group(group, x, y, z)` | сдвиг в координатах модели |
| `group_visible(group, visible)` | `false` скрывает группу; `nil` снимает принудительную видимость |
| `clear_pose()` / `clear_pose(group)` | сброс позы |
| `log(message)` | строка в лог мода |
| `print(...)` | то же, глобальная функция |

`group` — имя группы OBJ (`FAN_1`), `group` у контролов — control group (`CG` в имени или в `widgets`).

Глобально доступны `math` (`abs`, `floor`, `ceil`, `sqrt`, `sin`, `cos`, `rad`, `deg`, `min`, `max`, `pi`), `string` (`len`, `sub`, `format`, `lower`, `upper`), `table.insert`, `table.remove`, `pairs`, `ipairs`, `type`, `tonumber`, `tostring`, `pcall`, `assert`.

## Пример

```lua
function on_tick(stock)
  local spinning = math.abs(stock.speed()) > 1
  stock.group_visible("FAN_BLUR", spinning)
  if spinning then
    stock.rotate_group("FAN_1", 0, 0, stock.tick() * 17)
  end
end

function on_couple(stock, side)
  stock.log("coupled at " .. side)
end

function on_door(stock, group, open)
  if open then
    stock.set_control("CAB_LIGHT", 1)
  end
end
```
