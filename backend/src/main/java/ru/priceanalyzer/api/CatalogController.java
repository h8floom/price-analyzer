package ru.priceanalyzer.api;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.priceanalyzer.domain.PriceEntry;
import ru.priceanalyzer.domain.Product;
import ru.priceanalyzer.service.CatalogService;

@RestController
@RequestMapping("/api")
public class CatalogController {
    private static final Logger log = LoggerFactory.getLogger(CatalogController.class);
    private final CatalogService service;
    public CatalogController(CatalogService service) {this.service=service;}

    public record ProductInput(String name, String category, String unit, String description) {}
    public record PriceInput(Long productId, String store, BigDecimal price, LocalDate date) {}
    public record PriceUpdate(BigDecimal price) {}

    @GetMapping("/health")
    public Map<String,String> health(){return Map.of("status","ok","service","price-analyzer");}
    @GetMapping("/products")
    public List<Product> products(){return service.products();}
    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public Product createProduct(@RequestBody ProductInput p){
        return service.createProduct(p.name(),p.category(),p.unit(),p.description());
    }
    @PutMapping("/products/{id}")
    public Product updateProduct(@PathVariable long id,@RequestBody ProductInput p){
        return service.updateProduct(id,p.name(),p.category(),p.unit(),p.description());
    }
    @DeleteMapping("/products/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProduct(@PathVariable long id){service.deleteProduct(id);}

    @GetMapping("/prices")
    public List<PriceEntry> prices(@RequestParam(required=false) Long productId,
         @RequestParam(required=false) LocalDate from,
         @RequestParam(required=false) LocalDate to){
        return service.prices(productId,from,to);
    }
    @PostMapping("/prices")
    @ResponseStatus(HttpStatus.CREATED)
    public PriceEntry addPrice(@RequestBody PriceInput input){
        if(input.productId()==null) throw new ApiProblem(HttpStatus.BAD_REQUEST,"Не указан товар");
        return service.addPrice(input.productId(),input.store(),input.price(),input.date());
    }
    @PutMapping("/prices/{id}")
    public PriceEntry updatePrice(@PathVariable long id, @RequestBody PriceUpdate input) {
        return service.updatePrice(id, input.price());
    }
    @DeleteMapping("/prices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePrice(@PathVariable long id){service.deletePrice(id);}
    @GetMapping("/analysis/{productId}")
    public Map<String,Object> analysis(@PathVariable long productId,
          @RequestParam(required=false) LocalDate from,
          @RequestParam(required=false) LocalDate to){
        return service.analysis(productId,from,to);
    }
    @GetMapping(value="/export/prices.csv",produces="text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> csv(@RequestParam(required=false) Long productId,
                                      @RequestParam(defaultValue="standard") String format){
        try {
            if (!format.equals("standard") && !format.equals("excel")) {
                throw new ApiProblem(HttpStatus.BAD_REQUEST, "Неизвестный формат CSV");
            }
            boolean excel = format.equals("excel");
            if (productId != null) service.product(productId);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + (excel ? "prices-excel.csv" : "prices.csv"))
                .body(service.exportCsv(productId, excel).getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException failure) {
            log.warn("CSV price export failed, productId={}, error={}", productId, failure.getClass().getSimpleName());
            if (failure instanceof ApiProblem problem) throw problem;
            throw new ApiProblem(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось выгрузить цены");
        }
    }
    @PostMapping(value="/import/prices",consumes={"text/csv","text/plain"})
    public Map<String,Integer> importCsv(@RequestBody String body){
        return service.importCsv(body);
    }

    @PostMapping(value="/import/prices.xlsx", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Integer> importXlsx(@RequestParam MultipartFile file) throws IOException {
        try (var stream = file.getInputStream()) {
            return service.importXlsx(stream);
        }
    }
}
