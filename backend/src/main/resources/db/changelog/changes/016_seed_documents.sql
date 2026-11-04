--liquibase formatted sql

-- ============================================================================
-- Стартовые шаблоны документов. Поля берутся по именованным переменным ERP,
-- а не по адресам ячеек — форма правится без участия разработчика (US-K1).
-- ============================================================================

--changeset dipol:016-templates splitStatements:false
INSERT INTO document_templates (code, name, doc_type, version, body, output_format, author) VALUES
('ACT', 'Акт выполненных работ', 'ACT', 1,
'<h2 style="text-align:center">АКТ № {{request.number}}</h2>
<p style="text-align:center">приёмки выполненных работ по текущему ремонту<br>
общего имущества в многоквартирном доме</p>
<p>Дата: {{document.date}}</p>
<p><b>Заказчик:</b> {{client.name}}<br>
<b>ЖЭУ:</b> {{zheu.name}}, начальник — {{zheu.head}}</p>
<p><b>Адрес:</b> {{request.address}}, кв. {{request.apartment}}, подъезд {{request.entrance}}</p>
<p><b>Заявка заказчика №</b> {{request.external_number}} от {{request.received_date}}</p>
<p><b>Выполненные работы:</b></p>
<pre>{{works.list}}</pre>
<p>Дата выполнения: {{request.done_date}}. Дата приёмки: {{request.accepted_date}}</p>
<p>Работы выполнены в полном объёме, претензий по качеству нет.</p>
<p>Представитель подрядчика ______________ / {{responsible.name}} /</p>
<p>Представитель заказчика ______________ /______________/</p>',
'HTML', 'migration'),

('MAX_MESSAGE', 'Сообщение в MAX о выполненной работе', 'MESSAGE', 1,
'✅ Отчёт о выполненной работе
Адрес: {{request.address}}{{request.apartment}}
ЖЭУ: {{zheu.name}}
Работы: {{works.inline}}
Дата выполнения: {{request.done_date}}
Заявка: {{request.external_number}}',
'TEXT', 'migration'),

('WORK_ORDER', 'Внутренний наряд на выполнение работ', 'WORK_ORDER', 1,
'<h3>Наряд на выполнение работ</h3>
<p>Дата: {{document.date}}</p>
<p>Объект: {{request.address}}, кв. {{request.apartment}}, подъезд {{request.entrance}}</p>
<p>Проект: {{project.name}}</p>
<p>Заявка: {{request.number}} (внешний № {{request.external_number}})</p>
<p>Описание: {{request.description}}</p>
<p><b>Работы:</b></p>
<pre>{{works.list}}</pre>
<p>Ответственный: {{responsible.name}}</p>
<p>Особые условия доступа: ______________________________________</p>',
'HTML', 'migration'),

('PHOTO_REPORT', 'Фотоотчёт', 'PHOTO_REPORT', 1,
'<h3>Фотоотчёт по заявке {{request.number}}</h3>
<p>Адрес: {{request.address}}, кв. {{request.apartment}}</p>
<p>Работы: {{works.inline}}</p>
<p>Дата выполнения: {{request.done_date}}</p>
<p><i>Фотографии «до» и «после» прикреплены к заявке в ERP.</i></p>',
'HTML', 'migration');
