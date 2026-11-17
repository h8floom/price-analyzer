package ru.priceanalyzer.config;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import ru.priceanalyzer.service.CatalogService;

@Component
public class DemoData implements CommandLineRunner {
    private final CatalogService service;
    @Value("${app.demo.seed:false}")
    private boolean seed;
    public DemoData(CatalogService service){this.service=service;}
    @Override
    public void run(String... args){
        if(!seed || !service.products().isEmpty()) return;
        long coffee=service.createProduct("Кофе молотый, 250 г","Напитки","упаковка","").id();
        long tea=service.createProduct("Чай чёрный, 100 пакетиков","Напитки","упаковка","").id();
        long chocolate=service.createProduct("Шоколад молочный, 90 г","Сладости","плитка","").id();
        long milk=service.createProduct("Молоко 2,5%, 1 л","Молочные продукты","литр","").id();
        String[] stores={"Магнит","Пятёрочка","Лента"};
        for(int week=0;week<4;week++){
            LocalDate day=LocalDate.of(2026,10,3).plusDays(7L*week);
            for(int i=0;i<stores.length;i++){
                service.addPrice(coffee,stores[i],
                    BigDecimal.valueOf(365L+ i*9L - week*6L+(week%2)*7L),day);
            }
        }
        service.addPrice(tea,"Магнит",new BigDecimal("195.00"),LocalDate.of(2026,10,17));
        service.addPrice(tea,"Лента",new BigDecimal("189.00"),LocalDate.of(2026,10,17));
        service.addPrice(chocolate,"Магнит",new BigDecimal("119.00"),LocalDate.of(2026,10,10));
        service.addPrice(milk,"Пятёрочка",new BigDecimal("92.00"),LocalDate.of(2026,10,17));
    }
}
