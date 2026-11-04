"""Общие утилиты миграции legacy-таблиц «Диполь» в схему ERP.

Ключ адреса строится ТЕМ ЖЕ алгоритмом, что и в AddressService на бэкенде:
если алгоритмы разойдутся, импортированные дома перестанут находиться при
последующих загрузках из системы заказчика.
"""

import datetime as dt
import re
import unicodedata

# Типы населённых пунктов и улиц: на идентификацию дома не влияют.
TYPE_WORDS = {
    "гп", "рп", "пгт", "тер", "снт", "днп",
    "ул", "улица", "пр", "пркт", "проспект", "пер", "переулок",
    "ш", "шоссе", "наб", "набережная", "б", "бул", "бульвар",
    "пл", "площадь", "проезд", "линия", "туп", "тупик",
    "д", "дом", "к", "корп", "корпус", "стр", "строение", "лит", "литера",
}

NON_SIGNIFICANT = re.compile(r"[^a-zа-я0-9/\s]")
SPACES = re.compile(r"\s+")

# Значения, которыми в таблицах обозначено отсутствие данных.
EMPTY_MARKERS = {
    "", "-", "—", "–", "б/н", "нет", "н/д", "данные не найдены",
    "#n/a", "#ref!", "#value!", "#div/0!", "none", "null",
}


def address_key(raw):
    """Нормализованный ключ адреса: без типов объектов, слова отсортированы."""
    if not raw:
        return ""
    s = str(raw).lower().replace("ё", "е")
    s = unicodedata.normalize("NFKC", s)
    s = s.replace(".", " ").replace(",", " ").replace("-", " ")
    s = NON_SIGNIFICANT.sub(" ", s)
    s = SPACES.sub(" ", s).strip()
    words = [w for w in s.split(" ") if w and w not in TYPE_WORDS]
    return " ".join(sorted(words))


def display_address(raw):
    if raw is None:
        return None
    s = str(raw).strip()
    s = re.sub(r"\s*,\s*", ", ", s)
    s = SPACES.sub(" ", s)
    return re.sub(r"(,\s*)+$", "", s)


def clean(value):
    """Значение ячейки или None, если это пусто либо маркер отсутствия данных."""
    if value is None:
        return None
    if isinstance(value, str):
        s = value.strip()
        if s.lower() in EMPTY_MARKERS:
            return None
        return s
    return value


def text(value, limit=None):
    v = clean(value)
    if v is None:
        return None
    s = str(v).strip()
    if not s:
        return None
    return s[:limit] if limit else s


def num(value):
    """Число из ячейки. Строки с запятой и пробелами тоже разбираются."""
    v = clean(value)
    if v is None:
        return None
    if isinstance(v, (int, float)):
        return float(v)
    s = str(v).replace(" ", "").replace(" ", "").replace(",", ".")
    s = re.sub(r"[^0-9.\-]", "", s)
    if s in ("", "-", "."):
        return None
    try:
        return float(s)
    except ValueError:
        return None


def intval(value):
    n = num(value)
    return int(round(n)) if n is not None else None


def date(value):
    """Дата из ячейки: datetime, date или строка вида дд.мм.гггг."""
    v = clean(value)
    if v is None:
        return None
    if isinstance(v, dt.datetime):
        return v.date()
    if isinstance(v, dt.date):
        return v
    s = str(v).strip()
    for fmt in ("%Y-%m-%d", "%d.%m.%Y", "%d.%m.%y", "%Y-%m-%d %H:%M:%S"):
        try:
            return dt.datetime.strptime(s[:19], fmt).date()
        except ValueError:
            continue
    m = re.match(r"(\d{2})\.(\d{2})\.(\d{4})", s)
    if m:
        return dt.date(int(m.group(3)), int(m.group(2)), int(m.group(1)))
    return None


def datetime_(value):
    v = clean(value)
    if isinstance(v, dt.datetime):
        return v
    d = date(v)
    return dt.datetime.combine(d, dt.time()) if d else None


def q(value):
    """SQL-литерал. Экранирует кавычки; None превращается в NULL."""
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, (int,)):
        return str(value)
    if isinstance(value, float):
        if value != value:  # NaN
            return "NULL"
        return repr(round(value, 4))
    if isinstance(value, dt.datetime):
        return "'" + value.strftime("%Y-%m-%d %H:%M:%S") + "'"
    if isinstance(value, dt.date):
        return "'" + value.isoformat() + "'"
    s = str(value).replace("'", "''")
    return "'" + s + "'"


# Единицы измерения legacy → коды ERP.
UNIT_MAP = {
    "м2": "M2", "м²": "M2", "m2": "M2", "кв.м": "M2", "кв м": "M2",
    "м3": "M3", "м³": "M3",
    "м.пог.": "MP", "м.пог": "MP", "мпог": "MP", "м/п": "MP", "пог.м": "MP",
    "шт": "PCS", "шт.": "PCS", "штук": "PCS",
    "см.": "SHIFT", "см": "SHIFT", "смена": "SHIFT",
    "ч*час": "MH", "чел*час": "MH", "чел·ч": "MH", "ч/час": "MH", "чел.час": "MH",
    "час": "HOUR", "ч": "HOUR",
    "км": "KM", "рулон": "ROLL", "кг": "KG", "л": "L",
    "к-т": "SET", "компл": "SET", "компл.": "SET",
    "уп": "PCS", "уп.": "PCS", "пар": "PCS", "м": "MP",
}


def unit(value, default="PCS"):
    v = text(value)
    if not v:
        return default
    return UNIT_MAP.get(v.lower().strip(), default)


def month_start(d):
    return dt.date(d.year, d.month, 1) if d else None


def norm_name(value):
    """Имя сотрудника/контрагента: схлопнутые пробелы, без хвостовых точек."""
    s = text(value)
    if not s:
        return None
    s = SPACES.sub(" ", s).strip(" .")
    return s
