# Repository Guidelines

## Project Structure

Cristnights contains Layout Camera, a client-side Fabric mod for Minecraft Java Edition 26.3. Java sources live in `src/ru/layoutcamera/`, with game hooks in `mixin/`. The `resources/` directory contains Fabric metadata, the mixin registry, and bundled mob configuration templates. The root JAR is the current build. Mob definitions are loaded directly from `%USERPROFILE%\Desktop\Cristnights\config\layout-camera\mobs.json`. Saved zones and placements use the Minecraft game directory.

## Target Versions

- Minecraft Java Edition 26.3.
- Fabric Loader 0.20.1 (profile fabric-loader-0.20.1-26.3).
- JDK 25 for compilation. Fabric API is not required.

## Build and Run

From the project root, run in PowerShell with JDK 25 on PATH and an empty build/classes directory. Resolve libraries from the installed Minecraft version and Fabric profile manifests. The Fabric profile supplies sponge-mixin. Using every JAR in the libraries directory can select an incompatible authlib version.

```powershell
$mc = Join-Path $env:APPDATA '.minecraft'
$fabricId = 'fabric-loader-0.20.1-26.3'
$vanilla = Get-Content "$mc/versions/26.3/26.3.json" -Raw | ConvertFrom-Json
$fabric = Get-Content "$mc/versions/$fabricId/$fabricId.json" -Raw | ConvertFrom-Json
$paths = @($vanilla.libraries | ForEach-Object { $_.downloads.artifact.path }) +
    @($fabric.libraries | ForEach-Object {
        $group, $artifact, $libraryVersion = $_.name.Split(':')
        "$($group.Replace('.', '/'))/$artifact/$libraryVersion/$artifact-$libraryVersion.jar"
    })
$libs = $paths | Where-Object { $_ -and (Test-Path -LiteralPath "$mc/libraries/$_") } |
    ForEach-Object { "$mc/libraries/$_" }
$cp = (@("$mc/versions/26.3/26.3.jar") + @($libs)) -join ';'
$sources = (Get-ChildItem src -Recurse -Filter *.java).FullName
javac --release 25 -encoding UTF-8 -proc:none -cp $cp -d build/classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
$version = (Get-Content resources/fabric.mod.json -Raw | ConvertFrom-Json).version
$jarName = "layout-camera-$version-mc26.3.jar"
jar "-J-Djava.io.tmpdir=$((Resolve-Path build).Path)" --create --file $jarName -C build/classes . -C resources .
if ($LASTEXITCODE -ne 0) { throw 'JAR packaging failed' }
jar tf $jarName
```

Compile into an empty `build/classes` directory for each release. After every successful mod build, immediately install the new Layout Camera JAR in `%APPDATA%\.minecraft\mods`. Move older Layout Camera JARs from the project root and the Minecraft mods directory to the project's `archive/` directory. When both locations contain an identical old JAR, keep one archived copy. Verify that both the project root and mods contain only the new Layout Camera JAR. If Minecraft locks the old file, close the game before completing the replacement.

## Coding and Configuration

Use four-space indentation, UTF-8, PascalCase for classes, and camelCase for methods and fields. Keep mixin callbacks focused, prefix their names with `layoutcamera$`, and register new mixins in `resources/layoutcamera.mixins.json`. Keep all planning mob definitions in one shared UTF-8 config. Use a separate section keyed by a unique mob ID for each mob. Include its name, attack speed, attack type, damage, defense, mob type, armor, and health. Use `planning_chicken.properties` as the legacy reference, and migrate its values into the shared config when implementing it. Preserve user overrides and saved placements.

Update the Russian README.md in the same change whenever a command or configuration setting is added or changed. Document command syntax and behavior, and describe configuration values, defaults, and scope.

## Verification and Changes

Compile and inspect the JAR with `jar tf <file.jar>`. Check that it contains the mod metadata, mixin registry, compiled classes, and mob templates. This checkout has no automated test suite. When game testing is authorized, verify the affected behavior with the Fabric profile and record what was tested. If changes are submitted for review, summarize behavior changes, validation, and any impact on saved data.

## Текущее состояние проекта

Текущая сборка — Layout Camera 1.18.18. [README.md](README.md) содержит пользовательский гайд по уровням, полный список команд и справочник по JSON с ограничениями и значениями по умолчанию. Сверяй изменения с исходниками и сохраняй пользовательские настройки.

### Команды, уровни и сохранения

- Команды регистрирует LayoutCommands на встроенном сервере. Хозяину доступны /givetool, /layoutlevel, /createstage, /startspawn, /removestage и /removestagecopy. Гости по LAN могут призывать сохранённые уровни и убирать свои копии. /givepet доступна хозяину и гостям внутри их собственных копий. Предикаты доступа учитывают служебный CommandSourceStack с null в getServer() при отправке дерева команд.
- /layoutlevel create начинает выделение двух углов исходной области палкой. Исходные границы и настройки зон сохраняются в *.levels.json по миру, измерению и аккаунту хозяина. Можно менять настройки зоны по ID через /layoutlevel zone или отредактировать JSON и выполнить /layoutlevel reload.
- При первом /createstage <имя> хозяин сохраняет исходные блоки, зоны и расстановку защитников в Stage/<имя>/stage.nbt. Настройки каждой красной зоны по ID лежат в Stage/<имя>/spawn.json. При следующих вызовах используются сохранённые блоки. Если исходная область совпадает по ID, повторный вызов хозяином обновляет расстановку и добавляет недостающие зоны.
- Копия создаётся у верхнего предела строительства. Между краями копий разных игроков в одном измерении должно быть минимум 1000 блоков по горизонтали. Границы и модели расстановки копии получает её владелец. Блоки и боевые мобы видны в мире. Новая расстановка и изменения блоков внутри копии действуют до её удаления или замены.
- При удалении копии игрок возвращается в исходное измерение и точку входа. Отход дальше 20 блоков запускает предупреждение и отсчёт 10 секунд. Возврат в радиус отменяет отсчёт. /removestagecopy убирает собственную копию, /removestage убирает все копии по имени на встроенном сервере, /layoutlevel delete также убирает исходную разметку. Все три команды сохраняют папку Stage/<имя>.
- Зоны и расстановки хранятся под %APPDATA%\.minecraft\config\layout-camera\zones. Расширение .chickens сохраняется для совместимости. PlanningMobs читает строки из четырёх полей x y z yaw, пяти полей с ID моба и шести полей с ID и кодом зоны. При ошибке чтения сохранений блокируется их перезапись.

### Камера, разметка и сетка атаки

- J переключает режим планирования. Caps Lock и Num Lock допускаются, сочетания с Shift, Ctrl, Alt и Super игнорируются. Камера фиксирует направление и наклон, курсор освобождается для работы с хотбаром. В обычном режиме Shift переключает цвет палки, ЛКМ выделяет углы или удаляет зону активного типа. В планировании редактирование границ и смена цвета заблокированы.
- Верхняя зона blue и Нижняя зона green принимают защитников на свободных площадках 2×2 блока высотой в один блок. Модель перетаскиваемого моба поворачивается с шагом 90°. В копии красная зона спавна и белая зона финиша видны в планировании сразу, синие и зелёные зоны показываются при наведении или выборе места. Блоки перекрывают разметку этих зон и маркеры размещения.
- AttackGrid описывает клетки 2×2 блока, ориентированные по направлению моба. В центре находится M, а @ включает атаку в центральной клетке. # включает клетку атаки, точка оставляет её пустой. ZoneSelection.drawAttackGrid рисует оранжевые полупрозрачные диагональные полосы и контуры поверх блоков через setAlwaysOnTop(). Сетка видна при наведении перетаскиваемого моба на подходящую точку, скрывается при размещении и переключается ПКМ по размещённому мобу.

### Конфигурация, волны и бой

- mobs.json и общий spawn.json читаются из %USERPROFILE%\Desktop\Cristnights\config\layout-camera. Путь строится от Java user.home. PetConfig разбирает общие определения мобов, MobConfig поддерживает запасной planning_chicken.properties. Сохраняй значения из старых конфигов при миграции.
- movement_speed задаёт базовую скорость ходьбы и полёта для поддерживаемых атрибутов, диапазон от 0 до 1024. attack_knockback задаёт силу отбрасывания прямых атак, диапазон от 0 до 5. При 0 отбрасывание отключено, при отсутствии поля действует отбрасывание Minecraft. Сопротивление цели учитывается.
- /startspawn проверяет расстояние от игрока до ближайшей точки красной зоны по трём осям. Порог старта составляет 10 блоков. Зоны уровня используют индивидуальные настройки спавна и финиши своего уровня. Общий spawn.json задаёт суммарный лимит для ближайших зон вне уровней. Если место спавна занято, попытка повторяется без расхода лимита.
- Mob с маршрутом идёт к белому финишу. Маршрут восстанавливается по тегам сущности, в том числе на мирной сложности. Для мобов волны запрещены автоматический подъём, наземные прыжки и лазание. Белая зона удаляет подходящих серверных мобов без дропа.
- При старте волны расстановка превращается во временных серверных защитников. Выжившие возвращаются в клиентскую расстановку. Гибель защитника освобождает площадку и убирает её запись из текущей расстановки. Изменения в призванной копии действуют только в ней.
- FactionCombat учитывает фракции и видимость. Защитник с attack_grid атакует противника из волны, если его ноги находятся в активной клетке, разница высот не больше 12 блоков и истёк интервал атаки. Отрисовка сетки поверх блоков сохраняет проверку видимости цели. Моб волны выбирает цели впереди, учитывает attack_zones и останавливается при наличии свободного места по attack_block.

### Карта исходников

| Файл | Ответственность |
| --- | --- |
| LayoutCamera.java и mixin/ | Камера, горячие клавиши, хотбар и игровые перехваты. |
| LayoutCommands.java | Регистрация команд, проверка владельца и доступ по LAN. |
| ZoneSelection.java и LocalLevels.java | Разметка и сохранение зон, настройки исходных областей и отрисовка сетки атаки. |
| StageFiles.java, StageCopies.java и StagePlanning.java | Сохранения Stage, призыв и уборка копий, передача разметки игроку. |
| PetConfig.java, MobConfig.java и Pets.java | Конфиги мобов, прежние настройки курицы, предметы и призыв. |
| PlanningDrag.java и PlanningMobs.java | Перетаскивание, клиентские модели, расстановка и серверные защитники. |
| SpawnZones.java и FinishRouteGoal.java | Запуск волн, поиск финишей, маршруты и очистка. |
| FactionCombat.java, AttackGrid.java, AttackZones.java, AttackBlocks.java и AttackKnockback.java | Фракции, выбор целей, сетка, сдерживание и отбрасывание. |

Для 1.18.18 проверены сборка, содержимое JAR, совпадение установленного JAR с проектным и флаг отрисовки полос и контуров поверх блоков для четырёх поворотов. Эта проверка выполнялась без запуска Minecraft. Автоматического набора тестов в checkout нет.
