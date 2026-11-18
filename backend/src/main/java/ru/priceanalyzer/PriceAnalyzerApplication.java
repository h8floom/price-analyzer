package ru.priceanalyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import ru.priceanalyzer.auth.LocalAdmin;

@SpringBootApplication
@EnableScheduling
public class PriceAnalyzerApplication {
    public static void main(String[] args) {
        if (args.length == 2 && "--local-admin".equals(args[0])) {
            System.exit(LocalAdmin.run(args[1]));
            return;
        }
        SpringApplication.run(PriceAnalyzerApplication.class, args);
    }
}
