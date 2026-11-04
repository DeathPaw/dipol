package ru.dipol.importer;

import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;
import ru.dipol.exception.BusinessRuleException;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Чтение xlsx-выгрузки в список строк «заголовок → значение».
 *
 * <p>Строка заголовков ищется, а не задаётся жёстко: в выгрузках заказчика
 * перед ней бывают служебные строки с итогами.
 */
@Component
public class ExcelReader {

    public record Sheet(String name, List<String> headers, List<Map<String, Object>> rows) {}

    public List<String> sheetNames(InputStream in) {
        try (Workbook wb = WorkbookFactory.create(in)) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) names.add(wb.getSheetName(i));
            return names;
        } catch (IOException e) {
            throw new BusinessRuleException("Не удалось прочитать файл: " + e.getMessage());
        }
    }

    public Sheet read(InputStream in, String sheetName, Integer headerRow, int maxRows) {
        try (Workbook wb = WorkbookFactory.create(in)) {
            org.apache.poi.ss.usermodel.Sheet sheet = sheetName != null
                    ? wb.getSheet(sheetName) : wb.getSheetAt(0);
            if (sheet == null) throw new BusinessRuleException("Лист не найден: " + sheetName);

            int headerIdx = headerRow != null ? headerRow - 1 : findHeaderRow(sheet);
            Row header = sheet.getRow(headerIdx);
            if (header == null) throw new BusinessRuleException("Не найдена строка заголовков");

            List<String> headers = new ArrayList<>();
            for (int c = 0; c < header.getLastCellNum(); c++) {
                String h = asString(header.getCell(c));
                headers.add(h == null || h.isBlank() ? "col" + (c + 1) : h.trim());
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            for (int r = headerIdx + 1; r <= sheet.getLastRowNum() && rows.size() < maxRows; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                Map<String, Object> values = new LinkedHashMap<>();
                boolean empty = true;
                for (int c = 0; c < headers.size(); c++) {
                    Object v = asObject(row.getCell(c));
                    if (v != null && !String.valueOf(v).isBlank()) empty = false;
                    values.put(headers.get(c), v);
                }
                if (empty) continue;
                values.put("_row", r + 1);
                rows.add(values);
            }
            return new Sheet(sheet.getSheetName(), headers, rows);
        } catch (IOException e) {
            throw new BusinessRuleException("Не удалось прочитать файл: " + e.getMessage());
        }
    }

    /** Заголовок — первая строка, где заполнено не меньше трёх ячеек текстом. */
    private int findHeaderRow(org.apache.poi.ss.usermodel.Sheet sheet) {
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 20); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            int textCells = 0;
            for (int c = 0; c < row.getLastCellNum(); c++) {
                String v = asString(row.getCell(c));
                if (v != null && !v.isBlank() && !v.matches("^[0-9.,\\s]+$")) textCells++;
            }
            if (textCells >= 3) return r;
        }
        return 0;
    }

    private static String asString(Cell cell) {
        Object v = asObject(cell);
        return v == null ? null : String.valueOf(v);
    }

    private static Object asObject(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    LocalDateTime dt = cell.getLocalDateTimeCellValue();
                    yield dt.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)
                            ? dt.toLocalDate() : dt;
                }
                double d = cell.getNumericCellValue();
                yield d == Math.floor(d) && !Double.isInfinite(d) ? (long) d : d;
            }
            case BOOLEAN -> cell.getBooleanCellValue();
            case FORMULA -> {
                try {
                    yield switch (cell.getCachedFormulaResultType()) {
                        case STRING -> cell.getStringCellValue().trim();
                        case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                                ? (Object) LocalDate.from(cell.getLocalDateTimeCellValue())
                                : cell.getNumericCellValue();
                        case BOOLEAN -> cell.getBooleanCellValue();
                        default -> null;
                    };
                } catch (Exception e) {
                    // Формулы с #REF!/#N/A в legacy-книгах — обычное дело,
                    // строка не должна из-за этого выпадать из импорта.
                    yield null;
                }
            }
            default -> null;
        };
    }
}
