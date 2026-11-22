import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import ru.priceanalyzer.domain.PriceMath;
import ru.priceanalyzer.domain.PriceEntry;

public class PriceMathSmokeTest {
    private static PriceEntry price(long id,String store,String value,String date) {
        return new PriceEntry(id,1,store,new BigDecimal(value),LocalDate.parse(date));
    }
    private static void check(boolean condition, String message) {
        if(!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        var empty=PriceMath.summarize(List.of());
        check(empty.count()==0 && empty.min()==null && empty.average()==null,"Пустой список");
        var records=List.of(
            price(1,"A","100.00","2026-10-01"),
            price(2,"A","90.00","2026-10-08"),
            price(3,"B","110.00","2026-10-08"));
        var latest=PriceMath.latestByStore(records);
        check(latest.size()==2,"Количество магазинов");
        check(latest.get(0).price().compareTo(new BigDecimal("90.00"))==0,"Минимальная актуальная цена");
        check(latest.get(1).store().equals("B"),"Сортировка магазинов");
        var stats=PriceMath.summarize(latest);
        check(stats.min().compareTo(new BigDecimal("90.00"))==0,"Минимум");
        check(stats.max().compareTo(new BigDecimal("110.00"))==0,"Максимум");
        check(stats.average().compareTo(new BigDecimal("100.00"))==0,"Среднее");
        check(stats.count()==2,"Число записей");
        System.out.println("BACKEND OFFLINE: 8/8 checks passed");
    }
}
