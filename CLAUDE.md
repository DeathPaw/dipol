# CLAUDE.md

Ориентиры для работы с этим репозиторием.

## Что это

ERP «Диполь» — заявки на кровельные работы, выезды, материалы, ФОТ, документы,
ТБ, транспорт и управленческие кассы. Заменяет учёт в Google Sheets.
Полное описание — `README.md`, требования — `docs/source/`.

## Команды

```bash
# Бэкенд (Java 21, Spring Boot 4.0.2, Maven)
cd backend && SPRING_DATASOURCE_PASSWORD=dipol mvn spring-boot:run   # :8080
cd backend && mvn -DskipTests compile
cd backend && mvn test -Dtest=ClassName#method

# Фронтенд (React 18, TypeScript, Vite)
cd frontend && npm run dev      # :3000, /api проксируется на :8080
cd frontend && npm run build    # tsc + сборка в dist/
cd frontend && npx tsc --noEmit # только проверка типов

# Перенос legacy-данных
python3 tools/migration/etl.py && bash docs/legacy-data/out/apply.sh
psql -U dipol -h localhost -d dipol_db -f tools/migration/reset.sql   # откат
```

## Архитектура

### backend/src/main/java/ru/dipol/

Слоистое приложение на чистом JDBC (без JPA):

- **controller/** — REST под `/api/`. Валидация через `@Valid` на record-DTO,
  объявленных внутри контроллера.
- **service/** — бизнес-правила: статусная машина заявки, расчёт ФОТ, склад,
  документы, отчёт «Мост», сопоставление адресов.
- **repository/** — `NamedParameterJdbcTemplate`, статические `RowMapper`,
  вставки через `GeneratedKeyHolder`. Списки «как есть» возвращаются
  через `queryForList` и типизируются на фронте как `Row`.
- **model/** — Java records.
- **exception/** — `EntityNotFoundException` (404), `BusinessRuleException` (422),
  `ConflictException` (409), `ForbiddenException` (403).

### backend/src/main/resources/db/changelog/

Liquibase, а не Flyway. Мастер-файл `db.changelog-master.xml`, модули в
`changes/NNN_*.sql` в формате `--liquibase formatted sql`.

**Применённые changeset'ы не редактируются.** Любое изменение схемы — новый файл
`changes/NNN_*.sql`, подключённый в конец мастер-файла.

### frontend/src/

- **components/** — общие: `DataTable`, `Modal`, `PageHead`, `StyledSelect`,
  `MultiSelectFilter`, `Tabs`, `StatusBadge`, `Field`, `Toast`.
- **pages/** — по разделу на файл, модалки объявляются в том же файле.
- **hooks/useApi.ts** — загрузка с состояниями loading/error/data.
- **index.css** — вся палитра и базовые элементы. Новых цветов и высот
  контролов в страницах не заводить: `--control-h`, `--font-sm`, `--c-*`.

## Соглашения

- Весь интерфейс и все сообщения об ошибках — **на русском**.
- Поля API — **snake_case** сквозь всю систему: БД → Jackson → JSON → TypeScript.
- Модели — **records** (неизменяемые).
- **Физического удаления нет** там, где данные попали в историю: дома и клиенты
  объединяются, движения склада сторнируются, кассовые операции сторнируются,
  работы деактивируются.
- **Нормативы — не константы в коде.** Любое число, влияющее на расчёт
  (150 м²/подъезд, лимит 10 м², ставки, лимит белой ЗП), живёт в
  `parameters` / `coefficient_ranges` с датой действия.
- **Разные физические единицы не складываются.** Агрегация допустима только
  внутри одного `units.kind`.
- Закрытый факт не пересчитывается задним числом: начисление хранит версию
  применённого условия оплаты, документ — версию шаблона, проект — версию
  конфигурации.

## Что важно не сломать

- **Ключ адреса.** `AddressService.key()` в Java и `address_key()` в
  `tools/migration/common.py` обязаны давать одинаковый результат: иначе
  импортированные дома перестанут находиться при следующей загрузке.
- **Расчёт вырубки РК-3.** Выплата = `часы × базовая ставка × k`. Использовать
  `p` вместо `k` нельзя — это перенесённая из legacy ошибка (P0 бизнес-ТЗ).
  Пока ставка не утверждена, расчёт обязан возвращать отказ, а не число.
- **Статусная схема** проверяется по конфигурации проекта, а не по константе
  в коде. Обход — только с правом `REQUEST_STATUS_OVERRIDE` и с причиной.

## Открытые вопросы

`QUESTIONS.md` — то, что нельзя решить без владельца процесса. Если упираетесь
в неизвестное бизнес-правило: не угадывайте, добавьте вопрос туда и оставьте
в коде явный отказ или предупреждение.
