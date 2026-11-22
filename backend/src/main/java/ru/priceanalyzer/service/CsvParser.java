package ru.priceanalyzer.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import ru.priceanalyzer.api.ApiProblem;

/** Минимальный парсер RFC 4180: поддерживает кавычки, запятые и переносы строк. */
public final class CsvParser {
    private CsvParser() {}
    public static List<List<String>> parse(String input) {
        List<List<String>> rows=new ArrayList<>();
        List<String> row=new ArrayList<>();
        StringBuilder field=new StringBuilder();
        boolean quoted=false;
        for (int i=0; i<input.length(); i++) {
            char ch=input.charAt(i);
            if (ch=='"') {
                if(quoted && i+1<input.length() && input.charAt(i+1)=='"') {
                    field.append('"'); i++;
                } else if(!quoted && field.length()>0) {
                    throw new ApiProblem(HttpStatus.BAD_REQUEST,"Некорректный CSV");
                } else {
                    quoted=!quoted;
                }
            } else if(ch==',' && !quoted) {
                row.add(field.toString()); field.setLength(0);
            } else if ((ch=='\n' || ch=='\r') && !quoted) {
                if(ch=='\r' && i+1<input.length() && input.charAt(i+1)=='\n') i++;
                row.add(field.toString()); field.setLength(0);
                rows.add(row); row=new ArrayList<>();
            } else {
                field.append(ch);
            }
        }
        if(quoted) throw new ApiProblem(HttpStatus.BAD_REQUEST,"Незакрытая кавычка в CSV");
        if(field.length()>0 || !row.isEmpty()) {
            row.add(field.toString()); rows.add(row);
        }
        return rows;
    }
}
