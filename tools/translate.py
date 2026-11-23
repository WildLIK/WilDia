#!/usr/bin/env python3
"""
Rule-based Russian translation engine for SAE J2012 DTC titles and short
parameter phrases.

Statuses produced:
  verified — hand-written curated translations (tools/data/ru_curated*.yaml)
  auto     — produced by this deterministic rule engine, NOT reviewed
  (none)   — engine could not translate; keep the original English text

The engine never invents content: it maps recognised English component and
fault phrases to fixed Russian equivalents. If any part of the title is not
recognised, it refuses to translate (returns None). Result quality is
therefore "literal technical translation", still marked 'auto'.
"""
import re

# ---------------------------------------------------------------------------
# Component lexicon: longest-match first. Keys are lowercase English phrases.
# ---------------------------------------------------------------------------
COMPONENTS = {
    "mass or volume air flow sensor": "датчик массового/объёмного расхода воздуха (MAF)",
    "mass or volume air flow": "массовый/объёмный расход воздуха (MAF)",
    "mass air flow sensor": "датчик массового расхода воздуха (MAF)",
    "mass air flow": "массовый расход воздуха (MAF)",
    "manifold absolute pressure sensor": "датчик абсолютного давления во впускном коллекторе (MAP)",
    "manifold absolute pressure": "абсолютное давление во впускном коллекторе (MAP)",
    "barometric pressure": "барометрическое давление",
    "engine coolant temperature": "температура охлаждающей жидкости",
    "coolant temperature": "температура охлаждающей жидкости",
    "intake air temperature": "температура впускного воздуха",
    "ambient air temperature": "температура окружающего воздуха",
    "charge air temperature": "температура наддувочного воздуха",
    "throttle position sensor": "датчик положения дроссельной заслонки",
    "throttle position": "положение дроссельной заслонки",
    "throttle valve": "дроссельная заслонка",
    "throttle body": "дроссельный узел",
    "throttle actuator control": "привод дроссельной заслонки",
    "throttle actuator": "привод дроссельной заслонки",
    "accelerator pedal position": "положение педали акселератора",
    "accelerator pedal": "педаль акселератора",
    "cruise control": "круиз-контроль",
    "camshaft position": "положение распределительного вала (ДПРВ)",
    "crankshaft position": "положение коленчатого вала (ДПКВ)",
    "camshaft profile": "профиль кулачков распределительного вала",
    "camshaft": "распределительный вал",
    "crankshaft": "коленчатый вал",
    "engine speed": "обороты двигателя",
    "engine torque": "крутящий момент двигателя",
    "engine oil pressure": "давление моторного масла",
    "engine oil temperature": "температура моторного масла",
    "oil pressure": "давление моторного масла",
    "oil level": "уровень моторного масла",
    "oxygen sensor": "датчик кислорода (ДК)",
    "o2 sensor": "датчик кислорода (ДК)",
    "turbine": "турбина",
    "heated oxygen sensor": "подогреваемый датчик кислорода (ДК)",
    "air/fuel ratio sensor": "датчик соотношения воздух/топливо (широкополосный ДК)",
    "air fuel ratio sensor": "датчик соотношения воздух/топливо (широкополосный ДК)",
    "fuel/air ratio": "соотношение воздух/топливо",
    "lambda sensor": "лямбда-датчик (ДК)",
    "o2 sensor heater": "нагреватель датчика кислорода",
    "oxygen sensor heater": "нагреватель датчика кислорода",
    "heater": "нагреватель",
    "fuel rail pressure": "давление топлива в рампе",
    "fuel pressure regulator": "регулятор давления топлива",
    "fuel pressure": "давление топлива",
    "fuel level sensor": "датчик уровня топлива",
    "fuel level": "уровень топлива",
    "fuel temperature": "температура топлива",
    "fuel composition": "состав топлива",
    "fuel pump": "топливный насос",
    "fuel pump module": "модуль топливного насоса",
    "fuel pump relay": "реле топливного насоса",
    "fuel pump control": "управление топливным насосом",
    "fuel volume regulator": "регулятор объёма топлива",
    "fuel metering control valve": "клапан дозирования топлива",
    "fuel injection pump": "топливный насос высокого давления (ТНВД)",
    "injection pump": "топливный насос высокого давления (ТНВД)",
    "high pressure pump": "топливный насос высокого давления (ТНВД)",
    "fuel injector": "форсунка",
    "injector": "форсунка",
    "fuel filter": "топливный фильтр",
    "fuel system": "топливная система",
    "fuel rail": "топливная рампа",
    "fuel shut-off valve": "клапан отсечки топлива",
    "fuel shutoff valve": "клапан отсечки топлива",
    "shutoff valve": "клапан отсечки топлива",
    "camshaft position actuator": "муфта/актюатор изменения фаз газораспределения",
    "camshaft profile actuator": "актюатор профиля распределительного вала",
    "intake valve control solenoid": "соленоид управления впускными клапанами",
    "exhaust valve control solenoid": "соленоид управления выпускными клапанами",
    "intake valve": "впускной клапан",
    "exhaust valve": "выпускной клапан",
    "bypass valve": "клапан перепуска",
    "engine position system": "система определения положения коленчатого вала",
    "ho2s heater": "нагреватель подогреваемого датчика кислорода (ДК)",
    "ho2s": "подогреваемый датчик кислорода (ДК)",
    "a/f sensor": "датчик соотношения воздух/топливо (широкополосный ДК)",
    "boost": "наддув",
    "map/maf": "датчики MAP/MAF",
    "map": "абсолютное давление во впускном коллекторе (MAP)",
    "maf": "массовый расход воздуха (MAF)",
    "charge air cooler temperature sensor": "датчик температуры охладителя наддувочного воздуха (интеркулера)",
    "charge air cooler": "охладитель наддувочного воздуха (интеркулер)",
    "radiator coolant temperature sensor": "датчик температуры ОЖ радиатора",
    "radiator coolant temperature": "температура ОЖ радиатора",
    "air assisted injector": "форсунка с воздушным ассистированием",
    "fuel rail/system pressure": "давление топлива в рампе/системе",
    "low pressure fuel system": "контур низкого давления топлива",
    "fuel cooler pump": "насос охлаждения топлива",
    "fuel pressure relief control": "управление клапаном сброса давления топлива",
    "fuel pressure relief": "клапан сброса давления топлива",
    "supply voltage": "напряжение питания",
    "engine coolant flow": "поток охлаждающей жидкости",
    "intake air temperature sensor": "датчик температуры впускного воздуха",
    "ambient air temperature sensor": "датчик температуры окружающего воздуха",
    "throttle/pedal position sensor/switch": "датчик/выключатель положения дроссельной заслонки/педали",
    "throttle/pedal position sensor": "датчик положения дроссельной заслонки/педали",
    "throttle/pedal position": "положение дроссельной заслонки/педали",
    "turbocharger outlet pressure sensor": "датчик давления на выходе турбонагнетателя",
    "turbocharger/supercharger inlet pressure sensor": "датчик давления на входе турбонагнетателя",
    "humidity sensor": "датчик влажности",
    "exhaust heat exchanger": "теплообменник выхлопных газов",
    "exhaust": "выхлопные газы",
    "solenoid": "соленоид",
    "alternative fuel tank": "бак альтернативного топлива",
    "alternative fuel rail": "рампа альтернативного топлива",
    "cylinder head temperature sensor": "датчик температуры головки блока цилиндров (ГБЦ)",
    "cylinder head temperature": "температура головки блока цилиндров",
    "leak detection pump": "насос обнаружения утечек",
    "air filter": "воздушный фильтр",
    "fuel trim": "коррекция топлива (fuel trim)",
    "fuel": "топливо",
    "intake air": "впускной воздух",
    "ambient air": "окружающий воздух",
    "charge air": "наддувочный воздух",
    "coolant heater": "нагреватель охлаждающей жидкости",
    "coolant": "охлаждающая жидкость",
    "pressure sensor": "датчик давления",
    "level sensor": "датчик уровня",
    "temperature sensor": "датчик температуры",
    "reductant tank temperature sensor": "датчик температуры бака восстановителя (AdBlue)",
    "reductant quality sensor": "датчик качества восстановителя (AdBlue)",
    "reductant quality": "качество восстановителя (AdBlue)",
    "reductant injection air pump": "насос воздуха впрыска восстановителя (AdBlue)",
    "reductant injection air pressure control valve": "клапан управления давлением воздуха впрыска AdBlue",
    "reductant metering unit temperature sensor": "датчик температуры дозатора восстановителя (AdBlue)",
    "reductant metering unit": "дозатор восстановителя (AdBlue)",
    "reductant tank": "бак восстановителя (AdBlue)",
    "reductant/regeneration supply": "подача восстановителя/регенерации",
    "intake manifold tuning valve": "клапан настройки впускного коллектора (IMT)",
    "intake manifold tuning": "настройка впускного коллектора",
    "post catalyst fuel trim system": "коррекция топлива после нейтрализатора",
    "manifold absolute pressure/mass air flow": "датчики MAP/MAF",
    # --- P2xxx / diesel / SCR vocabulary ---
    "reductant injection valve": "клапан впрыска восстановителя (AdBlue)",
    "reductant injection air pressure sensor": "датчик давления воздуха впрыска восстановителя (AdBlue)",
    "reductant tank heater": "нагреватель бака восстановителя (AdBlue)",
    "reductant level sensor": "датчик уровня восстановителя (AdBlue)",
    "reductant pump": "насос восстановителя (AdBlue)",
    "reductant/regeneration supply control": "управление подачей восстановителя/регенерации",
    "reductant": "восстановитель (AdBlue)",
    "nox adsorber": "накопитель NOx",
    "evap fuel vapor temperature sensor": "датчик температуры паров топлива EVAP",
    "evap fuel vapor pressure sensor": "датчик давления паров топлива EVAP",
    "fuel fired heater": "топливный (жидкостный) отопитель",
    "intake manifold runner control actuator": "актюатор заслонок впускного коллектора",
    "charge air cooler bypass position sensor": "датчик положения клапана перепуска интеркулера",
    "charge air cooler bypass": "клапан перепуска интеркулера",
    "bypass control": "управление перепуском",
    "wastegate actuator": "актюатор клапана перепуска турбины (wastegate)",
    "fuel injection quantity": "цикловая подача топлива",
    "cylinder deactivation system": "система отключения цилиндров",
    "deactivation/intake valve control": "управление отключением цилиндров/впускными клапанами",
    "deactivation/exhaust valve control": "управление отключением цилиндров/выпускными клапанами",
    "engine shutoff solenoid": "соленоид остановки двигателя",
    "cold start injector": "форсунка холодного пуска",
    # --- U0xxx / communication ---
    "high speed can communication bus": "шина CAN высокоскоростная",
    "medium speed can communication bus": "шина CAN среднескоростная",
    "low speed can communication bus": "шина CAN низкоскоростная",
    "vehicle communication bus": "коммуникационная шина автомобиля",
    "control module communication bus": "коммуникационная шина модулей управления",
    "ecm/pcm": "ЭБУ двигателя",
    "ecm": "ЭБУ двигателя",
    "pcm": "ЭБУ силового агрегата",
    "tcm": "ЭБУ трансмиссии",
    "transfer case control module": "блок управления раздаточной коробкой",
    "gear shift control module": "блок управления переключением передач",
    "alternative fuel control module": "блок управления альтернативным топливом",
    "drive motor control module": "блок управления тяговым электродвигателем",
    "battery energy control module": "блок управления тяговой батареей",
    "emissions critical control information": "критичная для выбросов информация управления",
    "four-wheel drive clutch control module": "блок управления муфтой полного привода",
    "all wheel drive control module": "блок управления полным приводом",
    "pto control module": "блок управления валом отбора мощности (PTO)",
    "fuel additive control module": "блок управления дозированием присадок к топливу",
    "fuel cell control module": "блок управления топливным элементом",
    "exhaust gas sensor module": "модуль датчика отработавших газов",
    "rocker arm control module": "блок управления коромыслами клапанов",
    "bcm": "блок управления кузовом",
    "body control module": "блок управления кузовом",
    "instrument panel cluster": "щиток приборов",
    "gateway": "шлюз",
    "hvac control module": "блок управления климат-контролем",
    "hvac": "климат-контроль",
    "abs module": "модуль ABS",
    "steering column control module": "блок управления рулевой колонкой",
    "brake system control module": "модуль управления тормозной системой",
    "transmission fluid temperature sensor": "датчик температуры жидкости трансмиссии",
    "control module input power": "питание модуля управления",
    "control module ground": "масса модуля управления",
    "control module wake-up circuit": "цепь пробуждения модуля управления",
    "control module timer/clock": "таймер/тактовый генератор модуля управления",
    "control module disable": "цепь отключения модуля управления",
    "disable circuit": "цепь отключения",
    "ignition input": "вход зажигания",
    "accessory power relay": "реле питания аксессуаров",
    "retained accessory power": "поддерживаемое питание аксессуаров",
    "vehicle identification number": "идентификационный номер автомобиля (VIN)",
    # --- B0xxx / airbag ---
    "deployment control": "цепь срабатывания подушки безопасности",
    "deployment loop": "петля срабатывания подушки безопасности",
    "driver frontal": "фронтальная подушка безопасности водителя",
    "passenger frontal": "фронтальная подушка безопасности переднего пассажира",
    "center frontal": "центральная фронтальная подушка безопасности",
    "driver knee bolster": "коленная подушка безопасности водителя",
    "driver knee airbag": "коленная подушка безопасности водителя",
    "passenger knee airbag": "коленная подушка безопасности пассажира",
    "collapsible steering column": "рулевая колонка",
    "left side airbag": "боковая подушка безопасности слева",
    "right side airbag": "боковая подушка безопасности справа",
    "second row left side airbag": "боковая подушка безопасности 2-го ряда слева",
    "second row right side airbag": "боковая подушка безопасности 2-го ряда справа",
    "second row left frontal": "фронтальная подушка безопасности 2-го ряда слева",
    "second row right frontal": "фронтальная подушка безопасности 2-го ряда справа",
    "left curtain": "шторка безопасности слева",
    "right curtain": "шторка безопасности справа",
    "curtain": "шторка безопасности",
    "side airbag": "боковая подушка безопасности",
    "side deployment": "боковое срабатывание подушки безопасности",
    "deployment": "срабатывание подушки безопасности",
    "frontal": "фронтальная подушка безопасности",
    "airbag": "подушка безопасности",
    "knee airbag": "коленная подушка безопасности",
    "passenger knee bolster": "коленная подушка безопасности пассажира",
    "left frontal": "левая фронтальная подушка безопасности",
    "right frontal": "правая фронтальная подушка безопасности",
    "left side": "левая боковая",
    "right side": "правая боковая",
    "seat belt load limiter": "ограничитель нагрузки ремня безопасности",
    "restraints sensor": "датчик системы пассивной безопасности",
    "restraints": "система пассивной безопасности",
    "baro": "датчик барометрического давления",
    "vtec": "система VTEC",
    "eld": "датчик электрической нагрузки (ELD)",
    "roll over sensor": "датчик переворота автомобиля",
    "occupant classification system": "система классификации пассажира",
    "occupant position system": "система определения положения пассажира",
    "driver seat occupant classification sensor": "датчик классификации пассажира на сиденье водителя",
    "seat track position restraints sensor": "датчик положения сиденья (система пассивной безопасности)",
    "seat track position": "положение сиденья",
    "seat belt pretensioner deployment loop": "петля срабатывания преднатяжителя ремня безопасности",
    "seat belt pretensioner": "преднатяжитель ремня безопасности",
    "seat belt tension sensor": "датчик натяжения ремня безопасности",
    "seat belt sensor": "датчик ремня безопасности",
    "first row": "1-й ряд",
    "second row": "2-й ряд",
    "third row": "3-й ряд",
    "center": "центральный",
    "driver": "водитель",
    "passenger": "пассажир",
    # --- C0xxx / chassis ---
    "tone wheel": "зубчатое колесо датчика скорости колеса",
    "wheel speed sensor supply": "питание датчика скорости колеса",
    "wheel speed sensors rotation direction": "направление вращения датчиков скорости колёс",
    "left front inlet control": "управление впускным клапаном левого переднего колеса",
    "left front outlet control": "управление выпускным клапаном левого переднего колеса",
    "right front inlet control": "управление впускным клапаном правого переднего колеса",
    "right front outlet control": "управление выпускным клапаном правого переднего колеса",
    "left rear inlet control": "управление впускным клапаном левого заднего колеса",
    "left rear outlet control": "управление выпускным клапаном левого заднего колеса",
    "right rear inlet control": "управление впускным клапаном правого заднего колеса",
    "right rear outlet control": "управление выпускным клапаном правого заднего колеса",
    "left front": "левое переднее",
    "right front": "правое переднее",
    "left rear": "левое заднее",
    "right rear": "правое заднее",
    "stop lamp control": "управление стоп-сигналом",
    "stop lamp": "стоп-сигнал",
    "brake pedal feedback pressure solenoid": "соленоид обратной связи давления педали тормоза",
    "brake pedal feedback pressure sensor": "датчик обратной связи давления педали тормоза",
    "tcs control channel": "канал управления противобуксовочной системы (TCS)",
    "tcs": "противобуксовочная система (TCS)",
    "hydraulic release": "гидравлический сброс давления",
    "inlet control": "управление впускным клапаном",
    "outlet control": "управление выпускным клапаном",
    "wheel speed sensor": "датчик скорости колеса",
    "left": "левый",
    "right": "правый",
    "rear": "заднее",
    "front": "переднее",
    "brake booster travel sensor": "датчик хода штока вакуумного усилителя тормозов",
    "brake fluid level sensor/switch": "датчик/выключатель уровня тормозной жидкости",
    "brake fluid level": "уровень тормозной жидкости",
    "brake lining wear sensor": "датчик износа тормозных колодок",
    "abs control module": "модуль управления ABS",
    "steering wheel position sensor": "датчик положения рулевого колеса",
    "lateral acceleration sensor": "датчик бокового ускорения",
    "longitudinal acceleration sensor": "датчик продольного ускорения",
    "vertical acceleration sensor": "датчик вертикального ускорения",
    "yaw rate sensor": "датчик скорости поворота (рыскания)",
    "roll rate sensor": "датчик скорости крена",
    "pitch rate sensor": "датчик скорости тангажа",
    "multi-axis acceleration sensor": "многоосевой датчик ускорения",
    "stability system": "система курсовой устойчивости (ESP)",
    "low tire pressure": "низкое давление в шине",
    "tire diameter": "диаметр шины",
    "tire pressure monitor": "система контроля давления в шинах (TPMS)",
    "variable effort steering": "рулевое управление с переменным усилием",
    "wheel speed mismatch": "несоответствие скоростей колёс",
    "abs malfunction indicator": "индикатор неисправности ABS",
    "brake system malfunction indicator": "индикатор неисправности тормозной системы",
    "tire pressure monitor malfunction indicator": "индикатор неисправности системы TPMS",
    "traction active indicator": "индикатор активной работы противобуксовочной системы",
    "traction disable indicator": "индикатор отключения противобуксовочной системы",
    "vehicle dynamics indicator": "индикатор системы стабилизации",
    "tcs disable switch": "выключатель отключения TCS",
    "tcs mode control": "управление режимами TCS",
    "4wd/awd power transfer unit actuator": "актюатор раздаточной коробки (4WD)",
    "4wd/awd power transfer unit position sensor": "датчик положения раздаточной коробки (4WD)",
    "4wd/awd power transfer unit temperature sensor": "датчик температуры раздаточной коробки (4WD)",
    "4wd/awd rear differential unit actuator": "актюатор заднего дифференциала (4WD)",
    "4wd/awd rear differential unit temperature sensor": "датчик температуры заднего дифференциала (4WD)",
    "4wd/awd power transfer unit": "раздаточная коробка (4WD)",
    "4wd/awd rear differential unit": "задний дифференциал (4WD)",
    "position sensor": "датчик положения",
    "delivered driving torque": "фактический крутящий момент привода",
    "requested driving torque": "запрашиваемый крутящий момент привода",
    "driving torque": "крутящий момент привода",
    "pwm": "ШИМ (PWM)",
    "traction control": "противобуксовочная система",
    # --- Hybrid / EV ---
    "hybrid/ev battery voltage sense": "измерение напряжения тяговой батареи",
    "hybrid/ev battery temperature sensor": "датчик температуры тяговой батареи",
    "hybrid/ev battery cell balancing": "балансировка ячеек тяговой батареи",
    "hybrid/ev battery pack cooling fan": "вентилятор охлаждения тяговой батареи",
    "hybrid/ev battery pack voltage sense": "измерение напряжения блока тяговой батареи",
    "hybrid/ev battery pack coolant control": "управление охлаждением тяговой батареи",
    "hybrid/ev battery pack coolant temperature sensor": "датчик температуры ОЖ тяговой батареи",
    "hybrid/ev battery pack coolant pump": "насос ОЖ тяговой батареи",
    "hybrid/ev battery pack": "блок тяговой батареи",
    "hybrid/ev battery": "тяговая батарея",
    "hybrid/ev electronics coolant pump": "насос ОЖ электроники гибрида",
    "electric/auxiliary transmission fluid pump": "электрический/вспомогательный насос жидкости трансмиссии",
    "transmission fluid pressure sensor": "датчик давления жидкости трансмиссии",
    "drive motor inverter temperature sensor": "датчик температуры инвертора тягового двигателя",
    "drive motor inverter": "инвертор тягового двигателя",
    "drive motor position sensor": "датчик положения тягового двигателя",
    "drive motor temperature sensor": "датчик температуры тягового двигателя",
    "drive motor": "тяговый электродвигатель",
    "battery charging system positive": "положительная цепь системы зарядки АКБ",
    "battery charging system negative": "отрицательная цепь системы зарядки АКБ",
    "battery charging system": "система зарядки АКБ",
    "dc/dc converter temperature sensor": "датчик температуры DC/DC-преобразователя",
    "dc/dc converter": "DC/DC-преобразователь",
    "battery charger coupler temperature sensor": "датчик температуры разъёма зарядного устройства",
    "battery charger coupler": "разъём зарядного устройства",
    "battery charger": "зарядное устройство",
    "knock/combustion vibration sensor": "датчик детонации/вибраций сгорания",
    "nox sensor heater sense circuit": "контур контроля нагревателя датчика NOx",
    "intermediate shaft speed sensor": "датчик скорости промежуточного вала",
    "input/turbine shaft speed sensor": "датчик скорости входного вала/турбины",
    "exhaust aftertreatment fuel injector": "форсунка подачи топлива в систему дожига выхлопа",
    "hybrid battery pack": "блок тяговой батареи",
    "hybrid/ev battery pack current sensor": "датчик тока тяговой батареи",
    "hybrid/ev electronics": "электроника гибрида",
    "high voltage system interlock": "контур блокировки системы высокого напряжения (HVIL)",
    "high voltage system": "система высокого напряжения",
    "generator inverter temperature sensor": "датчик температуры инвертора генератора",
    "generator inverter": "инвертор генератора",
    "motor electronics coolant temperature sensor": "датчик температуры ОЖ электроники электродвигателя",
    "motor electronics": "электроника электродвигателя",
    "battery charger input voltage sensor": "датчик входного напряжения зарядного устройства",
    "transmission park position sensor/switch": "датчик/выключатель положения парковки АКПП",
    "restraints sensor module": "модуль датчика системы пассивной безопасности",
    "restraints system sensor module": "модуль датчика системы пассивной безопасности",
    "image processing module": "модуль обработки изображений (камера)",
    "electric/auxiliary transmission fluid pump motor": "электродвигатель насоса жидкости трансмиссии",
    "electric/auxiliary transmission fluid pump control module": "блок управления насосом жидкости трансмиссии",
    "contactor": "контактор",
    "o2 sensor positive current control": "управление положительным током датчика кислорода",
    "o2 sensor negative current control": "управление отрицательным током датчика кислорода",
    "o2 sensor pumping current trim circuit": "цепь коррекции тока накачки датчика кислорода",
    "o2 sensor pumping current": "ток накачки датчика кислорода",
    "diesel intake air flow": "поток впускного воздуха дизеля (дросселирование дизеля)",
    "cold start fuel injection control": "управление впрыском при холодном пуске",
    "cold start fuel injection": "впрыск топлива при холодном пуске",
    "engine shutoff valve": "клапан остановки двигателя",
    "cylinder fuel shut-off": "отключение подачи топлива по цилиндру",
    "knock sensor": "датчик детонации",
    "knock control": "управление детонацией",
    "ignition coil": "катушка зажигания",
    "ignition system": "система зажигания",
    "ignition/distributor": "зажигание/распределитель",
    "distributor": "распределитель зажигания",
    "spark plug": "свеча зажигания",
    "glow plug": "свеча накаливания",
    "misfire": "пропуски зажигания",
    "turbocharger/supercharger": "турбонагнетатель/механический нагнетатель",
    "turbocharger boost sensor": "датчик давления наддува",
    "turbocharger": "турбонагнетатель",
    "supercharger": "механический нагнетатель",
    "boost pressure": "давление наддува",
    "boost control": "управление наддувом",
    "wastegate": "клапан перепуска турбонагнетателя (wastegate)",
    "variable valve timing": "изменение фаз газораспределения (VVT)",
    "valve timing": "фазы газораспределения",
    "exhaust gas recirculation": "рециркуляция отработавших газов (EGR)",
    "egr": "рециркуляция отработавших газов (EGR)",
    "exhaust gas temperature": "температура отработавших газов",
    "exhaust pressure": "давление отработавших газов",
    "catalyst system efficiency": "эффективность нейтрализатора",
    "catalyst temperature": "температура нейтрализатора",
    "catalyst": "нейтрализатор",
    "three-way catalyst": "трёхкомпонентный нейтрализатор",
    "particulate filter": "сажевый фильтр (DPF)",
    "diesel particulate filter": "сажевый фильтр (DPF)",
    "dpf": "сажевый фильтр (DPF)",
    "nox sensor": "датчик NOx",
    "nox trap efficiency": "эффективность накопителя NOx",
    "nox trap": "накопитель NOx",
    "nox": "оксиды азота (NOx)",
    "selective catalytic reduction": "селективная каталитическая нейтрализация (SCR)",
    "scr": "селективная каталитическая нейтрализация (SCR)",
    "def": "жидкость выхлопной системы (AdBlue/DEF)",
    "urea": "жидкость AdBlue (мочевина)",
    "evaporative emission system": "система улавливания паров топлива (EVAP)",
    "evaporative emission control system": "система улавливания паров топлива (EVAP)",
    "evap emission control system": "система улавливания паров топлива (EVAP)",
    "evap system": "система улавливания паров топлива (EVAP)",
    "evap purge control valve": "клапан продувки системы EVAP",
    "purge control valve": "клапан продувки системы EVAP",
    "purge valve": "клапан продувки системы EVAP",
    "evap vent valve": "клапан вентиляции системы EVAP",
    "vent valve": "клапан вентиляции системы EVAP",
    "evap pressure": "давление паров топлива в системе EVAP",
    "vapor pressure": "давление паров топлива",
    "tank leak detection": "обнаружение утечки в баке",
    "secondary air": "система подачи вторичного воздуха",
    "air injection": "подача вторичного воздуха",
    "smog pump": "насос подачи вторичного воздуха",
    "pump": "насос",
    "coolant pump": "насос охлаждающей жидкости",
    "radiator fan": "вентилятор радиатора",
    "engine cooling fan": "вентилятор охлаждения двигателя",
    "cooling fan": "вентилятор охлаждения",
    "fan": "вентилятор",
    "thermostat": "термостат",
    "engine temperature": "температура двигателя",
    "idle air control": "управление частотой холостого хода",
    "idle speed control": "управление частотой холостого хода",
    "idle control": "управление холостым ходом",
    "idle speed": "обороты холостого хода",
    "idle": "холостой ход",
    "vehicle speed sensor": "датчик скорости автомобиля",
    "vehicle speed": "скорость автомобиля",
    "wheel speed sensor": "датчик скорости колеса",
    "wheel speed": "скорость колеса",
    "output shaft speed": "скорость выходного вала",
    "input shaft speed": "скорость входного вала",
    "system voltage": "напряжение бортовой сети",
    "battery voltage": "напряжение аккумулятора",
    "battery": "аккумулятор",
    "generator": "генератор",
    "alternator": "генератор",
    "starter": "стартёр",
    "starter relay": "реле стартёра",
    "ignition switch": "выключатель зажигания",
    "immobilizer": "иммобилайзер",
    "control module": "модуль управления",
    "control module memory": "память модуля управления",
    "electronic control module": "электронный модуль управления",
    "engine control module": "ЭБУ двигателя",
    "powertrain control module": "ЭБУ силового агрегата",
    "transmission control module": "ЭБУ трансмиссии",
    "communication bus": "коммуникационная шина",
    "can bus": "шина CAN",
    "can": "шина CAN",
    "transmission": "трансмиссия (АКПП/МКПП)",
    "gear position": "положение передачи",
    "gear ratio": "передаточное отношение",
    "clutch": "сцепление",
    "torque converter clutch": "муфта гидротрансформатора",
    "torque converter": "гидротрансформатор",
    "shift solenoid": "соленоид переключения передач",
    "solenoid": "соленоид",
    "pressure control solenoid": "соленоид управления давлением",
    "linear solenoid": "линейный соленоид",
    "steering angle sensor": "датчик угла поворота руля",
    "steering": "рулевое управление",
    "power steering": "усилитель рулевого управления",
    "brake light switch": "выключатель стоп-сигнала",
    "brake switch": "выключатель тормоза",
    "brake pedal": "педаль тормоза",
    "brake booster": "вакуумный усилитель тормозов",
    "brake pressure": "давление в тормозной системе",
    "brake": "тормозная система",
    "abs": "ABS",
    "airbag": "подушка безопасности",
    "restraint system": "система пассивной безопасности",
    "seat belt": "ремень безопасности",
    "seat": "сиденье",
    "door": "дверь",
    "window": "стеклоподъёмник",
    "light": "фонарь",
    "lamp": "лампа",
    "malfunction indicator lamp": "контрольная лампа неисправностей (MIL)",
    "air conditioning": "кондиционер",
    "climate control": "климат-контроль",
    "refrigerant pressure": "давление хладагента кондиционера",
    "refrigerant": "хладагент кондиционера",
    "cabin heater": "отопитель салона",
    "pcv": "вентиляция картера (PCV)",
    "manifold runner control": "управление заслонками впускного коллектора",
    "intake manifold runner": "заслонки впускного коллектора",
    "intake manifold": "впускной коллектор",
    "intake flap": "впускная заслонка",
    "swirl flap": "вихревые заслонки впускного коллектора",
    "linear oxygen sensor": "линейный (широкополосный) датчик кислорода",
    "pre-catalyst": "датчик до нейтрализатора",
    "post-catalyst": "датчик после нейтрализатора",
    "heated catalyst": "обогреваемый нейтрализатор",
    "emission control system": "система снижения выбросов",
    "emission": "выбросы",
    "ecu": "ЭБУ",
    "serial communication link": "последовательная линия связи",
    "communication link": "линия связи",
    "computer": "ЭБУ",
    "processor": "процессор ЭБУ",
    "memory": "память ЭБУ",
    "rom": "память ROM",
    "ram": "память RAM",
    "keep alive memory": "энергонезависимая память (KAM)",
    "check engine light": "контрольная лампа неисправностей (MIL)",
    "starter/ignition switch": "выключатель стартёра/зажигания",
    "start/stop": "система старт-стоп",
    "hybrid battery": "тяговая батарея гибрида",
    "battery pack": "тяговая батарея",
    "electric drive": "электропривод",
    "electric motor": "электродвигатель",
    "motor": "электродвигатель",
    "charger": "зарядное устройство",
    "charging system": "система зарядки",
    "request signal": "сигнал запроса",
    "relay": "реле",
    "fuse": "предохранитель",
    "switch": "выключатель",
    "sensor": "датчик",
    "actuator": "исполнительный механизм",
    "wiring harness": "жгут проводов",
    "harness": "жгут проводов",
    "connector": "разъём",
    "signal": "сигнал",
    "voltage supply": "напряжение питания",
    "supply voltage": "напряжение питания",
    "power supply": "питание",
    "charge": "заряд",
    "air flow": "расход воздуха",
    "air": "воздух",
    "pedal": "педаль",
    "engine": "двигатель",
}

# Fault phrases: matched from the tail of the title, longest first.
FAULTS = {
    "control circuit": "неисправность цепи управления",
    "control circuit/open": "обрыв в цепи управления",
    "control circuit/low": "низкий уровень сигнала в цепи управления",
    "control circuit/high": "высокий уровень сигнала в цепи управления",
    "control circuit high": "высокий уровень сигнала в цепи управления",
    "control circuit low": "низкий уровень сигнала в цепи управления",
    "control circuit open": "обрыв в цепи управления",
    "control circuit range/performance": "сигнал в цепи управления вне диапазона / неправильная работа",
    "control circuit intermittent": "периодическая неисправность в цепи управления",
    "control circuit malfunction": "неисправность цепи управления",
    "circuit high input": "высокий уровень сигнала в цепи",
    "circuit low input": "низкий уровень сигнала в цепи",
    "circuit range/performance": "сигнал вне диапазона / неправильная работа",
    "circuit performance": "неправильная работа цепи",
    "circuit intermittent": "периодическая неисправность цепи",
    "circuit open": "обрыв цепи",
    "circuit short to battery": "короткое замыкание в цепи на плюс",
    "circuit short to ground": "короткое замыкание в цепи на массу",
    "circuit shorted": "короткое замыкание в цепи",
    "circuit/open": "обрыв цепи",
    "circuit/low": "низкий уровень сигнала в цепи",
    "circuit/high": "высокий уровень сигнала в цепи",
    "circuit/open or short": "обрыв или короткое замыкание в цепи",
    "circuit malfunction": "неисправность цепи",
    "circuit high": "высокий уровень сигнала в цепи",
    "circuit low": "низкий уровень сигнала в цепи",
    "circuit high/open": "обрыв или высокий уровень сигнала в цепи",
    "circuit low/open": "обрыв или низкий уровень сигнала в цепи",
    "circuit": "неисправность цепи",
    "high input": "высокий уровень входного сигнала",
    "low input": "низкий уровень входного сигнала",
    "high voltage": "высокое напряжение",
    "low voltage": "низкое напряжение",
    "voltage high": "высокое напряжение",
    "voltage low": "низкое напряжение",
    "signal high": "высокий уровень сигнала",
    "signal low": "низкий уровень сигнала",
    "signal stuck high": "сигнал зафиксирован на высоком уровне",
    "signal stuck low": "сигнал зафиксирован на низком уровне",
    "signal cross": "перепутанные сигналы",
    "signal outside range": "сигнал вне допустимого диапазона",
    "signal incorrect": "неправильный сигнал",
    "signal invalid": "недействительный сигнал",
    "implausible signal": "неправдоподобный сигнал",
    "no signal": "нет сигнала",
    "no activity detected": "нет активности",
    "no activity": "нет активности",
    "incorrect": "неправильное значение",
    "restricted/blocked": "ограничение/засорение",
    "mechanical": "механическая неисправность",
    "state of charge": "состояние заряда",
    "control circuit performance": "неправильная работа цепи управления",
    "over temperature condition": "перегрев",
    "overspeed condition": "превышение оборотов",
    "overboost condition": "превышение давления наддува",
    "primary circuit": "первичная цепь",
    "secondary circuit": "вторичная цепь",
    "secondary circuit low": "низкий уровень сигнала во вторичной цепи",
    "secondary circuit high": "высокий уровень сигнала во вторичной цепи",
    "secondary circuit intermittent": "периодическая неисправность вторичной цепи",
    "secondary circuit/open": "обрыв вторичной цепи",
    "supply voltage low": "низкое напряжение питания",
    "supply voltage high": "высокое напряжение питания",
    "internal performance": "внутренняя неисправность (производительность)",
    "driver current/temperature too high": "чрезмерный ток/температура выходного каскада",
    "lower than expected": "ниже ожидаемого",
    "higher than expected": "выше ожидаемого",
    "leakage": "утечка",
    "level too low": "уровень слишком низкий",
    "disabled": "отключено",
    "restricted": "ограничение/засорение",
    "short to ground": "короткое замыкание на массу",
    "short to battery": "короткое замыкание на плюс",
    "low": "низкий уровень сигнала",
    "high": "высокий уровень сигнала",
    "(+) open": "обрыв линии (+)",
    "(+) low": "низкий уровень сигнала на линии (+)",
    "(+) high": "высокий уровень сигнала на линии (+)",
    "(-) open": "обрыв линии (-)",
    "(-) low": "низкий уровень сигнала на линии (-)",
    "(-) high": "высокий уровень сигнала на линии (-)",
    "(-) shorted to bus (+)": "короткое замыкание линии (-) на линию (+)",
    "(-) shorted to bus": "короткое замыкание линии (-) на линию (+)",
    "off": "отключена",
    "hydraulic release too long": "слишком долгий гидравлический сброс давления",
    "improper shutdown performance": "неправильное выключение",
    "improper wake-up performance": "неправильное пробуждение",
    "lost communication with": "потеря связи с",
    "active too long": "активна слишком долго",
    "over temperature": "перегрев",
    "phase u current": "ток фазы U",
    "phase v current": "ток фазы V",
    "phase w current": "ток фазы W",
    "phase u current low": "низкий ток фазы U",
    "phase v current low": "низкий ток фазы V",
    "phase w current low": "низкий ток фазы W",
    "phase u current high": "высокий ток фазы U",
    "phase v current high": "высокий ток фазы V",
    "phase w current high": "высокий ток фазы W",
    "hydraulic leakage": "гидравлическая утечка",
    "internal temperature too high": "внутренняя температура слишком высока",
    "no response": "нет ответа",
    "missing response": "отсутствует ответ",
    "slow response": "замедленный отклик",
    "erratic": "нестабильный сигнал",
    "intermittent": "периодическая неисправность",
    "intermittent/erratic": "периодический/нестабильный сигнал",
    "control circuit intermittent/erratic": "периодический/нестабильный сигнал в цепи управления",
    "circuit intermittent/erratic": "периодический/нестабильный сигнал в цепи",
    "too low": "слишком низкое значение",
    "too high": "слишком высокое значение",
    "flow low/performance": "недостаточный поток / неправильная работа",
    "flow low": "недостаточный поток",
    "flow high": "избыточный поток",
    "resistance": "несоответствие сопротивления",
    "resistance low": "низкое сопротивление",
    "resistance high": "высокое сопротивление",
    "overspeed": "превышение оборотов",
    "signals swapped": "перепутаны сигналы",
    "out of range": "вне диапазона",
    "range/performance": "сигнал вне диапазона / неправильная работа",
    "performance or stuck off": "неправильная работа или зафиксирован в выключенном состоянии",
    "performance or stuck on": "неправильная работа или зафиксирован во включённом состоянии",
    "performance/stuck": "неправильная работа или залипание",
    "performance/stuck off": "неправильная работа или зафиксирован в выключенном состоянии",
    "performance/stuck on": "неправильная работа или зафиксирован во включённом состоянии",
    "performance/stuck open": "неправильная работа или зафиксирован в открытом состоянии",
    "timing over-advanced": "фазы газораспределения чрезмерно опережены",
    "timing over-retarded": "фазы газораспределения чрезмерно запаздывают",
    "over-advanced": "чрезмерно опережено",
    "over-retarded": "чрезмерно запаздывает",
    "correlation": "несоответствие взаимного положения сигналов (корреляция)",
    "exceeded learning limit": "превышен предел адаптации",
    "actuator stuck": "актюатор зафиксирован в одном положении",
    "not commanded on": "не включается по команде",
    "not commanded off": "не выключается по команде",
    "commanded on/off": "включение/выключение по команде",
    "relay activated": "сработало реле",
    "relief valve activated": "сработал предохранительный клапан",
    "performance": "неправильная работа (производительность)",
    "rationality": "неправдоподобность (рациональность)",
    "rationality error": "неправдоподобность (рациональность)",
    "stuck open": "зафиксирован в открытом состоянии",
    "stuck closed": "зафиксирован в закрытом состоянии",
    "stuck on": "зафиксирован во включённом состоянии",
    "stuck off": "зафиксирован в выключенном состоянии",
    "stuck": "залипание (зафиксирован в одном положении)",
    "open": "обрыв",
    "shorted": "короткое замыкание",
    "high temperature": "высокая температура",
    "low temperature": "низкая температура",
    "temperature too high": "температура слишком высока",
    "temperature too low": "температура слишком низка",
    "too hot": "перегрев",
    "too cold": "слишком холодно",
    "high pressure": "высокое давление",
    "low pressure": "низкое давление",
    "pressure too high": "давление слишком велико",
    "pressure too low": "давление слишком мало",
    "pressure increase": "рост давления",
    "pressure drop": "падение давления",
    "flow high": "избыточный поток",
    "flow low": "недостаточный поток",
    "insufficient flow": "недостаточный поток",
    "excessive flow": "избыточный поток",
    "incorrect flow": "неправильный поток",
    "flow performance": "неправильный поток",
    "flow": "поток",
    "leak detected": "обнаружена утечка",
    "large leak detected": "обнаружена значительная утечка",
    "small leak detected": "обнаружена небольшая утечка",
    "very small leak": "обнаружена очень небольшая утечка",
    "leak": "утечка",
    "below threshold": "ниже порогового значения",
    "above threshold": "выше порогового значения",
    "below limit": "ниже предела",
    "above limit": "выше предела",
    "efficiency below threshold": "эффективность ниже порога",
    "too slow": "слишком медленно",
    "too fast": "слишком быстро",
    "too lean": "слишком бедная смесь",
    "too rich": "слишком богатая смесь",
    "lean": "бедная смесь",
    "rich": "богатая смесь",
    "correct speed not detected": "ожидаемая скорость не обнаружена",
    "not calibrated": "не откалибровано",
    "not learned": "не адаптировано (не изучено)",
    "not detected": "не обнаружено",
    "not plausible": "неправдоподобно",
    "fault": "неисправность",
    "malfunction": "неисправность",
    "improper correlation": "несоответствие (корреляция)",
    "improper": "неправильное значение",
    "insufficient": "недостаточное значение",
    "actuator stuck open": "исполнительный механизм зафиксирован открытым",
    "current too high": "ток слишком велик",
    "voltage out of range": "напряжение вне диапазона",
    "checksum error": "ошибка контрольной суммы",
    "wrong part installed": "установлена неправильная деталь",
    "invalid data": "недопустимые данные",
    "unexpected data": "неожиданные данные",
    "data problem": "проблема данных",
}

# Qualifiers like "Bank 1", "Sensor 2", "Cylinder 3".
QUAL_RE = re.compile(r"\b(bank|sensor|cylinder|engine|stage|unit|row)\s*([0-9]+)\b", re.I)

# Special-case whole-title patterns (checked against the cleaned body, before
# the generic pipeline). Qualifiers (bank/sensor/...) are appended later.
SPECIALS = [
    (re.compile(r"^misfires?.*cyl(?:inder)?\s*(\d+)$", re.I), "Пропуски зажигания в цилиндре {}"),
    (re.compile(r"^cyl(?:inder)?\s*(\d+)\s+misfire\s+(?:detected|recognized)$", re.I), "Пропуски зажигания в цилиндре {}"),
    (re.compile(r"^random/multiple cylinder misfire detected$", re.I), "Обнаружены случайные/многократные пропуски зажигания"),
    (re.compile(r"^cylinder (\d+) injection timing$", re.I), "Момент впрыска цилиндра {}"),
    (re.compile(r"^cylinder (\d+) glow plug(?: circuit| preheater)?$", re.I), "Свеча накаливания цилиндра {}"),
    (re.compile(r"^cylinder (\d+) glow plug circuit (open|shorted|high|low)$", re.I), "Цепь свечи накаливания цилиндра {} — {}"),
    (re.compile(r"^cylinder (\d+) injector(?: control)? circuit$", re.I), "Цепь управления форсункой цилиндра {}"),
    (re.compile(r"^cylinder (\d+) injector (high|low)$", re.I), "Форсунка цилиндра {} — {} уровень сигнала"),
    (re.compile(r"^cylinder (\d+) (?:fuel )?injection (?:pump|valve) (high|low)$", re.I), "Форсунка цилиндра {} — {} уровень сигнала"),
    (re.compile(r"^(?:system )?too lean$", re.I), "Слишком бедная смесь"),
    (re.compile(r"^(?:system )?too rich$", re.I), "Слишком богатая смесь"),
    (re.compile(r"^(?:catalyst system efficiency|catalyst efficiency) below threshold$", re.I),
     "Эффективность нейтрализатора ниже порога"),
    (re.compile(r"^catalyst temp(?:erature)? below threshold$", re.I), "Температура нейтрализатора ниже порога"),
    (re.compile(r"^(?:diesel )?particulate filter efficiency below threshold$", re.I), "Эффективность сажевого фильтра ниже порога"),
    (re.compile(r"^evap(?:orative)? (?:emission )?(?:control )?system (small|large|very small) leak.*", re.I), "Утечка в системе EVAP ({})"),
    (re.compile(r"^engine oil pressure (?:switch|sensor) (?:circuit )?(high|low)$", re.I), "Датчик давления масла — {} уровень сигнала"),
    (re.compile(r"^system \(?voltage\)? (?:out of range |)?(high|low)$", re.I), "Напряжение бортовой сети — {}"),
    (re.compile(r"^charging system (high|low) voltage$", re.I), "Напряжение системы зарядки — {}"),
    (re.compile(r"^generator/voltage regulator$", re.I), "Генератор/регулятор напряжения"),
    (re.compile(r"^check engine light(?:s)? (?:request|bulb|on) (?:on|requested|circuit).*", re.I), "Цепь контрольной лампы неисправностей (MIL)"),
    (re.compile(r"^serial communication link$", re.I), "Последовательная линия связи (CAN/K-line)"),
    (re.compile(r"^pcm/ecm/tpu (?:communication )?.*(internal).*", re.I), "Внутренняя связь модулей ЭБУ"),
    (re.compile(r"^ecu memory checksum$", re.I), "Ошибка контрольной суммы памяти ЭБУ"),
    (re.compile(r"^internal control module .*checksum.*$", re.I), "Ошибка контрольной суммы памяти ЭБУ"),
    (re.compile(r"^key(?: off)? cycle count.*$", re.I), "Счётчик циклов зажигания"),
    (re.compile(r"^vehicle speed signal$", re.I), "Сигнал скорости автомобиля"),
    (re.compile(r"^battery(?: pack)? current$", re.I), "Ток батареи"),
    (re.compile(r"^battery(?: pack)? voltage$", re.I), "Напряжение батареи"),
    (re.compile(r"^battery(?: pack)? temperature$", re.I), "Температура батареи"),
    (re.compile(r"^hybrid battery.*$", re.I), "Тяговая батарея гибрида"),
    (re.compile(r"^requested torque.*$", re.I), "Запрашиваемый крутящий момент"),
    (re.compile(r"^(?:[a-f] )?camshaft position[ -]+timing over-advanced or system performance$", re.I),
     "Фазы газораспределения чрезмерно опережены / неправильная работа системы"),
    (re.compile(r"^(?:[a-f] )?camshaft position[ -]+timing over-retarded$", re.I),
     "Фазы газораспределения чрезмерно запаздывают"),
    (re.compile(r"^o2 sensor signals swapped.*$", re.I),
     "Перепутаны сигналы датчиков кислорода"),
    (re.compile(r"^crankshaft position[ -]+camshaft position correlation$", re.I),
     "Несоответствие взаимного положения коленчатого и распределительного валов (ДПКВ/ДПРВ)"),
    (re.compile(r"^map/maf[ -]+throttle position correlation$", re.I),
     "Несоответствие сигналов MAP/MAF и положения дроссельной заслонки"),
    (re.compile(r"^map[ -]+mass or volume air flow correlation$", re.I),
     "Несоответствие сигналов датчика MAP и расходомера (MAF)"),
    (re.compile(r"^map[ -]+exhaust pressure correlation$", re.I),
     "Несоответствие сигналов MAP и давления выхлопных газов"),
    (re.compile(r"^map[ -]+turbocharger/supercharger inlet pressure correlation$", re.I),
     "Несоответствие сигналов MAP и давления на входе турбонагнетателя"),
    (re.compile(r"^barometric pressure[ -]+turbocharger/supercharger inlet pressure correlation$", re.I),
     "Несоответствие барометрического давления и давления на входе турбонагнетателя"),
    (re.compile(r"^fuel system leak detected - (large|small|very small) leak$", re.I),
     "Обнаружена {} утечка топлива"),
    (re.compile(r"^fuel system over pressure relief valve activated$", re.I),
     "Сработал предохранительный клапан избыточного давления топлива"),
    (re.compile(r"^fuel system over pressure$", re.I), "Избыточное давление топлива"),
    (re.compile(r"^fuel system (?:over|too) rich$", re.I), "Слишком богатая смесь"),
    (re.compile(r"^fuel system (?:over|too) lean$", re.I), "Слишком бедная смесь"),
    (re.compile(r"^fuel system (?:lean|rich) at (?:idle|cruise|high load).*$", re.I), None),
    (re.compile(r"^engine(?: crankshaft)? (?:start|cranking) time.*$", re.I), "Время прокрутки стартёра"),
    (re.compile(r"^camshaft position actuator", re.I), None),  # handled by generic pipeline
    (re.compile(r"^coolant thermostat \(coolant temperature below thermostat regulating temperature\)$", re.I),
     "Термостат (температура ОЖ ниже регулируемой температуры термостата)"),
    (re.compile(r"^insufficient coolant temperature for closed loop fuel control$", re.I),
     "Недостаточная температура ОЖ для замкнутого контура управления топливом"),
    (re.compile(r"^insufficient coolant temperature for stable operation$", re.I),
     "Недостаточная температура ОЖ для стабильной работы"),
    (re.compile(r"^intake air temperature too (high|low)$", re.I), None),
    (re.compile(r"^barometric pressure too (low|high)$", re.I), "Барометрическое давление — {}"),
    (re.compile(r"^fuel rail/system pressure too (low|high)$", re.I), "Давление топлива в рампе/системе — {}"),
    (re.compile(r"^low pressure fuel system pressure too (low|high)$", re.I), "Давление в контуре низкого давления топлива — {}"),
    (re.compile(r"^low fuel pressure[ -]+forced limited power$", re.I),
     "Низкое давление топлива — принудительное ограничение мощности"),
    (re.compile(r"^fuel injector insufficient flow[ -]+forced limited power$", re.I),
     "Недостаточная подача через форсунку — принудительное ограничение мощности"),
    (re.compile(r"^mass or volume air flow (?:a|b) circuit range/performance[ -]+air flow too low$", re.I),
     "Цепь расходомера (MAF) — вне диапазона; расход воздуха слишком низкий"),
    (re.compile(r"^mass or volume air flow (?:a|b) circuit range/performance[ -]+air flow too high$", re.I),
     "Цепь расходомера (MAF) — вне диапазона; расход воздуха слишком высокий"),
    (re.compile(r"^fuel rail pressure too low[ -]+engine cranking$", re.I),
     "Давление топлива в рампе слишком низкое при прокрутке стартёром"),
    (re.compile(r"^intake air pressure measurement system[ -]+multiple sensor correlation$", re.I),
     "Несоответствие показаний датчиков давления впускного воздуха"),
    (re.compile(r"^intake air temperature measurement system[ -]+multiple sensor correlation$", re.I),
     "Несоответствие показаний датчиков температуры впускного воздуха"),
    (re.compile(r"^barometric pressure[ -]+turbocharger/supercharger boost sensor correlation$", re.I),
     "Несоответствие барометрического давления и датчика наддува"),
    (re.compile(r"^charge air cooler temperature[/ -]+intake air temperature correlation$", re.I),
     "Несоответствие температур интеркулера и впускного воздуха"),
    (re.compile(r"^radiator coolant temperature[/ -]+engine coolant temperature correlation$", re.I),
     "Несоответствие показаний температуры ОЖ радиатора и двигателя"),
    (re.compile(r"^cylinder (\d+) injection timing performance[/ -]+over (retarded|advanced)$", re.I),
     "Момент впрыска цилиндра {} — чрезмерно {}"),
    (re.compile(r"^engine coolant flow low/performance$", re.I),
     "Недостаточный поток охлаждающей жидкости"),
    (re.compile(r"^turbocharger/supercharger bypass valve[ -]+mechanical$", re.I),
     "Клапан перепуска турбонагнетателя — механическая неисправность"),
    (re.compile(r"^o2 sensor slow response[ -]+rich to lean$", re.I),
     "Датчик кислорода — замедленный отклик (из богатой смеси в бедную)"),
    (re.compile(r"^o2 sensor slow response[ -]+lean to rich$", re.I),
     "Датчик кислорода — замедленный отклик (из бедной смеси в богатую)"),
    (re.compile(r"^o2 sensor delayed response[ -]+rich to lean$", re.I),
     "Датчик кислорода — задержанный отклик (из богатой смеси в бедную)"),
    (re.compile(r"^o2 sensor delayed response[ -]+lean to rich$", re.I),
     "Датчик кислорода — задержанный отклик (из бедной смеси в богатую)"),
    (re.compile(r"^o2 sensor (?:pump )?current.*$", re.I), "Ток датчика кислорода"),
    (re.compile(r"^o2 sensor heater resistance$", re.I), "Сопротивление нагревателя датчика кислорода"),
    (re.compile(r"^battery (?:b )?state of charge performance$", re.I),
     "Неправильная работа оценки состояния заряда батареи"),
    (re.compile(r"^evap system tank vapor line restricted/blocked$", re.I),
     "Ограничение/засорение паровой линии бака системы EVAP"),
    (re.compile(r"^body control module requested mil illumination$", re.I),
     "Запрос включения MIL от блока управления кузовом (BCM)"),
    (re.compile(r"^intake air (?:temperature )?(?:mass )?air flow.*$", re.I), None),
    (re.compile(r"^cylinder (\d+) (?:engine )?oil (?:temp|temperature).*", re.I), None),
    (re.compile(r"^camshaft position - camshaft profile correlation$", re.I), None),
    (re.compile(r"^excessive time to enter closed loop fuel timing control$", re.I),
     "Чрезмерное время входа в замкнутый контур управления фазами подачи топлива"),
    (re.compile(r"^excessive time to enter closed loop cylinder balance control$", re.I),
     "Чрезмерное время входа в замкнутый контур управления балансом цилиндров"),
    (re.compile(r"^excessive time to enter closed loop air/fuel ratio control$", re.I),
     "Чрезмерное время входа в замкнутый контур управления соотношением воздух/топливо"),
    (re.compile(r"^excessive time to enter closed loop fuel pressure control$", re.I),
     "Чрезмерное время входа в замкнутый контур управления давлением топлива"),
    (re.compile(r"^closed loop air/fuel ratio control at limit[ -]+system too rich$", re.I),
     "Замкнутый контур управления соотношением воздух/топливо на пределе — смесь слишком богатая"),
    (re.compile(r"^closed loop air/fuel ratio control at limit[ -]+system too lean$", re.I),
     "Замкнутый контур управления соотношением воздух/топливо на пределе — смесь слишком бедная"),
    (re.compile(r"^closed loop fuel pressure control at limit[ -]+pressure too high$", re.I),
     "Замкнутый контур управления давлением топлива на пределе — давление слишком высокое"),
    (re.compile(r"^closed loop fuel pressure control at limit[ -]+pressure too low$", re.I),
     "Замкнутый контур управления давлением топлива на пределе — давление слишком низкое"),
    (re.compile(r"^fuel delivery error$", re.I), "Ошибка подачи топлива"),
    (re.compile(r"^fuel timing error$", re.I), "Ошибка фазирования подачи топлива"),
    (re.compile(r"^incorrect fuel composition$", re.I), "Неправильный состав топлива"),
    (re.compile(r"^fuel system over pressure relief valve frequent activation$", re.I),
     "Частое срабатывание предохранительного клапана избыточного давления топлива"),
    (re.compile(r"^o2 sensor signal biased/stuck (lean|rich)$", re.I),
     "Сигнал датчика кислорода смещён/зафиксирован ({})"),
    (re.compile(r"^coolant temperature relapsed below diagnostic monitoring temperature$", re.I),
     "Температура ОЖ вновь опустилась ниже температуры диагностики"),
    (re.compile(r"^engine coolant over temperature condition$", re.I), "Перегрев охлаждающей жидкости"),
    (re.compile(r"^injection timing performance$", re.I), "Неправильная работа момента впрыска"),
    (re.compile(r"^cylinder (\d+) contribution/balance$", re.I), "Вклад/баланс цилиндра {}"),
    (re.compile(r"^cold start injector (\d+)$", re.I), "Форсунка холодного пуска {}"),
    (re.compile(r"^injector/injection timing control circuit$", re.I),
     "Цепь управления форсунками/моментом впрыска"),
    (re.compile(r"^manifold absolute pressure[ -]+turbocharger/supercharger boost sensor correlation$", re.I),
     "Несоответствие сигналов MAP и датчика наддува"),
    (re.compile(r"^control module improper (shutdown|wake-up) performance$", re.I),
     "Модуль управления — {}"),
    (re.compile(r"^reductant leakage$", re.I), "Утечка восстановителя (AdBlue)"),
    (re.compile(r"^cylinder (\d+)[ -]+fuel trim at (max|min) limit$", re.I),
     "Коррекция топлива цилиндра {} на {} пределе"),
    (re.compile(r"^cylinder (\d+)[ -]+injector leaking$", re.I),
     "Форсунка цилиндра {} негерметична (подтекает)"),
    (re.compile(r"^cold start fuel injection quantity (?:[a-f] )?(lower|higher) than expected$", re.I),
     "Цикловая подача топлива при холодном пуске — {} ожидаемой"),
    (re.compile(r"^vehicle overspeed condition$", re.I), "Превышение скорости автомобиля"),
    (re.compile(r"^engine oil over temperature$", re.I), "Перегрев моторного масла"),
    (re.compile(r"^turbocharger/supercharger (?:[a-f] )?underboost condition$", re.I),
     "Недостаточное давление наддува"),
    (re.compile(r"^charge air cooler cooling fan control circuit driver performance$", re.I),
     "Неправильная работа выходного каскада цепи управления вентилятором интеркулера"),
    (re.compile(
        r"^injection pump fuel metering control(?: (range/performance|low|high|intermittent))?$", re.I),
     lambda m: {
         None: "Управление дозированием топлива ТНВД — неисправность",
         "range/performance": "Управление дозированием топлива ТНВД — вне диапазона / неправильная работа",
         "low": "Управление дозированием топлива ТНВД — низкий уровень сигнала",
         "high": "Управление дозированием топлива ТНВД — высокий уровень сигнала",
         "intermittent": "Управление дозированием топлива ТНВД — периодическая неисправность",
     }[m.group(1).lower() if m.group(1) else None]),
    (re.compile(r"^particulate filter over temperature$", re.I), "Перегрев сажевого фильтра"),
    (re.compile(r"^catalyst system over temperature$", re.I), "Перегрев нейтрализатора"),
    (re.compile(r"^throttle actuator control system[ -]+ice blockage$", re.I),
     "Ледяная пробка в приводе дроссельной заслонки"),
    (re.compile(r"^manifold absolute pressure/mass air flow[ -]+throttle position correlation at idle$", re.I),
     "Несоответствие сигналов MAP/MAF и положения дроссельной заслонки на холостом ходу"),
    (re.compile(r"^manifold absolute pressure/mass air flow[ -]+throttle position correlation at higher load$", re.I),
     "Несоответствие сигналов MAP/MAF и положения дроссельной заслонки при высокой нагрузке"),
    (re.compile(r"^cylinder (\d+) fuel injector (?:[a-f] )?offset learning at (min|max) limit$", re.I),
     "Адаптация смещения форсунки цилиндра {} на {} пределе"),
    (re.compile(r"^diesel intake air flow (?:[a-f] )?control system[ -]+(high|low) air flow detected$", re.I),
     "Обнаружен {} расход воздуха (управление потоком впускного воздуха дизеля)"),
    (re.compile(r"^diesel intake air flow position sensor minimum/maximum stop performance$", re.I),
     "Неправильная работа конечных упоров датчика потока впускного воздуха дизеля"),
    (re.compile(r"^high voltage system interlock circuit$", re.I),
     "Контур блокировки системы высокого напряжения (HVIL) — неисправность цепи"),
    (re.compile(r"^high voltage system interlock$", re.I),
     "Контур блокировки системы высокого напряжения (HVIL)"),
    (re.compile(r"^invalid data received from (.+)$", re.I),
     lambda m: _received_from(m.group(1), "Недопустимые данные от: ")),
    (re.compile(r"^software incompatibility with (.+)$", re.I),
     lambda m: _received_from(m.group(1), "Несовместимость ПО: ")),
    (re.compile(r"^lost communication with (.+)$", re.I),
     lambda m: _lost_communication(m.group(1))),
]

FAULT_WORD_HIGHLOW = {"high": "высокий", "low": "низкий", "open": "обрыв", "shorted": "короткое замыкание"}
# word forms used by voltage-related templates
FAULT_WORD_VOLTAGE = {"high": "высокое", "low": "низкое"}


def _try_specials(title):
    """Return a Russian string, '' (a None-template matched: skip specials and
    fall through to the generic pipeline) or None (nothing matched)."""
    skip = False
    for rx, tmpl in SPECIALS:
        m = rx.search(title)
        if not m:
            continue
        if tmpl is None:
            skip = True
            continue
        if callable(tmpl):
            try:
                return tmpl(m)
            except Exception:
                return ""
        if tmpl in {
            "Напряжение бортовой сети — {}",
            "Напряжение системы зарядки — {}",
        }:
            wordmap = FAULT_WORD_VOLTAGE
        elif tmpl in {
            "Барометрическое давление — {}",
            "Давление топлива в рампе/системе — {}",
            "Давление в контуре низкого давления топлива — {}",
        }:
            wordmap = {"low": "слишком низкое", "high": "слишком высокое"}
        elif tmpl == "Момент впрыска цилиндра {} — чрезмерно {}":
            wordmap = {"retarded": "запаздывает", "advanced": "опережено"}
        elif tmpl == "Коррекция топлива цилиндра {} на {} пределе":
            wordmap = {"max": "максимальном", "min": "минимальном"}
        elif tmpl == "Цикловая подача топлива при холодном пуске — {} ожидаемой":
            wordmap = {"lower": "ниже", "higher": "выше"}
        elif tmpl == "Сигнал датчика кислорода смещён/зафиксирован ({})":
            wordmap = {"lean": "бедная смесь", "rich": "богатая смесь"}
        elif tmpl == "Адаптация смещения форсунки цилиндра {} на {} пределе":
            wordmap = {"min": "минимальном", "max": "максимальном"}
        elif tmpl == "Обнаружен {} расход воздуха (управление потоком впускного воздуха дизеля)":
            wordmap = {"high": "высокий", "low": "низкий"}
        elif tmpl == "Модуль управления — {}":
            wordmap = {"shutdown": "неправильное выключение", "wake-up": "неправильное пробуждение"}
        elif tmpl in {
            "Утечка в системе EVAP ({})",
            "Обнаружена {} утечка топлива",
        }:
            wordmap = {"large": "большая", "small": "небольшая", "very small": "очень небольшая"}
        else:
            wordmap = FAULT_WORD_HIGHLOW
        parts = []
        for g in m.groups():
            if g is None:
                continue
            if g.lower() in wordmap:
                g = wordmap[g.lower()]
            parts.append(g)
        try:
            return tmpl.format(*parts)
        except (IndexError, KeyError):
            return tmpl
    return "" if skip else None


_LEXICON_RX_CACHE = {}


def _lost_communication(target):
    """'Lost Communication With <module>' -> 'Потеря связи: <module RU>'."""
    ru = translate_title(target)
    if not ru:
        return ""
    return "Потеря связи: " + ru[0].lower() + ru[1:]


def _received_from(target, prefix):
    """'Invalid Data Received From <module>' -> '<prefix> <module RU>'."""
    ru = translate_title(target)
    if not ru:
        return ""
    return prefix + ru[0].lower() + ru[1:]


def _lexicon_regex(lexicon):
    rx = _LEXICON_RX_CACHE.get(id(lexicon))
    if rx is None:
        alts = sorted(lexicon.keys(), key=len, reverse=True)
        rx = re.compile(
            r"(?<![a-z0-9])(?:" + "|".join(re.escape(p) for p in alts) + r")(?![a-z0-9])"
        )
        _LEXICON_RX_CACHE[id(lexicon)] = rx
    return rx


def _match_longest(text, lexicon):
    """Return (ru, rest): the longest lexicon phrase matching a contiguous
    part of text (case-insensitive, whole-word) and the text without it."""
    if not text:
        return None, text
    lower = text.lower()
    best = None
    for m in _lexicon_regex(lexicon).finditer(lower):
        if best is None or len(m.group()) > len(best[0]):
            best = (m.group(), m.start())
    if best is None:
        return None, text
    phrase, idx = best
    ru = lexicon[phrase]
    rest = text[:idx] + text[idx + len(phrase):]
    return ru, rest


def _clean_leftover(rest):
    rest = rest.replace("-", " ").replace("/", " ").replace("&", " ")
    # Generic connective/generic words that carry no technical meaning once the
    # component and the fault have been matched.
    rest = re.sub(
        r"\b(control|system|valve|sensor|switch|circuit|signal|voltage|position|module|temperature|pressure|level|heater|with|stage|loop|channel|driver|unit|row|data|center)\b",
        " ",
        rest,
        flags=re.I,
    )
    return re.sub(r"\s+", " ", rest).strip()


def _qualify_paren(paren):
    """Translate '(Bank 1, Sensor 1)' style parenthesis content; None if unknown."""
    p = paren.strip()
    ru_words = []
    for qm in QUAL_RE.finditer(p):
        what, num = qm.group(1).lower(), qm.group(2)
        ru = {
            "bank": "банк", "sensor": "датчик", "cylinder": "цилиндр",
            "engine": "двигатель", "stage": "ступень", "unit": "блок", "row": "ряд",
        }[what]
        ru_words.append(f"{ru} {num}")
    rest = QUAL_RE.sub(" ", p)
    rest = re.sub(r"\b[A-Fa-f]\b", " ", rest)
    rest = re.sub(r"[,\s]+", " ", rest).strip()
    if rest:
        rest_l = rest.lower()
        if rest_l in ("pre-catalyst", "pre cat", "upstream"):
            ru_words.append("до нейтрализатора")
        elif rest_l in ("post-catalyst", "post cat", "downstream"):
            ru_words.append("после нейтрализатора")
        elif all(w in ("sensor", "sensors", "bank", "cylinder", "engine", "switch") for w in rest_l.split()):
            pass  # bare structure words without numbers
        elif rest_l in ("cam/rotor/injector", "cam rotor injector"):
            pass  # descriptive paren from injection pump titles
        elif re.fullmatch(r"[a-z]{1,6}", rest_l):
            pass  # short acronym like (IMT), (IPC), (TCS)
        else:
            return None
    return ru_words


def translate_title(title):
    """Return Russian title or None. Deterministic; refuses partial matches."""
    if not title:
        return None
    t = re.sub(r"\s+", " ", title.strip())
    t = t.replace('"', "").replace("’", "'")

    # Parentheses that are descriptive rather than qualifiers (e.g. P0128)
    # block qualifier parsing below; try full-title specials first.
    special_full = _try_specials(t)
    if special_full:
        return special_full

    # Split trailing qualifiers in parentheses, e.g. "... (Bank 1, Sensor 1)"
    paren_ru = None
    m = re.search(r"\s*\(([^)]*)\)$", t)
    if m:
        paren_ru = _qualify_paren(m.group(1))
        if paren_ru is None:
            return None
        t = t[: m.start()].strip()

    # Extract numeric qualifiers from the main body.
    quals = []
    for qm in QUAL_RE.finditer(t):
        what, num = qm.group(1).lower(), qm.group(2)
        ru_what = {
            "bank": "банк", "sensor": "датчик", "cylinder": "цилиндр",
            "engine": "двигатель", "stage": "ступень", "unit": "блок", "row": "ряд",
        }[what]
        quals.append(f"{ru_what} {num}")
    t_body = QUAL_RE.sub(" ", t)
    # Strip standalone channel letters A-F ("Regulator A Control", "Sensor B").
    t_body = re.sub(r"\b[A-Fa-f]\b(?=\s|$)", " ", t_body)
    # Strip short parenthesised acronyms inside the body: "(IMT)", "(IPC)".
    # (Letter-first so the bus polarity markers "(+)"/"(-)" survive.)
    t_body = re.sub(r"\s*\([A-Za-z][A-Za-z0-9/ -]{0,5}\)", " ", t_body)
    t_body = re.sub(r"[-–]\s*$", " ", t_body)
    # Normalise free-standing dashes: "Pressure - Too Low" -> "Pressure Too Low".
    t_body = re.sub(r"\s+[-–]\s+", " ", t_body)
    t_body = re.sub(r"\s+", " ", t_body).strip(" ,;-/")

    special = _try_specials(t_body)
    if special:
        all_quals = quals + (paren_ru or [])
        if all_quals:
            special += " (" + ", ".join(all_quals) + ")"
        return special

    fault_ru, rest1 = _match_longest(t_body, FAULTS)
    if fault_ru is None:
        fault_ru = ""
    # Match up to two component phrases ("Crankshaft Position - Camshaft
    # Position Correlation" style titles have two).
    comp_rus = []
    rest2 = rest1
    for _ in range(3):
        ru, rest2 = _match_longest(rest2, COMPONENTS)
        if ru is None:
            break
        comp_rus.append(ru)
    if comp_rus:
        comp_ru = comp_rus[0]
    else:
        comp_ru = None
    leftover = _clean_leftover(rest2)
    if leftover and re.search(r"[a-z]{3,}", leftover, re.I):
        # Unrecognised words remain — refuse to translate.
        return None
    if not comp_ru and not fault_ru:
        return None

    head = None
    if comp_rus:
        head = " / ".join(c[0].upper() + c[1:] for c in comp_rus)
        if fault_ru:
            head += " — " + fault_ru
    elif fault_ru:
        head = fault_ru[0].upper() + fault_ru[1:]
    all_quals = quals + (paren_ru or [])
    if all_quals:
        head += " (" + ", ".join(all_quals) + ")"
    return head


# ---------------------------------------------------------------------------
# Short sentence/phrase translations for symptoms and simple causes.
# ---------------------------------------------------------------------------
SENTENCES = {
    "check engine light on": "горит контрольная лампа неисправностей (Check Engine)",
    "check engine light": "контрольная лампа неисправностей (Check Engine)",
    "mil on": "горит контрольная лампа неисправностей (MIL)",
    "mil illuminated": "горит контрольная лампа неисправностей (MIL)",
    "engine light on": "горит лампа Check Engine",
    "check engine lamp lit": "горит лампа Check Engine",
    "check engine lamp on": "горит лампа Check Engine",
    "malfunction indicator lamp on": "горит контрольная лампа неисправностей (MIL)",
    "loss of power": "потеря мощности",
    "loss of engine power": "потеря мощности двигателя",
    "reduced power": "снижение мощности",
    "reduced engine power": "снижение мощности двигателя",
    "limp mode": "аварийный режим (limp mode)",
    "limp home mode": "аварийный режим (limp mode)",
    "reduced power or limp mode": "снижение мощности или аварийный режим",
    "loss of power or limp mode": "потеря мощности или аварийный режим",
    "engine stalling": "глохнет двигатель",
    "engine stalls": "двигатель глохнет",
    "stalling": "глохнет двигатель",
    "rough idle": "нестабильный холостой ход",
    "rough running": "неровная работа двигателя",
    "rough running or misfire": "неровная работа или пропуски зажигания",
    "engine misfire": "пропуски зажигания",
    "misfiring": "пропуски зажигания",
    "misfires": "пропуски зажигания",
    "hesitation": "подёргивание при разгоне",
    "hesitation on acceleration": "подёргивание при разгоне",
    "hard start": "трудный запуск",
    "hard starting": "трудный запуск",
    "difficulty starting": "трудный запуск",
    "starting problems": "проблемы с запуском",
    "no start": "двигатель не запускается",
    "won't start": "двигатель не запускается",
    "increased fuel consumption": "повышенный расход топлива",
    "poor fuel economy": "повышенный расход топлива",
    "higher fuel consumption": "повышенный расход топлива",
    "increased emissions": "повышенные выбросы",
    "smoke from exhaust": "дым из выхлопной трубы",
    "excessive smoke": "излишнее дымление",
    "strong fuel smell": "сильный запах топлива",
    "fuel smell": "запах топлива",
    "failed emissions test": "не пройден тест на выбросы",
    "unstable idle": "нестабильный холостой ход",
    "unstable engine idle": "нестабильный холостой ход",
    "idle fluctuates": "плавающие обороты холостого хода",
    "poor engine performance": "ухудшение работы двигателя",
    "poor acceleration": "плохой разгон",
    "slow acceleration": "вялый разгон",
    "engine overheating": "перегрев двигателя",
    "overheating": "перегрев",
    "noise from engine": "посторонний шум из двигателя",
    "abnormal noise": "посторонний шум",
    "vibration": "вибрация",
    "engine vibration": "вибрация двигателя",
    "brake performance reduced": "снижение эффективности тормозов",
    "abs warning light": "лампа ABS на приборной панели",
    "abs warning lamp on": "горит лампа ABS",
    "traction control light on": "горит лампа системы курсовой устойчивости",
    "steering effort increased": "руль стал тугим",
    "gauge readings incorrect": "неверные показания приборов",
    "gauges inoperative": "приборы не работают",
    "transmission shifting problems": "проблемы переключения передач",
    "harsh shifting": "жёсткое переключение передач",
    "gear changes harsh": "жёсткое переключение передач",
    "transmission in limp mode": "АКПП в аварийном режиме",
    "gear slippage": "пробуксовка передач",
    "no gear engagement": "передачи не включаются",
    "battery drain": "разряд аккумулятора",
    "battery not charging": "аккумулятор не заряжается",
    "lights dim": "тусклый свет фар",
    "air conditioning not working": "кондиционер не работает",
    "no air conditioning": "кондиционер не работает",
    "heating not working": "отопление не работает",
    "fan not working": "вентилятор не работает",
    "odometer incorrect": "неверный одометр",
    "speedometer incorrect": "неверный спидометр",
    "fuel gauge inaccurate": "неточный указатель уровня топлива",
    "none observed": "симптомов может не быть",
    "no symptoms observed": "симптомов может не быть",
    "usually no symptoms": "симптомов обычно нет",
    "possible drivability issues": "возможны проблемы в работе двигателя",
    "drivability problems": "проблемы в работе двигателя",
    "startability issues": "проблемы с запуском",
    "unusual odor": "непривычный запах",
    "fuel odor": "запах топлива",
    "exhaust smell": "запах выхлопа",
    "sulfur smell": "запах серы (тухлых яиц)",
    # extended symptom phrases
    "engine dies": "двигатель глохнет",
    "engine shuts off": "двигатель глохнет",
    "engine cranks but does not start": "стартёр крутит, но двигатель не заводится",
    "extended cranking": "длительный запуск",
    "long crank time": "длительный запуск",
    "engine runs rough": "неровная работа двигателя",
    "engine surging": "плавающие обороты двигателя",
    "surging at idle": "плавающие обороты холостого хода",
    "engine hesitation": "подёргивания двигателя",
    "hesitation during acceleration": "подёргивания при разгоне",
    "stumble on acceleration": "провалы при разгоне",
    "power loss under load": "потеря мощности под нагрузкой",
    "lack of power": "недостаток мощности",
    "reduced fuel economy": "повышенный расход топлива",
    "excessive fuel consumption": "повышенный расход топлива",
    "noisy engine": "шумная работа двигателя",
    "knocking noise": "стук/детонация",
    "whining noise from engine": "вой/свист из двигателя",
    "hissing noise": "шипение (подсос воздуха)",
    "smell of fuel": "запах топлива",
    "smell of rotten eggs": "запах серы (тухлых яиц)",
    "black smoke from exhaust": "чёрный дым из выхлопной трубы",
    "white smoke from exhaust": "белый дым из выхлопной трубы",
    "blue smoke from exhaust": "синий дым из выхлопной трубы",
    "smoke on start-up": "дым при запуске",
    "oil leak": "утечка масла",
    "coolant leak": "утечка охлаждающей жидкости",
    "coolant loss": "потеря охлаждающей жидкости",
    "high coolant temperature": "высокая температура охлаждающей жидкости",
    "temperature gauge high": "стрелка температуры на максимуме",
    "heater not working": "печка (отопитель) не работает",
    "air conditioning performance reduced": "снижение производительности кондиционера",
    "battery warning light": "лампа разряда аккумулятора",
    "charging warning light on": "горит лампа зарядки",
    "dim lights": "тусклый свет",
    "flickering lights": "мигающий свет",
    "electrical issues": "проблемы с электрикой",
    "multiple electrical faults": "множественные ошибки электрики",
    "warning lights on": "горят предупреждающие лампы",
    "multiple warning lights": "множественные предупреждающие лампы",
    "warning lamp illuminated": "горит предупреждающая лампа",
    "abs light on": "горит лампа ABS",
    "esp light on": "горит лампа ESP",
    "airbag light on": "горит лампа подушек безопасности",
    "airbag warning light on": "горит лампа подушек безопасности",
    "transmission warning light": "лампа АКПП",
    "gear shifts delayed": "задержки переключения передач",
    "delayed shifts": "задержки переключения передач",
    "shift flares": "пробуксовка при переключении",
    "transmission slipping": "пробуксовка АКПП",
    "transmission shudder": "дрожь АКПП",
    "judder when engaging": "дрожь при включении передачи",
    "neutral droning": "гул на нейтрали",
    "no movement": "машина не едет",
    "vehicle will not move": "машина не едет",
    "limp home": "аварийный режим",
    "failsafe mode": "аварийный режим",
    "reduced engine power message": "сообщение «снижение мощности двигателя»",
    "engine derate": "принудительное снижение мощности",
    "forced engine shutdown": "принудительная остановка двигателя",
    "excessive exhaust smoke": "излишний дым из выхлопа",
    "dpf warning light": "лампа сажевого фильтра",
    "regeneration frequent": "слишком частые регенерации",
    "poor idle quality": "неровный холостой ход",
    "idle surging": "плавающие обороты холостого хода",
    "high idle": "повышенные обороты холостого хода",
    "low idle": "пониженные обороты холостого хода",
    "stalls at idle": "глохнет на холостом ходу",
    "stalls when stopping": "глохнет при остановке",
    "dies when warm": "глохнет на прогретом двигателе",
    "runs cold": "двигатель не прогревается до рабочей температуры",
    "poor heater output": "слабый обогрев салона",
    "fast battery drain": "быстрый разряд аккумулятора",
    "starting problems when cold": "проблемы запуска на холодную",
    "hard start when hot": "трудный запуск на горячую",
    "no crank": "стартёр не крутит",
    "starter clicks": "стартёр щёлкает",
    "speedometer not working": "спидометр не работает",
    "odometer not working": "одометр не работает",
    "cruise control inoperative": "круиз-контроль не работает",
    "power windows inoperative": "стеклоподъёмники не работают",
    "doors not locking": "двери не запираются",
    "central locking issues": "проблемы центрального замка",
    "horn inoperative": "сигнал не работает",
    "wipers inoperative": "дворники не работают",
    "lights inoperative": "фонари не работают",
    "climate control inoperative": "климат-контроль не работает",
    "fans running constantly": "вентиляторы работают постоянно",
    "fan runs constantly": "вентилятор работает постоянно",
    "no fan operation": "вентилятор не включается",
    "dtcs stored in other modules": "ошибки в других блоках",
    "no notable symptoms": "симптомов может не быть",
    "there may be no noticeable symptoms": "заметных симптомов может не быть",
    "often no noticeable symptoms": "заметных симптомов часто нет",
}

SYMPTOM_PREFIX = [
    ("possible", "возможны: "),
    ("sometimes", "иногда: "),
    ("usually", "обычно: "),
    ("may cause", "может вызывать: "),
    ("may result in", "может приводить к: "),
]


def translate_symptom(text):
    t = re.sub(r"\s+", " ", (text or "").strip()).lower().rstrip(".")
    prefix_ru = ""
    for en, ru in SYMPTOM_PREFIX:
        if t.startswith(en + " ") or t == en:
            prefix_ru = ru
            t = t[len(en):].strip(" :")
            break
    parts = [p.strip() for p in t.split(";")]
    out = []
    for p in parts:
        if not p:
            continue
        ru = SENTENCES.get(p)
        if ru is None:
            # try "x or y" composition
            if " or " in p:
                subs = [SENTENCES.get(s.strip()) for s in p.split(" or ")]
                if subs and all(subs):
                    ru = " или ".join(subs)
        if ru is None:
            return None
        out.append(ru)
    if not out:
        return None
    return prefix_ru + "; ".join(out)


# Simple cause noun-phrase rules.
CAUSE_PATTERNS = [
    (re.compile(r"^wiring(?: harness)?(?: damage| damaged| worn| problem| issues?)$", re.I), "повреждение проводки/жгута"),
    (re.compile(r"^damaged wiring(?: harness)?$", re.I), "повреждённая проводка"),
    (re.compile(r"^open(?: circuit)? (?:in|in the) (?:wiring|harness|circuit)$", re.I), "обрыв в проводке/цепи"),
    (re.compile(r"^short(?: circuit)? (?:to|against) (?:ground|earth|mass)$", re.I), "короткое замыкание на массу"),
    (re.compile(r"^short(?: circuit)? (?:to|against) (?:battery|plus|voltage|b\+)$", re.I), "короткое замыкание на плюс"),
    (re.compile(r"^open or short(?:ed)? (?:in|in the|circuit|wiring)$", re.I), "обрыв или короткое замыкание в цепи"),
    (re.compile(r"^open (?:or short|and short) (?:circuit )?in (?:the )?wiring(?: harness)?$", re.I), "обрыв или короткое замыкание в проводке"),
    (re.compile(r"^corroded connector(?:s)?$", re.I), "окисленные разъёмы"),
    (re.compile(r"^corroded or loose (?:connector|connection)s?$", re.I), "окисленный или ослабленный разъём"),
    (re.compile(r"^loose (?:connector|connection)s?$", re.I), "ослабленный разъём"),
    (re.compile(r"^bad (?:connector|connection)$", re.I), "плохой контакт в разъёме"),
    (re.compile(r"^failed (?:sensor|component|module|actuator)$", re.I), "неисправный датчик/компонент"),
    (re.compile(r"^failed or failing sensor$", re.I), "неисправный или деградирующий датчик"),
    (re.compile(r"^faulty sensor$", re.I), "неисправный датчик"),
    (re.compile(r"^faulty (?:actuator|valve|solenoid)$", re.I), "неисправный исполнительный механизм/клапан"),
    (re.compile(r"^failed (?:actuator|valve|solenoid|pump|motor)$", re.I), "неисправный исполнительный механизм"),
    (re.compile(r"^faulty (?:module|ecu|pcm|ecm|control module)$", re.I), "неисправный ЭБУ/модуль"),
    (re.compile(r"^failed (?:module|ecu|pcm|ecm)$", re.I), "неисправный ЭБУ/модуль"),
    (re.compile(r"^blown fuse$", re.I), "перегоревший предохранитель"),
    (re.compile(r"^fuse(?:s)? blown or missing$", re.I), "перегоревший или отсутствующий предохранитель"),
    (re.compile(r"^low (?:coolant|engine coolant) level$", re.I), "низкий уровень охлаждающей жидкости"),
    (re.compile(r"^low (?:oil|engine oil) level$", re.I), "низкий уровень моторного масла"),
    (re.compile(r"^low fuel level$", re.I), "низкий уровень топлива"),
    (re.compile(r"^low battery voltage$", re.I), "низкое напряжение аккумулятора"),
    (re.compile(r"^weak battery$", re.I), "разряженный аккумулятор"),
    (re.compile(r"^poor (?:battery )?connection(?:s)?$", re.I), "плохой контакт клемм"),
    (re.compile(r"^corroded (?:battery )?terminals$", re.I), "окисленные клеммы аккумулятора"),
    (re.compile(r"^vacuum leak(?:s)?$", re.I), "подсос воздуха во впускном тракте (вакуумная утечка)"),
    (re.compile(r"^unmetered air (?:leak|leaks|after maf)$", re.I), "неучтённый подсос воздуха после расходомера"),
    (re.compile(r"^air leak (?:after|downstream of) maf$", re.I), "подсос воздуха после расходомера (MAF)"),
    (re.compile(r"^exhaust leak$", re.I), "утечка выхлопных газов"),
    (re.compile(r"^exhaust (?:gas )?leak(?:s)? (?:before|near|upstream) (?:o2|oxygen|catalyst)", re.I), "утечка выхлопных газов до датчика кислорода/нейтрализатора"),
    (re.compile(r"^clogged (?:catalyst|dpf|filter|injector)s?$", re.I), "засорение (катализатора/фильтра/форсунки)"),
    (re.compile(r"^restricted (?:exhaust|air intake|fuel filter)$", re.I), "засорение выпускной/впускной системы или топливного фильтра"),
    (re.compile(r"^dirty (?:maf|mass air flow) sensor$", re.I), "загрязнённый датчик массового расхода воздуха"),
    (re.compile(r"^contaminated (?:maf|mass air flow) sensor$", re.I), "загрязнённый датчик массового расхода воздуха"),
    (re.compile(r"^stuck (?:open|closed|on|off) (?:valve|actuator|thermostat)$", re.I), "заклинивший клапан/механизм"),
    (re.compile(r"^faulty thermostat$", re.I), "неисправный термостат"),
    (re.compile(r"^stuck (?:open|closed) thermostat$", re.I), "термостат заклинил"),
    (re.compile(r"^worn (?:spark plugs|plugs)$", re.I), "изношенные свечи зажигания"),
    (re.compile(r"^faulty (?:spark plugs|spark plug|plug)s?$", re.I), "неисправные свечи зажигания"),
    (re.compile(r"^worn (?:ignition )?coil(?:s)?$", re.I), "изношенная катушка зажигания"),
    (re.compile(r"^failed (?:ignition )?coil(?:s)?$", re.I), "неисправная катушка зажигания"),
    (re.compile(r"^bad fuel(?: quality)?$", re.I), "некачественное топливо"),
    (re.compile(r"^wrong fuel$", re.I), "неподходящее топливо"),
    (re.compile(r"^low fuel pressure$", re.I), "низкое давление топлива"),
    (re.compile(r"^high fuel pressure$", re.I), "высокое давление топлива"),
    (re.compile(r"^fuel starvation$", re.I), "недостаток подачи топлива"),
    (re.compile(r"^clogged fuel filter$", re.I), "засорённый топливный фильтр"),
    (re.compile(r"^failed (?:fuel )?pump$", re.I), "неисправный топливный насос"),
    (re.compile(r"^software (?:issue|problem|error|bug|needs update)$", re.I), "проблема программного обеспечения ЭБУ"),
    (re.compile(r"^outdated software$", re.I), "устаревшее программное обеспечение ЭБУ"),
    (re.compile(r"^loose ground (?:connection|strap)$", re.I), "плохой контакт массового провода"),
    (re.compile(r"^ground (?:connection|wire) problem$", re.I), "проблема с массовым проводом"),
    (re.compile(r"^circuit problem$", re.I), "проблема в цепи"),
    (re.compile(r"^internal (?:ecu|module|control module) (?:failure|fault|problem)$", re.I), "внутренняя неисправность ЭБУ"),
    (re.compile(r"^evap system leak$", re.I), "утечка в системе EVAP"),
    (re.compile(r"^loose (?:fuel )?cap$", re.I), "неплотно закрученная крышка бензобака"),
    (re.compile(r"^bad (?:fuel )?cap(?: seal)?$", re.I), "неисправная крышка бензобака"),
    (re.compile(r"^wrong oil (?:type|viscosity)$", re.I), "неподходящее моторное масло"),
    (re.compile(r"^oil (?:contamination|level incorrect)$", re.I), "проблема с моторным маслом"),
    (re.compile(r"^sensor (?:out of|outside) calibration$", re.I), "датчик вне калибровки"),
    (re.compile(r"^mechanical (?:fault|problem|wear|damage)$", re.I), "механическая неисправность/износ"),
    (re.compile(r"^timing (?:belt|chain) (?:problem|stretched|worn)$", re.I), "проблема с ремнём/цепью ГРМ"),
    (re.compile(r"^incorrect timing$", re.I), "неправильные фазы/углы"),
    (re.compile(r"^low compression$", re.I), "низкая компрессия"),
    (re.compile(r"^clogged (?:egr|evap) (?:valve|passage|system)$", re.I), "засорение клапана/канала"),
    (re.compile(r"^failed (?:egr|purge|vent) valve$", re.I), "неисправный клапан"),
    (re.compile(r"^expired (?:def|urea)$", re.I), "испорченная жидкость AdBlue"),
    (re.compile(r"^low (?:def|urea) level$", re.I), "низкий уровень AdBlue"),
    (re.compile(r"^ CAN bus (?:issue|fault|problem)$", re.I), "проблема на шине CAN"),
    (re.compile(r"^can bus (?:issue|fault|problem)$", re.I), "проблема на шине CAN"),
    (re.compile(r"^wiring short(?:ed)?$", re.I), "короткое замыкание в проводке"),
    (re.compile(r"^short(?:ed)? wiring$", re.I), "короткое замыкание в проводке"),
    (re.compile(r"^sensor (?:connector|wiring) (?:problem|damage|damaged)$", re.I), "проблема с разъёмом/проводкой датчика"),
    (re.compile(r"^intermittent (?:connection|contact|wiring)$", re.I), "нестабильный контакт"),
    (re.compile(r"^heat damage$", re.I), "повреждение от нагрева"),
    (re.compile(r"^software or hardware (?:issue|fault)$", re.I), "проблема ПО или «железа» ЭБУ"),
    (re.compile(r"^short(?:ed)? (?:to|against) (?:\+|plus|b\+|12v|battery voltage)$", re.I), "короткое замыкание на плюс"),
    (re.compile(r"^short(?:ed)? (?:to|against) (?:-|ground|earth|mass|chassis)$", re.I), "короткое замыкание на массу"),
    (re.compile(r"^open(?:ed)? (?:circuit|wiring|wire|wire\\(s\\))$", re.I), "обрыв в цепи"),
    (re.compile(r"^broken (?:wire|wiring|wire\\(s\\)|circuit|connector|wire harness)$", re.I), "обрыв провода/цепи"),
    (re.compile(r"^damaged (?:wire|wiring|circuit|connector|wire harness|cable)$", re.I), "повреждение провода/цепи"),
    (re.compile(r"^chafed (?:wire|wiring|harness)$", re.I), "перетёртая проводка"),
    (re.compile(r"^melted (?:wire|wiring|harness|connector)$", re.I), "оплавленная проводка"),
    (re.compile(r"^water (?:in|intrusion|damage|ingress)(?: (?:in|into) (?:connector|ecu|module))?$", re.I), "попадание воды в разъём/блок"),
    (re.compile(r"^corrosion(?: on| in| of)? (?:connector|terminals?|wiring|sensor pins?)$", re.I), "коррозия контактов/разъёма"),
    (re.compile(r"^oxidized (?:connector|contacts?|terminals?)$", re.I), "окисленные контакты"),
    (re.compile(r"^pin(?:s)? (?:not )?(?:seated|engaged|mismatched|bent)$", re.I), "плохо seated/погнутые контакты разъёма"),
    (re.compile(r"^sticking (?:valve|actuator|piston|throttle)$", re.I), "заклинивание механизма"),
    (re.compile(r"^carbon (?:buildup|deposits?|fouling)$", re.I), "нагар/отложения углерода"),
    (re.compile(r"^oil (?:contamination|in (?:sensor|connector|harness)|leak|sludge)$", re.I), "загрязнение маслом/утечка масла"),
    (re.compile(r"^coolant (?:leak|loss|contamination)$", re.I), "утечка/потеря охлаждающей жидкости"),
    (re.compile(r"^refrigerant (?:leak|low|loss)$", re.I), "утечка хладагента кондиционера"),
    (re.compile(r"^boost (?:leak|hose leak|pipe leak|leak in (?:the )?(?:intake|boost) (?:system|pipes?))$", re.I), "утечка в тракте наддува"),
    (re.compile(r"^intercooler (?:leak|damage|blocked|fouled)$", re.I), "неисправность/засорение интеркулера"),
    (re.compile(r"^worn (?:turbo|turbocharger|bearings?|seals?|pump|clutch|timing chain|piston rings?)$", re.I), "износ турбины/подшипников/уплотнений"),
    (re.compile(r"^failed (?:turbo|supercharger|actuator|vgt|vanes?)$", re.I), "неисправность турбины/актюатора"),
    (re.compile(r"^stuck (?:closed|open) wastegate$", re.I), "заклинивший wastegate"),
    (re.compile(r"^clogged (?:dpf|particulate filter|catalyst|egr|egr passages?|injectors?|nozzles?)$", re.I), "засорение (DPF/катализатор/EGR/форсунки)"),
    (re.compile(r"^defective (?:sensor|valve|actuator|component|part|module)$", re.I), "дефектный датчик/компонент"),
    (re.compile(r"^sensor (?:drift|out of range|stuck|contamination)$", re.I), "дрейф/загрязнение датчика"),
    (re.compile(r"^injector (?:wear|failure|leak|balance issue|clogged|stuck)$", re.I), "неисправность/износ форсунки"),
    (re.compile(r"^ignition (?:misfire|coil failure|spark blowout|breakdown)$", re.I), "неисправность в системе зажигания"),
    (re.compile(r"^spark plug (?:gap|fouling|wear|failure)$", re.I), "проблема свечей зажигания"),
    (re.compile(r"^mechanical (?:engine problem|engine issue|failure|stress|damage|issue|worn engine)$", re.I), "механическая проблема двигателя"),
    (re.compile(r"^excessive (?:engine wear|load|heat|temperature|vibration)$", re.I), "чрезмерный износ/нагрузка/нагрев"),
    (re.compile(r"^camshaft (?:wear|damage|deactivation|timing error|timing retarded)$", re.I), "износ/ошибка фаз распредвала"),
    (re.compile(r"^timing (?:chain|belt) (?:stretch|stretched|worn|jumped|skip|failure)$", re.I), "растяжение/износ цепи или ремня ГРМ"),
    (re.compile(r"^low (?:compression|engine compression)$", re.I), "низкая компрессия"),
    (re.compile(r"^valve (?:clearance|adjustment|stuck|leak|seals?)$", re.I), "проблема клапанов/зазоров"),
    (re.compile(r"^head gasket (?:leak|failure)$", re.I), "пробой прокладки ГБЦ"),
    (re.compile(r"^fuel (?:contamination|dilution|quality poor|poor quality|starvation|pressure drop)$", re.I), "проблема с топливом/подачей топлива"),
    (re.compile(r"^air (?:restriction|intake restriction|filter clogged|filter blocked)$", re.I), "ограничение впуска воздуха (фильтр)"),
    (re.compile(r"^malfunctioning (?:relay|fuse|switch|sensor|actuator)$", re.I), "неисправное реле/предохранитель/датчик"),
    (re.compile(r"^failing (?:relay|sensor|module|pump|battery|alternator|generator)$", re.I), "деградирующее реле/датчик/насос/генератор"),
    (re.compile(r"^battery (?:weak|failing|drain|draw|load)$", re.I), "проблема аккумулятора (разряд/утечка тока)"),
    (re.compile(r"^parasitic (?:draw|drain)$", re.I), "паразитный ток утечки"),
    (re.compile(r"^bad (?:relay|fuse|ground|earth|wire|wiring|connection|connection at (?:sensor|module|ecu))$", re.I), "плохой контакт/реле/предохранитель"),
    (re.compile(r"^poor (?:electrical connection|ground|wiring|contact)$", re.I), "плохой электрический контакт"),
    (re.compile(r"^signal (?:interference|noise|ground offset)$", re.I), "помехи/наводки в сигнале"),
    (re.compile(r"^emissions (?:equipment tampering|component removed|modification)$", re.I), "вмешательство в системы выбросов"),
    (re.compile(r"^aftermarket (?:parts|exhaust|intake|tune|chip)$", re.I), "несерийные детали/прошивка"),
    (re.compile(r"^tuned (?:ecu|engine)$", re.I), "изменённая прошивка ЭБУ"),
    (re.compile(r"^incorrect (?:installation|part|component|repair|fluid|oil type)$", re.I), "неправильная установка/деталь/жидкость"),
    (re.compile(r"^low (?:adblue|def|reductant|urea) (?:level|quality)$", re.I), "низкий уровень/качество AdBlue"),
    (re.compile(r"^crystallized (?:def|urea|reductant)$", re.I), "кристаллизация AdBlue"),
    (re.compile(r"^nox sensor (?:failure|drift|contamination)$", re.I), "неисправность датчика NOx"),
    (re.compile(r"^lambda (?:control deviation|sensor drift)$", re.I), "отклонение лямбда-регулирования"),
    (re.compile(r"^can (?:bus wiring issue|message error|timeout)$", re.I), "проблема шины CAN"),
    (re.compile(r"^module (?:not configured|coding incorrect|needs coding|missing communication)$", re.I), "блок не сконфигурирован/нет связи"),
    (re.compile(r"^component (?:protection active|not matched)$", re.I), "активна защита компонента (Component Protection)"),
    (re.compile(r"^immobilizer (?:mismatch|issue|active)$", re.I), "проблема иммобилайзера"),
    (re.compile(r"^key (?:not recognized|mismatch)$", re.I), "ключ не распознан"),
    (re.compile(r"^incorrect (?:vin|mIL request) received$", re.I), "получен неверный VIN/запрос MIL"),
]


def translate_cause(text):
    t = re.sub(r"\s+", " ", (text or "").strip()).lower().rstrip(".")
    # Strip common trailing context so the core phrase matches the lexicon.
    t = re.sub(
        r"\s+(?:in|on|of|at|from)\s+(?:the\s+)?(?:wiring|harness|wire harness|circuit|connector|sensor|module|ecu|system|component|valve|pump|engine|vehicle|car|actuator|relay|fuse)$",
        "",
        t,
    )
    for rx, ru in CAUSE_PATTERNS:
        if rx.match(t):
            return ru
    return None


def stats():
    return {
        "components": len(COMPONENTS),
        "faults": len(FAULTS),
        "specials": len([1 for _, t in SPECIALS if t is not None]),
        "sentences": len(SENTENCES),
        "cause_patterns": len(CAUSE_PATTERNS),
    }
