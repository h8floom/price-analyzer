package ru.priceanalyzer.api;

import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.priceanalyzer.service.PriceReports;
import ru.priceanalyzer.service.ReportFiles;

@RestController
@RequestMapping("/api")
public class ReportController {
    private final PriceReports reports;

    ReportController(PriceReports reports) {
        this.reports = reports;
    }

    @GetMapping("/reports")
    public List<PriceReports.Row> report(@ModelAttribute PriceReports.Filter filter) {
        return reports.calculate(filter);
    }

    @GetMapping("/reports.xlsx")
    public ResponseEntity<byte[]> workbook(@ModelAttribute PriceReports.Filter filter) {
        try {
            return download("price-report.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ReportFiles.xlsx(reports.calculate(filter)));
        } catch (IOException failure) {
            throw new ApiProblem(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось сформировать XLSX");
        }
    }

    @GetMapping("/reports.pdf")
    public ResponseEntity<byte[]> pdf(@ModelAttribute PriceReports.Filter filter) {
        try {
            return download("price-report.pdf", "application/pdf", ReportFiles.pdf(reports.calculate(filter)));
        } catch (IOException failure) {
            throw new ApiProblem(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось сформировать PDF");
        }
    }

    private ResponseEntity<byte[]> download(String file, String type, byte[] body) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + file).body(body);
    }
}
