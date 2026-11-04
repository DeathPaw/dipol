#!/usr/bin/env python3
"""ETL: перенос действующих учётных таблиц «Диполь» в схему ERP.

На вход — шесть выгруженных книг Google Sheets из docs/legacy-data.
На выход — набор .sql в docs/legacy-data/out, применяемый psql'ом по порядку,
и протокол контрольной сверки (US-N5).

Принципы переноса:
  * связи строятся по ID дома, а не по тексту адреса; неопознанные адреса
    заводятся отдельными карточками и попадают в очередь качества данных;
  * для каждой строки сохраняется источник (книга, лист, строка) в legacy_map;
  * ошибочные формулы legacy (выплата вырубки по p вместо k) НЕ переносятся:
    переносится только исходный факт, расчёт делает ERP;
  * разные физические единицы не сводятся в «общий объём».

Запуск:
    python3 tools/migration/etl.py
    bash docs/legacy-data/out/apply.sh
"""

import collections
import datetime as dt
import os
import sys

from openpyxl import load_workbook

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import (address_key, clean, date, datetime_, display_address,  # noqa: E402
                    intval, month_start, norm_name, num, q, text, unit)

BASE = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DATA = os.path.join(BASE, "docs", "legacy-data")
OUT = os.path.join(DATA, "out")

BOOK_MKD = "МКД"
BOOK_REQUESTS = "Заявки_Лок.Рем."
BOOK_MATERIALS = "Диполь — материалы и ФОТ"
BOOK_CASH = "Кассы"
BOOK_RK3 = "План РК3"
BOOK_B2C = "Расчет B2C для мягкой кровли"


class Out:
    """Накопитель SQL-операторов для одного файла."""

    def __init__(self, name, title):
        self.name = name
        self.lines = ["-- " + title,
                      "-- Сгенерировано tools/migration/etl.py, править вручную не нужно.",
                      "BEGIN;"]

    def add(self, sql):
        self.lines.append(sql if sql.rstrip().endswith(";") else sql + ";")

    def comment(self, text_):
        self.lines.append("")
        self.lines.append("-- " + text_)

    def write(self):
        self.lines.append("COMMIT;")
        path = os.path.join(OUT, self.name)
        with open(path, "w", encoding="utf-8") as f:
            f.write("\n".join(self.lines) + "\n")
        return path


report = collections.OrderedDict()
issues = []


def note(metric, value):
    report[metric] = value


def issue(kind, severity, message):
    issues.append((kind, severity, message))


# ---------------------------------------------------------------------------
# Справочники соответствий
# ---------------------------------------------------------------------------

# Проект в legacy-таблицах → (код проекта ERP, код конфигурации, тип клиента).
PROJECT_MAP = {
    "абонентский договор": ("AB", "AB_MAIN", "ЖКХ"),
    "абонентский договор 1": ("AB", "AB_MAIN", "ЖКХ"),
    "абонентский договор 2": ("AB", "AB_MAIN", "ЖКХ"),
    "абонентский договор (доп.)": ("AB-DOP", "AB_EXTRA", "ЖКХ"),
    "швы": ("SHV1", "SEAMS", "ЖКХ"),
    "козырьки": ("KZ", "CANOPY", "ЖКХ"),
    "снег, наледь": ("SN", "SNOW", "ЖКХ"),
    "рк3 - ремонт кровли большие объемы": ("RK3", "RK3", "ЖКХ"),
    "рк3": ("RK3", "RK3", "ЖКХ"),
    "b2c": ("B2C", "B2C", "B2C"),
    "прочие работы": ("PROCHEE", "B2C", "ЖКХ"),
    "скатная кровля": ("SKAT", "PITCHED", "B2C"),
    "общие расходники": ("OBSHIE", "B2C", "ЖКХ"),
    "волхов": ("VOLHOV", "B2C", "B2C"),
}

PROJECT_TITLES = {
    "AB": "Локальный ремонт — абонентский договор",
    "AB-DOP": "Локальный ремонт — абонентский договор (доп.)",
    "SHV1": "Межпанельные швы",
    "KZ": "Козырьки",
    "SN": "Снег и наледь",
    "RK3": "РК-3 — ремонт кровли большие объёмы",
    "B2C": "Частные заказы (B2C)",
    "PROCHEE": "Прочие работы",
    "SKAT": "Скатная кровля",
    "OBSHIE": "Общие расходники",
    "VOLHOV": "Волхов",
}

CLIENT_ZHKH = "ООО «ЖКХ Сиверский»"
CLIENT_B2C = "Частные заказчики (B2C)"

# Заголовок колонки объёма в месячном листе → код работы.
SKU_BY_HEADER = [
    ("примыкание к вентканалу", "AB_ADJ_VENT"),
    ("внутреннему ливнестоку", "AB_ADJ_DRAIN"),
    ("заплатка", "AB_PATCH"),
    ("канализационному стояку", "AB_ADJ_SEWER"),
    ("электрощита", "AB_ADJ_PANEL"),
    ("вентиляционному стояку", "AB_ADJ_STACK"),
    ("уборка и вывоз мусора", "AB_WASTE"),
    ("дефектовка", "AB_DEFECT"),
    ("кромке кровли", "SN_EDGE"),
    ("горизонтальной поверхности", "SN_HORIZ"),
    ("ливнестока хим", "SN_DRAIN"),
    ("герметизация межпанельных швов", "SV_SEAL"),
    ("очистка козырька", "KZ_CLEAN"),
    ("козырька над входной группой", "KZ_REPAIR_ENTRY"),
    ("козырька лоджии", "KZ_REPAIR_LOGGIA"),
]

FIN_CLASS = {
    "работа по договору аб": "AB_CONTRACT",
    "сверх аб": "ABOVE_AB",
    "гарантия": "WARRANTY",
    "принято": "AB_CONTRACT",
}


def sku_for_header(header):
    if not header:
        return None
    h = str(header).lower()
    # Порядок важен: «козырька над входной группой» и «очистка козырька»
    # пересекаются по подстроке, поэтому очистку проверяем первой.
    if "очистка козырька" in h:
        return "KZ_CLEAN"
    for needle, code in SKU_BY_HEADER:
        if needle in h:
            return code
    return None


# ---------------------------------------------------------------------------
# Мастер-данные: ЖЭУ, дома, клиенты, сотрудники, проекты
# ---------------------------------------------------------------------------

def load_master(out):
    wb = load_workbook(os.path.join(DATA, "mkd.xlsx"), read_only=True, data_only=True)

    out.comment("ЖЭУ")
    zheu_rows = 0
    ws = wb["ЖЭУ"]
    for i, row in enumerate(ws.iter_rows(min_row=2, max_col=5, values_only=True), 2):
        legacy_id = intval(row[0])
        name = text(row[1])
        if not legacy_id or not name:
            continue
        out.add(
            "INSERT INTO zheu (legacy_id, name, head_name, address, settlements) VALUES "
            f"({q(legacy_id)}, {q(name)}, {q(text(row[2]))}, {q(text(row[3]))}, {q(text(row[4]))})"
        )
        zheu_rows += 1
    note("ЖЭУ перенесено", zheu_rows)

    out.comment("МКД: карточки домов со стабильным ID")
    buildings = {}          # legacy_id -> dict
    by_key = {}             # address_key -> legacy_id
    ws = wb["МКД"]
    for i, row in enumerate(ws.iter_rows(min_row=2, max_col=8, values_only=True), 2):
        legacy_id = intval(row[0])
        address = display_address(text(row[1]))
        if not legacy_id or not address:
            continue
        if legacy_id in buildings:
            # В листе МКД встречается один и тот же ID_Дома в двух строках:
            # берём первую, вторую отправляем в очередь качества данных.
            issue("BUILDING_DUPLICATE_ID", "WARNING",
                  f"Лист МКД, строка {i}: ID_Дома {legacy_id} уже встречался "
                  f"(«{buildings[legacy_id]['address']}» и «{address}») — вторая строка не перенесена")
            continue
        key = address_key(address)
        rec = {
            "legacy_id": legacy_id,
            "address": address,
            "key": key,
            "floors": intval(row[2]),
            "zheu": intval(row[3]),
            "apartments": intval(row[4]),
            "entrances": intval(row[5]),
            "per_entrance": intval(row[6]),
            "roof": text(row[7]),
            "row": i,
        }
        buildings[legacy_id] = rec
        # Дубли ключа возможны: два дома с одинаковым нормализованным адресом.
        # Первый выигрывает, остальные помечаются как требующие разбора.
        if key in by_key and by_key[key] != legacy_id:
            issue("BUILDING_DUPLICATE_KEY", "WARNING",
                  f"Одинаковый нормализованный адрес у домов {by_key[key]} и {legacy_id}: {address}")
        else:
            by_key[key] = legacy_id

        out.add(
            "INSERT INTO buildings (legacy_id, address, address_key, zheu_id, floors, "
            "entrances_count, apartments_count, apartments_per_entrance, roof_type) VALUES ("
            f"{q(legacy_id)}, {q(address)}, {q(key)}, "
            f"(SELECT id FROM zheu WHERE legacy_id = {q(rec['zheu'])}), "
            f"{q(rec['floors'])}, {q(rec['entrances'])}, {q(rec['apartments'])}, "
            f"{q(rec['per_entrance'])}, {q(rec['roof'])})"
        )
        out.add(
            "INSERT INTO legacy_map (entity_type, entity_id, source_book, source_sheet, source_row, source_key) "
            f"SELECT 'BUILDING', id, {q(BOOK_MKD)}, 'МКД', {q(i)}, {q(str(legacy_id))} "
            f"FROM buildings WHERE legacy_id = {q(legacy_id)}"
        )
    note("Домов перенесено", len(buildings))

    out.comment("Подъезды: диапазоны квартир из количества подъездов и квартир в подъезде")
    entrance_rows = 0
    for rec in buildings.values():
        n = rec["entrances"]
        per = rec["per_entrance"]
        if not n or not per or n <= 0 or per <= 0:
            continue
        for e in range(1, n + 1):
            first = (e - 1) * per + 1
            last = e * per
            out.add(
                "INSERT INTO building_entrances (building_id, number, apartment_from, apartment_to, note) "
                f"SELECT id, {q(e)}, {q(first)}, {q(last)}, "
                "'Диапазон рассчитан при миграции из равномерного деления — проверить на нестандартных домах' "
                f"FROM buildings WHERE legacy_id = {q(rec['legacy_id'])} "
                "ON CONFLICT (building_id, number) DO NOTHING"
            )
            entrance_rows += 1
    note("Подъездов заведено", entrance_rows)
    issue("BUILDING_ENTRANCE_ESTIMATED", "WARNING",
          f"{entrance_rows} диапазонов квартир рассчитаны равномерным делением: "
          "для нестандартных домов подъезд заявки может определяться неверно")

    out.comment("Алиасы адресов из листа «Лист3» — второе написание того же дома")
    alias_rows = 0
    if "Лист3" in wb.sheetnames:
        ws = wb["Лист3"]
        for row in ws.iter_rows(min_row=2, max_col=5, values_only=True):
            legacy_id = intval(row[0])
            alias = text(row[1])
            if not legacy_id or not alias or legacy_id not in buildings:
                continue
            key = address_key(alias)
            if not key or key == buildings[legacy_id]["key"]:
                continue
            out.add(
                "INSERT INTO building_aliases (building_id, alias, alias_key, source, created_by) "
                f"SELECT id, {q(display_address(alias))}, {q(key)}, 'LEGACY', 'migration' "
                f"FROM buildings WHERE legacy_id = {q(legacy_id)} "
                "ON CONFLICT (alias_key, source) DO NOTHING"
            )
            alias_rows += 1
            by_key.setdefault(key, legacy_id)
    note("Алиасов адресов", alias_rows)
    wb.close()
    return buildings, by_key


def load_clients_projects(out, employees):
    out.comment("Клиенты")
    out.add(
        "INSERT INTO clients (client_type, name, short_name, comment) VALUES "
        f"('LEGAL', {q(CLIENT_ZHKH)}, 'ЖКХ Сиверский', "
        "'Заведён при миграции по данным листа «Импорт» (организация в заявках заказчика). "
        "Реквизиты требуют уточнения')"
    )
    out.add(
        "INSERT INTO clients (client_type, name, comment) VALUES "
        f"('PERSON', {q(CLIENT_B2C)}, "
        "'Технический клиент для исторических частных заказов: в legacy-таблицах "
        "конкретный заказчик по строке ФОТ и материалов не указан')"
    )

    out.comment("Сотрудники (из листов ФОТ, козырьков и швов)")
    for name, role in sorted(employees.items()):
        out.add(
            "INSERT INTO employees (full_name, short_name, role_code, note) VALUES "
            f"({q(name)}, {q(name)}, {q(role)}, 'Заведён при миграции из учётных таблиц')"
        )
    note("Сотрудников заведено", len(employees))

    out.comment("Проекты — экземпляры конфигураций у клиента")
    for code, title in PROJECT_TITLES.items():
        config = next((c for (p, c, _) in PROJECT_MAP.values() if p == code), "B2C")
        client = CLIENT_B2C if code in ("B2C", "SKAT", "VOLHOV") else CLIENT_ZHKH
        lifecycle = "ONE_OFF" if code in ("SKAT", "VOLHOV") else "PERMANENT"
        started = "2024-10-01"
        out.add(
            "INSERT INTO projects (code, name, client_id, config_id, config_version, lifecycle_type, "
            "started_on, finish_criteria, status, note, created_by) VALUES ("
            f"{q(code)}, {q(title)}, "
            f"(SELECT id FROM clients WHERE name = {q(client)}), "
            f"(SELECT id FROM project_configs WHERE code = {q(config)}), 1, {q(lifecycle)}, "
            f"{q(started)}, "
            + (q("Завершение договорных работ по объекту") if lifecycle == "ONE_OFF" else "NULL")
            + ", 'ACTIVE', 'Создан при миграции из учётных таблиц', 'migration')"
        )
    note("Проектов заведено", len(PROJECT_TITLES))


def collect_employees():
    """Имена исполнителей из всех книг. Мусорные строки-адреса отсеиваются."""
    names = {}
    wb = load_workbook(os.path.join(DATA, "materialy_i_fot.xlsx"), read_only=True, data_only=True)
    for sheet in [n for n in wb.sheetnames if n.startswith("ФОТ ")]:
        ws = wb[sheet]
        for row in ws.iter_rows(min_row=2, max_col=9, values_only=True):
            name = norm_name(row[8])
            if not name:
                continue
            # В колонке исполнителя встречаются вставленные адреса объектов.
            if any(ch.isdigit() for ch in name) or "," in name:
                continue
            role = "ROPE_ACCESS" if "промальп" in name.lower() else "ROOFER"
            names.setdefault(name, role)
    if "Компенсации" in wb.sheetnames:
        ws = wb["Компенсации"]
        for row in ws.iter_rows(min_row=2, max_col=4, values_only=True):
            name = norm_name(row[2])
            if name and not any(ch.isdigit() for ch in name) and "," not in name:
                names.setdefault(name, "ROOFER")
    wb.close()
    return names


# ---------------------------------------------------------------------------
# Заявки
# ---------------------------------------------------------------------------

def load_building_index_from_rk3():
    """«№ заявки в смете» → ID_Дома из листа План РК3 → Заявки_Лок.Рем."""
    path = os.path.join(DATA, "plan_rk3.xlsx")
    if not os.path.exists(path):
        return {}
    wb = load_workbook(path, read_only=True, data_only=True)
    index = {}
    if "Заявки_Лок.Рем." in wb.sheetnames:
        ws = wb["Заявки_Лок.Рем."]
        rows = ws.iter_rows(min_row=3, max_col=33, values_only=True)
        for row in rows:
            key = text(row[0])
            building = intval(row[32]) if len(row) > 32 else None
            if key and building:
                index[key] = building
    wb.close()
    return index


class RequestLoader:
    """Разбор листов заявок и генерация SQL. Нумерация — своя, сквозная по проекту."""

    def __init__(self, out, buildings, by_key, rk3_index):
        self.out = out
        self.buildings = buildings
        self.by_key = by_key
        self.rk3_index = rk3_index
        self.seq = collections.Counter()     # (project, year) -> номер
        self.extra_buildings = {}            # address_key -> (address, temp id)
        self.count = 0
        self.per_sheet = collections.Counter()
        self.unmatched = 0

    def building_ref(self, legacy_key, address):
        """SQL-выражение, дающее id дома. Неопознанный адрес заводится карточкой."""
        legacy_id = self.rk3_index.get(legacy_key)
        if not legacy_id:
            legacy_id = self.by_key.get(address_key(address))
        if legacy_id:
            return f"(SELECT id FROM buildings WHERE legacy_id = {q(legacy_id)})"

        key = address_key(address)
        if key not in self.extra_buildings:
            self.extra_buildings[key] = display_address(address)
            self.unmatched += 1
        return f"(SELECT id FROM buildings WHERE address_key = {q(key)} ORDER BY id LIMIT 1)"

    def emit_extra_buildings(self):
        if not self.extra_buildings:
            return
        self.out.comment("Дома, отсутствующие в реестре МКД: заведены по адресу из заявки")
        for key, address in sorted(self.extra_buildings.items()):
            self.out.add(
                "INSERT INTO buildings (address, address_key, condition_note) VALUES "
                f"({q(address)}, {q(key)}, "
                "'Заведён при миграции: адрес встречался в заявках, но отсутствует в реестре МКД')"
            )
            self.out.add(
                "INSERT INTO data_issues (issue_type, severity, entity_type, entity_id, message, responsible) "
                f"SELECT 'ADDRESS_UNMATCHED', 'WARNING', 'BUILDING', id, "
                f"'Адрес из заявок не найден в реестре МКД — проверить и объединить с существующим домом', "
                f"'migration' FROM buildings WHERE address_key = {q(key)}"
            )

    def number_for(self, project, day):
        year = day.year if day else 2026
        self.seq[(project, year)] += 1
        return f"{project}_{year}_{self.seq[(project, year)]}", self.seq[(project, year)]

    def emit(self, *, project, sheet, row_no, legacy_key, address, apartment,
             external_number, received, done, status, fin_class, customer_closed,
             description, act_wording, comment, zheu_legacy, items,
             report_link=None, photo_link=None, report_text=None,
             started=None, entrance=None, repair_type=None):
        day = done or received or dt.date(2026, 1, 1)
        number, seq = self.number_for(project, day)
        building = self.building_ref(legacy_key, address)

        received_dt = datetime_(received) or dt.datetime.combine(day, dt.time())
        done_dt = datetime_(done)

        parts = [
            "WITH r AS (INSERT INTO requests (number, seq_in_project, external_number, external_system, "
            "source_code, client_id, project_id, building_id, zheu_id, apartment, entrance, "
            "received_at_customer, received_at, done_at, status, financial_class, customer_closed, "
            "description, act_wording, internal_comment, report_link, photo_link, report_text, created_by) "
            "VALUES ("
            f"{q(number)}, {q(seq)}, {q(external_number)}, 'СУПЕР МКД+', 'CUSTOMER_SYSTEM', "
            f"(SELECT id FROM clients WHERE name = {q(CLIENT_ZHKH if project != 'B2C' else CLIENT_B2C)}), "
            f"(SELECT id FROM projects WHERE code = {q(project)}), "
            f"{building}, "
            f"(SELECT id FROM zheu WHERE legacy_id = {q(zheu_legacy)}), "
            f"{q(apartment)}, {q(entrance)}, "
            f"{q(datetime_(received))}, {q(received_dt)}, {q(done_dt)}, "
            f"{q(status)}, {q(fin_class)}, {q(bool(customer_closed))}, "
            f"{q(description)}, {q(act_wording)}, {q(comment)}, "
            f"{q(report_link)}, {q(photo_link)}, {q(report_text)}, 'migration') RETURNING id)"
        ]

        for idx, (sku_code, qty, item_unit, ref_qty, ref_unit, item_status) in enumerate(items):
            parts.append(
                f", i{idx} AS (INSERT INTO request_items (request_id, sku_id, unit_code, declared_qty, "
                "fact_qty, accepted_qty, reference_qty, reference_unit_code, repair_type, status, sort_order) "
                f"SELECT r.id, s.id, {q(item_unit)}, {q(qty)}, {q(qty)}, {q(qty)}, "
                f"{q(ref_qty)}, {q(ref_unit)}, {q(repair_type)}, {q(item_status)}, {q((idx + 1) * 10)} "
                f"FROM r, sku s WHERE s.code = {q(sku_code)})"
            )

        parts.append(
            ", h AS (INSERT INTO request_status_history (request_id, from_status, to_status, reason, actor) "
            f"SELECT r.id, NULL, {q(status)}, 'Перенос из учётной таблицы', 'migration' FROM r)"
        )
        parts.append(
            ", e AS (INSERT INTO request_events (request_id, event_type, text, actor) "
            f"SELECT r.id, 'CREATED', {q('Заявка перенесена из листа «' + sheet + '», строка ' + str(row_no))}, "
            "'migration' FROM r)"
        )
        parts.append(
            "INSERT INTO legacy_map (entity_type, entity_id, source_book, source_sheet, source_row, source_key) "
            f"SELECT 'REQUEST', r.id, {q(BOOK_REQUESTS)}, {q(sheet)}, {q(row_no)}, {q(legacy_key)} FROM r"
        )
        self.out.add("\n".join(parts))
        self.count += 1
        self.per_sheet[sheet] += 1


def sane_date(value, where):
    """Дата с проверкой диапазона.

    В листах встречаются явно испорченные значения (1911 год): такую дату
    нельзя переносить как факт — она ломает отчёты за период. Значение
    отбрасывается, а строка попадает в очередь качества данных.
    """
    d = date(value)
    if d is None:
        return None
    if d.year < 2020 or d > dt.date.today() + dt.timedelta(days=365):
        issue("DATE_OUT_OF_RANGE", "WARNING",
              f"{where}: дата «{d.isoformat()}» вне разумного диапазона — не перенесена")
        return None
    return d


def status_for(done, customer_closed, act_link):
    """Статус ERP по признакам legacy-строки."""
    if not done:
        return "CREATED"
    if act_link:
        return "ACT_CREATED"
    if customer_closed:
        return "CUSTOMER_ACCEPTED"
    return "DONE"


def yes(value):
    v = text(value)
    return bool(v) and v.strip().lower() in ("да", "yes", "true", "1", "✓")


def load_requests(out, buildings, by_key):
    rk3_index = load_building_index_from_rk3()
    note("Соответствий «заявка → ID дома» из Плана РК3", len(rk3_index))

    wb = load_workbook(os.path.join(DATA, "zayavki_lok_rem.xlsx"), read_only=True, data_only=True)
    loader = RequestLoader(out, buildings, by_key, rk3_index)

    # Сначала — дома, которых нет в реестре: они должны появиться до заявок.
    month_sheets = [n for n in wb.sheetnames if len(n) == 7 and n[2] == "."]
    collected = []

    for sheet in sorted(month_sheets, key=lambda s: (s[3:], s[:2])):
        ws = wb[sheet]
        header = [text(c) for c in next(ws.iter_rows(min_row=4, max_row=4, values_only=True))]
        sku_cols = {}
        for idx, h in enumerate(header):
            code = sku_for_header(h)
            if code and 5 < idx < 12:
                sku_cols[idx] = code
        for i, row in enumerate(ws.iter_rows(min_row=6, max_col=34, values_only=True), 6):
            address = text(row[4])
            if not address:
                continue
            collected.append(("AB", sheet, i, row, sku_cols, header))

    # Снег и наледь
    if "Снег, наледь" in wb.sheetnames:
        ws = wb["Снег, наледь"]
        header = [text(c) for c in next(ws.iter_rows(min_row=4, max_row=4, values_only=True))]
        sku_cols = {idx: sku_for_header(h) for idx, h in enumerate(header)
                    if 5 < idx < 12 and sku_for_header(h)}
        for i, row in enumerate(ws.iter_rows(min_row=6, max_col=34, values_only=True), 6):
            if not text(row[4]):
                continue
            collected.append(("SN", "Снег, наледь", i, row, sku_cols, header))

    # Межпанельные швы: заявленный и принятый объём — разные колонки.
    if "Межп. швы" in wb.sheetnames:
        ws = wb["Межп. швы"]
        for i, row in enumerate(ws.iter_rows(min_row=6, max_col=34, values_only=True), 6):
            if not text(row[4]):
                continue
            collected.append(("SHV1", "Межп. швы", i, row, {6: "SV_SEAL"}, None))

    # Предварительный проход: зарегистрировать неопознанные адреса — карточки
    # домов должны появиться в SQL раньше, чем ссылающиеся на них заявки.
    for project, sheet, i, row, sku_cols, header in collected:
        loader.building_ref(text(row[0]), text(row[4]))
    for project, sheet, i, row in canopy_rows():
        loader.building_ref(text(row[0]), text(row[2]))
    loader.emit_extra_buildings()

    out.comment("Заявки: локальный ремонт, снег/наледь, швы")
    for project, sheet, i, row, sku_cols, header in collected:
        legacy_key = text(row[0]) or f"{sheet}_{i}"
        address = text(row[4])
        act_link = text(row[29]) if len(row) > 29 else None
        where = f'Заявки, лист «{sheet}», строка {i}'
        done = sane_date(row[19], where)
        customer_closed = yes(row[17])
        status_raw = (text(row[18]) or "").lower()

        items = []
        if project == "SHV1":
            declared = num(row[6])
            accepted = num(row[7])
            if declared or accepted:
                items.append(("SV_SEAL", accepted if accepted is not None else declared,
                              "MP", None, None, "ACCEPTED" if accepted else "DONE"))
            done = sane_date(row[20], where) or sane_date(row[19], where)
        else:
            for idx, code in sku_cols.items():
                value = num(row[idx]) if idx < len(row) else None
                if not value:
                    continue
                item_unit = {"AB_WASTE": "M3", "SN_EDGE": "MP", "SN_DRAIN": "PCS"}.get(code, "M2")
                items.append((code, value, item_unit, None, None, "ACCEPTED"))

        if not items:
            # Объём мог остаться только в общей колонке «Объем работ».
            total = num(row[13])
            if total:
                items.append(("OTHER", total, unit(row[14], "M2"), None, None, "ACCEPTED"))

        loader.emit(
            project=project, sheet=sheet, row_no=i, legacy_key=legacy_key, address=address,
            apartment=text(row[5]), external_number=text(row[2]),
            received=sane_date(row[3], where), done=done,
            status=status_for(done, customer_closed, act_link),
            fin_class=FIN_CLASS.get(status_raw, "OTHER"),
            customer_closed=customer_closed,
            description=text(row[16]) or text(row[12]),
            act_wording=text(row[12]),
            comment=text(row[30]) if len(row) > 30 else None,
            zheu_legacy=intval(row[21]),
            items=items,
            report_link=text(row[27]) if len(row) > 27 else None,
            photo_link=None,
            report_text=text(row[31]) if len(row) > 31 else None,
        )

    # Козырьки: вид работы задан текстом в колонке «Работа».
    out.comment("Заявки: козырьки")
    for project, sheet, i, row in canopy_rows():
        legacy_key = text(row[0]) or f"{sheet}_{i}"
        address = text(row[2])
        work = text(row[5]) or ""
        qty = num(row[6])
        hours = num(row[11])
        where = f'Козырьки, строка {i}'
        done = sane_date(row[10], where) or sane_date(row[9], where)

        items = []
        code = sku_for_header(work) or "KZ_REPAIR_ENTRY"
        if code == "KZ_CLEAN":
            # Очистка считается в чел·ч; площадь остаётся справочной величиной
            # и в начисление не попадает (US-H2).
            items.append(("KZ_CLEAN", hours, "MH", qty, "M2", "ACCEPTED"))
        elif qty:
            items.append((code, qty, "M2", None, None, "ACCEPTED"))

        loader.emit(
            project="KZ", sheet=sheet, row_no=i, legacy_key=legacy_key, address=address,
            apartment=text(row[4]), entrance=intval(row[3]),
            external_number=None, received=sane_date(row[9], where) or done, done=done,
            status=status_for(done, yes(row[7]), text(row[16]) if len(row) > 16 else None),
            fin_class="AB_CONTRACT", customer_closed=yes(row[7]),
            description=work, act_wording=work,
            comment=text(row[14]) if len(row) > 14 else None,
            zheu_legacy=None,
            items=items, repair_type=work,
            report_text=text(row[17]) if len(row) > 17 else None,
        )

    wb.close()
    note("Заявок перенесено", loader.count)
    for sheet, cnt in sorted(loader.per_sheet.items()):
        note(f"  заявок из листа «{sheet}»", cnt)
    note("Адресов не найдено в реестре МКД", loader.unmatched)
    if loader.unmatched:
        issue("ADDRESS_UNMATCHED", "WARNING",
              f"{loader.unmatched} адресов из заявок отсутствуют в реестре МКД — заведены отдельными карточками")
    return loader


def canopy_rows(_unused=None):
    """Строки козырьков.

    Берём лист «Козырьки» из книги материалов и ФОТ, а не одноимённый лист из
    книги заявок: только там есть время выполнения в чел·ч и исполнитель, без
    которых очистка козырька (US-H2) переносится без своей единицы учёта.
    """
    path = os.path.join(DATA, "materialy_i_fot.xlsx")
    wb = load_workbook(path, read_only=True, data_only=True)
    if "Козырьки" not in wb.sheetnames:
        wb.close()
        return
    ws = wb["Козырьки"]
    for i, row in enumerate(ws.iter_rows(min_row=6, max_col=20, values_only=True), 6):
        if not text(row[2]):
            continue
        yield "KZ", "Козырьки", i, tuple(row)
    wb.close()


# ---------------------------------------------------------------------------
# Материалы, ФОТ, компенсации
# ---------------------------------------------------------------------------

def load_materials_and_payroll(out_mat, out_pay):
    wb = load_workbook(os.path.join(DATA, "materialy_i_fot.xlsx"), read_only=True, data_only=True)

    materials = {}
    suppliers = set()
    movements = []
    for sheet in [n for n in wb.sheetnames if n.startswith("Материалы ")]:
        ws = wb[sheet]
        for i, row in enumerate(ws.iter_rows(min_row=2, max_col=11, values_only=True), 2):
            name = text(row[3])
            qty = num(row[4])
            if not name or not qty:
                continue
            amount = num(row[6])
            supplier = norm_name(row[7])
            project_raw = (text(row[8]) or "").lower()
            project = PROJECT_MAP.get(project_raw, (None,))[0]
            if project_raw and not project:
                issue("PROJECT_UNKNOWN", "WARNING",
                      f"Материалы, лист «{sheet}»: неизвестный проект «{text(row[8])}»")
            materials.setdefault(name, unit(row[5]))
            if supplier:
                suppliers.add(supplier)
            movements.append({
                "sheet": sheet, "row": i, "name": name, "qty": qty,
                "unit": unit(row[5]), "amount": amount, "supplier": supplier,
                "project": project, "date": date(row[2]) or date(row[1]),
                "order": text(row[1]), "comment": text(row[9]),
            })

    out_mat.comment("Номенклатура материалов")
    for name, u in sorted(materials.items()):
        out_mat.add(
            "INSERT INTO materials (name, unit_code, note) VALUES "
            f"({q(name)}, {q(u)}, 'Заведён при миграции из листов «Материалы»')"
        )
    note("Материалов заведено", len(materials))

    out_mat.comment("Поставщики")
    for s in sorted(suppliers):
        out_mat.add(f"INSERT INTO suppliers (name, note) VALUES ({q(s)}, 'Из листов «Материалы»')")
    note("Поставщиков заведено", len(suppliers))

    out_mat.comment("Движения материалов: поступление и списание «с колёс»")
    total_amount = 0.0
    for m in movements:
        price = round(m["amount"] / m["qty"], 2) if (m["amount"] and m["qty"]) else None
        total_amount += m["amount"] or 0
        moved = m["date"] or dt.date(2026, 1, 1)
        supplier_ref = (f"(SELECT id FROM suppliers WHERE name = {q(m['supplier'])})"
                        if m["supplier"] else "NULL")
        project_ref = (f"(SELECT id FROM projects WHERE code = {q(m['project'])})"
                       if m["project"] else "NULL")
        out_mat.add(
            "WITH rec AS (INSERT INTO stock_movements (movement_type, material_id, qty, unit_code, price, "
            "amount, moved_at, supplier_id, project_id, on_wheels, comment, created_by) "
            f"SELECT 'RECEIPT', m.id, {q(m['qty'])}, {q(m['unit'])}, {q(price)}, {q(m['amount'])}, "
            f"{q(datetime_(moved))}, {supplier_ref}, {project_ref}, TRUE, "
            f"{q('Заказ ' + (m['order'] or '') + ('; ' + m['comment'] if m['comment'] else ''))}, 'migration' "
            f"FROM materials m WHERE m.name = {q(m['name'])} RETURNING id), "
            "iss AS (INSERT INTO stock_movements (movement_type, material_id, qty, unit_code, price, amount, "
            "moved_at, project_id, on_wheels, linked_movement_id, comment, created_by) "
            f"SELECT 'ISSUE', m.id, {q(m['qty'])}, {q(m['unit'])}, {q(price)}, {q(m['amount'])}, "
            f"{q(datetime_(moved))}, {project_ref}, TRUE, rec.id, "
            "'Списание «с колёс» при миграции', 'migration' "
            f"FROM materials m, rec WHERE m.name = {q(m['name'])} RETURNING id) "
            "INSERT INTO legacy_map (entity_type, entity_id, source_book, source_sheet, source_row, source_key) "
            f"SELECT 'MATERIAL_MOVE', rec.id, {q(BOOK_MATERIALS)}, {q(m['sheet'])}, {q(m['row'])}, "
            f"{q(m['sheet'] + '#' + str(m['row']))} FROM rec"
        )
    note("Движений материалов", len(movements))
    note("Сумма материалов, ₽", round(total_amount, 2))

    out_mat.comment("История цен на материалы")
    price_rows = 0
    if "Цена на материалы" in wb.sheetnames:
        ws = wb["Цена на материалы"]
        rows = list(ws.iter_rows(min_row=2, max_col=23, values_only=True))
        for block in (0, 6, 12, 18):
            for row in rows:
                if len(row) <= block + 3:
                    continue
                name = text(row[block + 2])
                price = num(row[block + 3])
                d = date(row[block + 1])
                if not name or not price or not d:
                    continue
                out_mat.add(
                    "INSERT INTO material_prices (material_id, priced_on, price, price_per_m2) "
                    f"SELECT id, {q(d)}, {q(price)}, {q(num(row[block + 4]) if len(row) > block + 4 else None)} "
                    f"FROM materials WHERE name = {q(name)}"
                )
                price_rows += 1
    note("Записей истории цен", price_rows)

    # ---- ФОТ --------------------------------------------------------------
    out_pay.comment("Начисления ФОТ из месячных листов")
    payroll_rows = 0
    payroll_total = 0.0
    for sheet in [n for n in wb.sheetnames if n.startswith("ФОТ ")]:
        ws = wb[sheet]
        for i, row in enumerate(ws.iter_rows(min_row=2, max_col=11, values_only=True), 2):
            employee = norm_name(row[8])
            amount = num(row[7])
            if not employee or amount is None:
                continue
            if any(ch.isdigit() for ch in employee) or "," in employee:
                continue
            project_raw = (text(row[2]) or "").lower()
            project = PROJECT_MAP.get(project_raw, (None,))[0]
            if not project:
                issue("PROJECT_UNKNOWN", "WARNING",
                      f"ФОТ, лист «{sheet}»: неизвестный проект «{text(row[2])}» — строка не перенесена")
                continue
            d = date(row[1]) or dt.date(2026, 1, 1)
            qty = num(row[5])
            u = unit(row[6], "SHIFT")
            coef = num(row[4]) or 1
            work = text(row[3])
            payroll_total += amount
            out_pay.add(
                "WITH pe AS (INSERT INTO payroll_entries (employee_id, project_id, basis_type, period_month, "
                "entry_date, qty, unit_code, coefficient, formula, amount, status, is_manual, reason, created_by) "
                f"SELECT e.id, p.id, 'MANUAL', {q(month_start(d))}, {q(d)}, {q(qty)}, {q(u)}, {q(coef)}, "
                f"{q('Перенос из листа «' + sheet + '»' + (': ' + work if work else ''))}, {q(amount)}, "
                f"'CLOSED', TRUE, 'Миграция исторического начисления', 'migration' "
                f"FROM employees e, projects p WHERE e.full_name = {q(employee)} AND p.code = {q(project)} "
                "RETURNING id) "
                "INSERT INTO legacy_map (entity_type, entity_id, source_book, source_sheet, source_row, source_key) "
                f"SELECT 'PAYROLL', pe.id, {q(BOOK_MATERIALS)}, {q(sheet)}, {q(i)}, "
                f"{q(sheet + '#' + str(i))} FROM pe"
            )
            payroll_rows += 1
    note("Начислений ФОТ перенесено", payroll_rows)
    note("Сумма ФОТ, ₽", round(payroll_total, 2))

    # ---- Компенсации -------------------------------------------------------
    comp_status = {
        "чек на руках": "CHECK_ON_HANDS",
        "чек передан в офис": "PASSED_TO_OFFICE",
        "чек передан бухг.": "PASSED_TO_ACCOUNTANT",
        "чек передан бухгалтеру": "PASSED_TO_ACCOUNTANT",
        "компенсировано": "COMPENSATED",
    }
    out_pay.comment("Компенсации сотрудникам")
    comp_rows = 0
    comp_total = 0.0
    if "Компенсации" in wb.sheetnames:
        ws = wb["Компенсации"]
        for i, row in enumerate(ws.iter_rows(min_row=2, max_col=7, values_only=True), 2):
            employee = norm_name(row[2])
            amount = num(row[5])
            if not employee or amount is None:
                continue
            project_raw = (text(row[3]) or "").lower()
            project = PROJECT_MAP.get(project_raw, (None,))[0]
            d = date(row[1]) or dt.date(2026, 1, 1)
            status = comp_status.get((text(row[6]) or "").lower().strip(), "CHECK_ON_HANDS")
            comp_total += amount
            project_ref = (f"(SELECT id FROM projects WHERE code = {q(project)})" if project else "NULL")
            out_pay.add(
                "INSERT INTO compensations (employee_id, project_id, spent_on, amount, title, status, "
                "comment, created_by) "
                f"SELECT e.id, {project_ref}, {q(d)}, {q(amount)}, {q(text(row[4]) or 'Компенсация')}, "
                f"{q(status)}, 'Перенос из листа «Компенсации»', 'migration' "
                f"FROM employees e WHERE e.full_name = {q(employee)}"
            )
            comp_rows += 1
    note("Компенсаций перенесено", comp_rows)
    note("Сумма компенсаций, ₽", round(comp_total, 2))

    # ---- РК-3 --------------------------------------------------------------
    rk3_rows = load_rk3(out_pay, wb)
    note("Объектов РК-3 перенесено", rk3_rows)

    wb.close()


def load_rk3(out, wb):
    """Объекты РК-3. Формулы ФОТ не переносятся — переносится только факт."""
    if "РК3" not in wb.sheetnames:
        return 0
    out.comment("Объекты РК-3: факт вырубки и монтажа без legacy-формул выплаты")
    ws = wb["РК3"]
    count = 0
    for i, row in enumerate(ws.iter_rows(min_row=6, max_col=46, values_only=True), 6):
        act = text(row[0])
        address = text(row[2])
        if not act or not address:
            continue
        year = 2025
        if "_" in act:
            parts = act.split("_")
            if len(parts) > 1 and parts[1].isdigit():
                year = int(parts[1])
        key = address_key(address)
        building_ref = f"(SELECT id FROM buildings WHERE address_key = {q(key)} ORDER BY id LIMIT 1)"
        out.add(
            "WITH o AS (INSERT INTO rk3_objects (act_number, project_id, building_id, seq_in_year, "
            "season_year, defect_date, shipment_date, supplier_order_number, delivery_cost, unload_cost, "
            "lift_method, lift_cost, waste_cost, shipment_total, crew_roofers, crew_demolition, "
            "accepted_at, accepted_demolition_m2, fact_demolition_man_hours, customer_rep, contractor_rep, "
            "status, note, created_by) "
            f"SELECT {q(act)}, p.id, {building_ref}, {q(intval(row[1]))}, {q(year)}, "
            f"{q(date(row[3]))}, {q(date(row[9]))}, {q(text(row[22]))}, {q(num(row[18]))}, {q(num(row[19]))}, "
            f"{q(text(row[23]))}, {q(num(row[24]))}, {q(num(row[25]))}, {q(num(row[21]))}, "
            f"{q(text(row[26]))}, {q(text(row[27]))}, {q(date(row[28]))}, {q(num(row[29]))}, {q(num(row[30]))}, "
            f"{q(text(row[43]))}, {q(text(row[44]))}, 'CLOSED', "
            "'Перенос из листа «РК3». Коэффициент и выплата вырубки не переносились: "
            "в исходной таблице выплата считалась по p вместо k (P0 бизнес-ТЗ)', 'migration' "
            "FROM projects p WHERE p.code = 'RK3' RETURNING id) "
            "INSERT INTO legacy_map (entity_type, entity_id, source_book, source_sheet, source_row, source_key) "
            f"SELECT 'RK3_OBJECT', o.id, {q(BOOK_MATERIALS)}, 'РК3', {q(i)}, {q(act)} FROM o"
        )

        # Строки монтажа по типам материала: колонки 31..34 — принятые м².
        mount_cols = {31: "Линокром ТКП", 32: "Унифлекс ЭКП", 33: "Линокром ХПП", 34: "Унифлекс ХПП"}
        order = 10
        for col, layer in mount_cols.items():
            qty = num(row[col]) if col < len(row) else None
            if not qty:
                continue
            out.add(
                "INSERT INTO rk3_object_lines (rk3_object_id, line_kind, layer_name, accepted_qty, "
                "unit_code, sort_order) "
                f"SELECT id, 'MOUNT', {q(layer)}, {q(qty)}, 'M2', {q(order)} "
                f"FROM rk3_objects WHERE act_number = {q(act)}"
            )
            order += 10
        demolition = num(row[29])
        if demolition:
            out.add(
                "INSERT INTO rk3_object_lines (rk3_object_id, line_kind, layer_name, accepted_qty, "
                "unit_code, sort_order) "
                f"SELECT id, 'DEMOLITION', 'Вырубка', {q(demolition)}, 'M2', 5 "
                f"FROM rk3_objects WHERE act_number = {q(act)}"
            )
        count += 1
    return count


# ---------------------------------------------------------------------------
# Кассы
# ---------------------------------------------------------------------------

def circuit_for(account_name):
    n = (account_name or "").lower()
    if n.startswith("диполь") or "контора диполь" in n:
        return "DIPOL"
    if n.startswith("ковры") or "контора ковры" in n:
        return "CARPETS"
    if "личн" in n:
        return "PERSONAL"
    return "OTHER"


def load_cash(out):
    path = os.path.join(DATA, "kassy.xlsx")
    if not os.path.exists(path):
        note("Кассовых операций перенесено", 0)
        issue("CASH_MISSING", "WARNING", "Книга «Кассы» не выгружена — финансовый контур не перенесён")
        return
    wb = load_workbook(path, read_only=True, data_only=True)

    accounts = {}
    categories = {}
    counterparties = set()
    transactions = []

    for sheet in ["Касса", "Касса Архив"]:
        if sheet not in wb.sheetnames:
            continue
        ws = wb[sheet]
        # Заголовок журнала — строка 13, над ней блок остатков.
        for i, row in enumerate(ws.iter_rows(min_row=14, max_col=13, values_only=True), 14):
            legacy_id = text(row[0])
            amount = num(row[6])
            if not legacy_id or amount is None:
                continue
            src = text(row[4])
            dst = text(row[5])
            for acc in (src, dst):
                if acc:
                    accounts.setdefault(acc, circuit_for(acc))
            cat = text(row[7])
            if cat:
                categories.setdefault(cat, None)
            cp = norm_name(row[8])
            if cp:
                counterparties.add(cp)
            transactions.append({
                "sheet": sheet, "row": i, "legacy_id": legacy_id,
                "date": date(row[1]), "kind": text(row[3]), "src": src, "dst": dst,
                "amount": amount, "category": cat, "counterparty": cp,
                "comment": text(row[10]), "period_from": date(row[11]), "period_to": date(row[12]),
                "circuit": circuit_for(src) if circuit_for(src) != "OTHER" else circuit_for(dst),
            })

    out.comment("Денежные счета и технические статьи «откуда/куда»")
    # Реальные кассы отличаются от технических точек («Корректировка», «Рабочий»)
    # наличием контура: в балансе участвуют только реальные счета.
    for name, circuit in sorted(accounts.items()):
        is_real = circuit in ("DIPOL", "CARPETS", "PERSONAL") and "—" in name
        out.add(
            "INSERT INTO cash_accounts (name, account_type, circuit_code, is_real, sort_order) VALUES ("
            f"{q(name)}, "
            + ("'CARD'" if "карт" in name.lower() else "'CASH'")
            + f", {q(circuit)}, {q(is_real)}, 100)"
        )
    note("Денежных счетов и точек", len(accounts))

    out.comment("Статьи кассы, которых не было в стартовом справочнике")
    for cat in sorted(categories):
        code = "LEGACY_" + str(abs(hash(cat)) % 100000)
        categories[cat] = code
        out.add(
            "INSERT INTO cash_categories (code, name, direction, sort_order) VALUES "
            f"({q(code)}, {q(cat)}, 'BOTH', 500) ON CONFLICT (code) DO NOTHING"
        )

    out.comment("Контрагенты")
    for cp in sorted(counterparties):
        out.add(
            "INSERT INTO counterparties (name, kind) VALUES "
            f"({q(cp)}, 'OTHER') ON CONFLICT DO NOTHING"
        )
    note("Контрагентов кассы", len(counterparties))

    out.comment("Кассовые операции: неизменяемый UUID вместо строкового ID из таблицы")
    total_in = total_out = 0.0
    for t in transactions:
        kind = (t["kind"] or "").lower()
        if "приход" in kind:
            direction = "INCOME"
            total_in += t["amount"]
        elif "расход" in kind:
            direction = "EXPENSE"
            total_out += t["amount"]
        elif "перемещ" in kind:
            direction = "TRANSFER"
        else:
            direction = "INCOME"
            total_in += t["amount"]
        out.add(
            "WITH tx AS (INSERT INTO cash_transactions (uuid, legacy_id, occurred_on, direction, "
            "from_account_id, to_account_id, amount, category_code, counterparty_id, circuit_code, "
            "period_from, period_to, comment, created_by) VALUES ("
            f"gen_random_uuid(), {q(t['legacy_id'])}, {q(t['date'] or dt.date(2026, 1, 1))}, {q(direction)}, "
            f"(SELECT id FROM cash_accounts WHERE name = {q(t['src'])}), "
            f"(SELECT id FROM cash_accounts WHERE name = {q(t['dst'])}), "
            f"{q(t['amount'])}, {q(categories.get(t['category']))}, "
            f"(SELECT id FROM counterparties WHERE name = {q(t['counterparty'])} LIMIT 1), "
            f"{q(t['circuit'])}, {q(t['period_from'])}, {q(t['period_to'])}, "
            f"{q(t['comment'])}, 'migration') RETURNING id) "
            "INSERT INTO legacy_map (entity_type, entity_id, source_book, source_sheet, source_row, source_key) "
            f"SELECT 'CASH_TX', tx.id, {q(BOOK_CASH)}, {q(t['sheet'])}, {q(t['row'])}, {q(t['legacy_id'])} "
            "FROM tx ON CONFLICT (source_book, source_sheet, source_key) DO NOTHING"
        )
    note("Кассовых операций перенесено", len(transactions))
    note("Приходов, ₽", round(total_in, 2))
    note("Расходов, ₽", round(total_out, 2))
    issue("CASH_SCOPE", "WARNING",
          "Книга «Кассы» содержит операции «Диполя», «Ковров» и личные — граница контуров "
          "требует решения владельца процесса (вопрос Q-08)")
    wb.close()


# ---------------------------------------------------------------------------
# Протокол сверки
# ---------------------------------------------------------------------------

def write_report(out):
    out.comment("Протокол контрольной сверки миграции")
    for metric, value in report.items():
        out.add(
            "INSERT INTO import_reconciliation (source_book, source_sheet, metric, source_value, note) VALUES "
            f"('ВСЕ', '—', {q(metric)}, "
            + (q(float(value)) if isinstance(value, (int, float)) else "NULL")
            + f", {q(str(value))})"
        )
    out.comment("Вопросы и неполнота данных, найденные при переносе")
    for kind, severity, message in issues:
        out.add(
            "INSERT INTO data_issues (issue_type, severity, message, responsible) VALUES "
            f"({q(kind)}, {q(severity)}, {q(message)}, 'migration')"
        )


def main():
    os.makedirs(OUT, exist_ok=True)

    master = Out("01_master.sql", "Мастер-данные: ЖЭУ, дома, клиенты, сотрудники, проекты")
    requests_out = Out("02_requests.sql", "Заявки и позиции работ")
    materials_out = Out("03_materials.sql", "Материалы, поставщики, движения и цены")
    payroll_out = Out("04_payroll.sql", "ФОТ, компенсации, объекты РК-3")
    cash_out = Out("05_cash.sql", "Управленческие кассы")
    report_out = Out("06_reconciliation.sql", "Протокол сверки и очередь качества данных")

    requests_out.add(
        "DO $$ BEGIN IF (SELECT COUNT(*) FROM requests) > 0 THEN "
        "RAISE EXCEPTION 'В базе уже есть заявки: миграция рассчитана на чистую базу. "
        "Сначала выполните tools/migration/reset.sql'; END IF; END $$"
    )

    buildings, by_key = load_master(master)
    employees = collect_employees()
    load_clients_projects(master, employees)
    load_requests(requests_out, buildings, by_key)
    load_materials_and_payroll(materials_out, payroll_out)
    load_cash(cash_out)
    write_report(report_out)

    paths = [o.write() for o in
             (master, requests_out, materials_out, payroll_out, cash_out, report_out)]

    with open(os.path.join(OUT, "apply.sh"), "w", encoding="utf-8") as f:
        f.write("#!/usr/bin/env bash\n")
        f.write("# Применение миграции. Порядок файлов важен: сначала мастер-данные.\n")
        f.write("set -e\n")
        f.write('DB="${1:-dipol_db}"\n')
        f.write('USER_="${2:-dipol}"\n')
        f.write('DIR="$(cd "$(dirname "$0")" && pwd)"\n')
        for p in paths:
            f.write(f'psql -v ON_ERROR_STOP=1 -U "$USER_" -h localhost -d "$DB" '
                    f'-f "$DIR/{os.path.basename(p)}"\n')
        f.write('echo "Миграция применена"\n')
    os.chmod(os.path.join(OUT, "apply.sh"), 0o755)

    print("Сгенерировано:")
    for p in paths:
        print("  ", os.path.relpath(p, BASE))
    print("\nСводка переноса:")
    for metric, value in report.items():
        print(f"  {metric}: {value}")
    if issues:
        print("\nЗамечания:")
        for kind, severity, message in issues[:20]:
            print(f"  [{severity}] {kind}: {message}")
        if len(issues) > 20:
            print(f"  … и ещё {len(issues) - 20}")


if __name__ == "__main__":
    main()
