# Repository Guidelines

## Project Structure

Cristnights contains Layout Camera, a client-side Fabric mod for Minecraft Java Edition 26.3. Java sources live in `src/ru/layoutcamera/`, with game hooks in `mixin/`. The `resources/` directory contains Fabric metadata, the mixin registry, and bundled mob configuration templates. The root JAR is the current build. Mob definitions are loaded directly from `%USERPROFILE%\Desktop\Cristnights\config\layout-camera\mobs.json`. Saved zones and placements use the Minecraft game directory.

## Target Versions

- Minecraft Java Edition 26.3.
- Fabric Loader 0.20.1 (profile fabric-loader-0.20.1-26.3).
- JDK 25 for compilation. Fabric API is not required.

## Build and Run

From the project root, run in PowerShell with JDK 25 on PATH.

```powershell
$mc = Join-Path $env:APPDATA '.minecraft'
$libs = Get-ChildItem "$mc\libraries" -Recurse -Filter *.jar |
    Where-Object { $_.Name -notmatch '-natives-' }
$cp = (@("$mc\versions\26.3\26.3.jar") + $libs.FullName) -join ';'
$sources = (Get-ChildItem src -Recurse -Filter *.java).FullName
javac --release 25 -encoding UTF-8 -proc:none -cp $cp -d build/classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
$version = (Get-Content resources/fabric.mod.json -Raw | ConvertFrom-Json).version
jar --create --file "layout-camera-$version-mc26.3.jar" -C build/classes . -C resources .
```

Compile into an empty `build/classes` directory for each release. After every successful mod build, immediately install the new Layout Camera JAR in `%APPDATA%\.minecraft\mods`. Move older Layout Camera JARs from the project root and the Minecraft mods directory to the project's `archive/` directory. When both locations contain an identical old JAR, keep one archived copy. Verify that both the project root and mods contain only the new Layout Camera JAR. If Minecraft locks the old file, close the game before completing the replacement.

## Coding and Configuration

Use four-space indentation, UTF-8, PascalCase for classes, and camelCase for methods and fields. Keep mixin callbacks focused, prefix their names with `layoutcamera$`, and register new mixins in `resources/layoutcamera.mixins.json`. Keep all planning mob definitions in one shared UTF-8 config. Use a separate section keyed by a unique mob ID for each mob. Include its name, attack speed, attack type, damage, defense, mob type, armor, and health. Use `planning_chicken.properties` as the legacy reference, and migrate its values into the shared config when implementing it. Preserve user overrides and saved placements.

Update the Russian README.md in the same change whenever a command or configuration setting is added or changed. Document command syntax and behavior, and describe configuration values, defaults, and scope.

## Verification and Changes

Compile and inspect the JAR with `jar tf <file.jar>`. Check that it contains the mod metadata, mixin registry, compiled classes, and mob templates. This checkout has no automated test suite. When game testing is authorized, verify the affected behavior with the Fabric profile and record what was tested. If changes are submitted for review, summarize behavior changes, validation, and any impact on saved data.

## Реализованное состояние проекта на 18 ноября 2026

Текущая сборка — Layout Camera 1.16.3. Основной пользовательский справочник находится в README.md. При продолжении работы сверяй описанное поведение с исходниками и сохраняй пользовательские изменения конфигов.

### Команды

Все команды зарегистрированы в LayoutCommands и доступны владельцу одиночного мира.

| Команда | Назначение |
| --- | --- |
| `/givetool` | Выдаёт палку «Выделитель зон». |
| `/givepet <id>` | Выдаёт настроенный предмет призыва моба из mobs.json. Поддерживает полный ID и короткий ID в пространстве layoutcamera. |
| `/startspawn` | Запускает последовательность появления мобов в красных зонах текущего измерения. |

Проверка доступа к командам должна корректно обрабатывать служебный CommandSourceStack с null в getServer(). Такой объект используется при построении пакета команд во время входа в мир. Ошибка здесь уже приводила к сообщению «Неверные данные игрока».

### Зоны и управление

| Код | Название | Цвет |
| --- | --- | --- |
| `blue` | Верхняя зона | Синий |
| `green` | Нижняя зона | Зелёный |
| `red` | Зона спавна | Красный |
| `white` | Зона финиша | Белый |

В обычном режиме палка выбирает два угла левой кнопкой мыши. Shift циклически переключает blue, green, red, white. Нажатие внутри зоны активного типа при отсутствии первого угла удаляет её. Сохранение выполняется через временный файл и атомарную замену.

J переключает режим планирования. Caps Lock и Num Lock допускаются, сочетания с Shift, Ctrl, Alt и Super игнорируются. Вход возможен при свободном курсоре в мире с закрытым меню. Камера фиксирует наклон и направление, курсор освобождается, хотбар отображается для перетаскивания.

Создание и удаление зон, а также переключение типа палки заблокированы в режиме планирования. Предмет из /givepet перетаскивается из хотбара на точку разрешённой зоны. Для встроенной курицы также работают обычное яйцо и яйцо призыва курицы. Точки предназначены для зон 2×2 блока высотой в один блок. Направление размещения выбирается курсором с шагом 90 градусов.

Во время перетаскивания показываются свободные подходящие места и области спавна и финиша. Неподходящие Верхние и Нижние зоны скрыты. Занятые точки исключаются из выбора. Правый клик выбирает размещённого моба, кнопка удаления убирает его и сохраняет изменения.

Белая зона удаляет наступивших на неё серверных мобов без дропа. Игроки сохраняются. Удаление выполняется на встроенном сервере.

### Конфигурация

Оба основных JSON-файла читаются прямо из `%USERPROFILE%\Desktop\Cristnights\config\layout-camera\`. Путь строится от Java user.home. Активными файлами являются mobs.json и spawn.json в проекте. Прежние копии под .minecraft не используются для этих двух конфигов.

В mobs.json каждая запись верхнего уровня задаёт уникальный ID. Например, layoutcamera:chicken_bab. Поле model задаёт существующий тип Minecraft. Все определения находятся в одном UTF-8 JSON.

| Поле mobs.json | Поведение и значение по умолчанию |
| --- | --- |
| `model` | Обязательный ID модели и типа сущности Minecraft. |
| `name` | Имя моба и подпись предмета. Без поля используется имя типа Minecraft. |
| `summon_item` | Предмет призыва, по умолчанию minecraft:clay_ball. |
| `health` | Максимальное здоровье, минимум 1. Без поля сохраняется базовое значение модели. |
| `armor` | Броня, минимум 0. Без поля сохраняется базовое значение модели. |
| `damage` | Урон, минимум 0. В бою фракций ноль и отсутствие поля используют положительный базовый урон модели или 2. |
| `attack_speed` | Атак в секунду, минимум 0. В бою фракций ноль и отсутствие поля используют одну атаку в секунду. |
| `can_move` | По умолчанию true. false отключает обычный ИИ. Защитник атакует рядом, сохраняя неподвижность. Волна принудительно включает движение к финишу. |
| `can_place` | Размещение в планировании, по умолчанию true. |
| `placement_zone` | Один код разрешённой зоны, по умолчанию blue. |
| `faction` | peaceful или hostile. Без поля монстры Minecraft считаются hostile, другие типы peaceful. |
| `attack_zones` | Список разрешённых зон целей для моба, появившегося из зоны спавна. Имеет приоритет над настройкой волны. Пустой список запрещает выбор целей. |
| `attack_block` | Число одновременно сдерживаемых мобов волны. Целое от 0, по умолчанию 1. |
| `attack_type` | Сохранённое текстовое поле прежнего конфига. Текущий бой использует ближнюю атаку. |
| `mob_type` | Сохранённое текстовое поле прежнего конфига. Фракция определяется faction. |
| `defense` | Сохранённое числовое поле прежнего конфига. Текущую броню задаёт armor. |

MobConfig поддерживает прежний planning_chicken.properties для переноса настроек курицы и запасной загрузки. Поле id в старом файле обозначает ID прежней курицы. Сохраняй пользовательские значения при миграции.

| Поле spawn.json | Поведение и значение по умолчанию |
| --- | --- |
| `order` | Обязательный непустой список ID Minecraft или мобов из mobs.json. Порядок повторяется циклически. |
| `interval_ticks` | Обязательный положительный целый интервал. 20 тиков примерно равны секунде. |
| `total_enemies` | Обязательный положительный целый общий лимит волны по всем зонам. |
| `finish_search_radius` | Положительный целый радиус поиска финиша, по умолчанию 50 блоков. |
| `attack_zones` | Общий список зон целей для волны. Без поля разрешены все четыре зоны. Настройка конкретного моба имеет приоритет. |

Редактирование конфигов применяется при последующем запуске команды или новом размещении. Текущий запуск волны использует загруженные при старте настройки.

### Волны, маршрут и бой

SpawnZones распределяет появления между красными зонами текущего измерения. При отсутствии свободного места повторяет попытку, сохраняя счётчик. Повторный запуск во время появления текущей волны отклоняется. После выдачи всего лимита можно запускать следующую волну.

Для каждой точки появления выбирается ближайшая белая зона в заданном радиусе. Радиус учитывает расстояние до доступной точки на верхней поверхности зоны. Зоны спавна без подходящего финиша исключаются. Если подходящих зон нет, показывается ошибка «Нету зоны спавна с финишем в радиусе … блоков».

FinishRouteGoal ведёт моба к финишу. Маршрут сохраняется в тегах сущности и восстанавливается при загрузке. На мирной сложности волновые монстры создаются с обходом проверки сложности, а их удаление из-за мирной сложности подавляется. Проверка доступных функций мира сохраняется.

С версии 1.16.3 мобам с маршрутом волны запрещены автоматический подъём, наземные прыжки и лазание. Ограничение действует во время движения и боя и восстанавливается с тегом маршрута. Основные перехваты находятся в LivingEntityMixin.

FactionCombat задаёт мирную и злую стороны и выбирает противников в радиусе 12 блоков с проверкой видимости. На старте волны расставленные в планировании экземпляры создаются на сервере как временные защитники. Их клиентские модели скрываются на время участия. Временные защитники не записываются в сохранение сущностей. После окончания боя с волной они удаляются, клиентская расстановка восстанавливается.

Моб волны выбирает противника впереди по направлению к финишу. AttackZones учитывает зону размещения защитника. При занятой цели маршрут приостанавливается, после её гибели продолжается. Для каждого защитника AttackBlocks резервирует до attack_block атакующих. Остальные проходят мимо. Место освобождается при гибели или удалении атакующего. Значение 0 запрещает удержание.

### Сохранения и совместимость

Зоны и расстановки остаются под `%APPDATA%\.minecraft\config\layout-camera\zones\`. Ключ файла зависит от мира или сервера и измерения. Путь к расстановкам сохраняет расширение .chickens для совместимости.

Читаются записи расстановок из четырёх полей x y z yaw для старых куриц, пяти полей с ID моба и шести полей с ID и кодом зоны. При сохранении новых размещений записывается их зона. Для старых записей зона определяется по существующим зонам и настройкам моба. При ошибке чтения сохранений блокируется их перезапись.

### Карта исходников и проверенные исправления

| Файл | Ответственность |
| --- | --- |
| LayoutCamera.java и mixin/ | Камера, горячая клавиша, хотбар, управление и игровые перехваты. |
| LayoutCommands.java | Регистрация трёх команд, проверка владельца, выдача инструментов. |
| ZoneSelection.java | Выбор, цвета, отображение, хранение зон, точки размещения и финиш. |
| PetConfig.java и MobConfig.java | Основной JSON, проверка полей, поддержка старых параметров курицы. |
| Pets.java | Предметы призыва, создание и настройка обычных мобов. |
| PlanningDrag.java и PlanningMobs.java | Перетаскивание, модели расстановки, сохранение, удаление и серверные защитники. |
| SpawnZones.java и FinishRouteGoal.java | Волны, финиши, маршруты, временные защитники и очистка запуска. |
| FactionCombat.java, AttackZones.java и AttackBlocks.java | Стороны боя, выбор целей, разрешённые зоны и лимиты сдерживания. |

В ходе разработки проверялись сборка, содержимое JAR, разбор конфигов, регистрация команд и пакет входа, совместимость форматов расстановок, фильтры зон и радиус финиша. Изолированные проверки выполнялись в рабочей папке чата. Полного автоматического набора тестов в проекте нет. Поведение боя и последнего запрета подъёма в игре остаётся неподтверждённым. При следующих проверках явно указывай, что проверено в игре.

Исправленные причины прежних сбоев включают null-сервер при проверке команд, ввод J с lock-модификаторами, чтение устаревшей копии mobs.json из папки игры и ошибку «неизвестный ID» при попытке создания зомби на мирной сложности.

При сборке используй зависимости из манифеста версии 26.3 для точного выбора версий библиотек. Чтение всех JAR из libraries одновременно уже приводило к NoSuchMethodError в authlib при запуске проверок. Для mixin требуется библиотека sponge-mixin из установленного Fabric. При ошибке доступа jar к временной папке передавай Java-параметр -Djava.io.tmpdir с доступной рабочей директорией.
