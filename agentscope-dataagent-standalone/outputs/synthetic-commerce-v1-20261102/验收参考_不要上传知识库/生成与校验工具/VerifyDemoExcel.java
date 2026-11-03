import io.agentscope.dataagent.dataset.parser.ExcelParser;
import io.agentscope.dataagent.dataset.parser.ParsedTable;
import java.nio.file.*;
import java.math.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

public class VerifyDemoExcel {
    static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }
    static ParsedTable parse(Path file) throws Exception {
        try (var in = Files.newInputStream(file)) {
            return new ExcelParser().parse(in, file.getFileName().toString(), 500000);
        }
    }
    static long cents(String value) {
        return new BigDecimal(value).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
    public static void main(String[] args) throws Exception {
        Path base = Path.of(args[0]);
        var orders = parse(base.resolve("演示订单.xlsx"));
        var customers = parse(base.resolve("演示客户.xlsx"));
        require(orders.totalRows()==48000 && orders.rows().size()==48000 && !orders.truncated(), "order count");
        require(customers.totalRows()==2000 && customers.rows().size()==2000 && !customers.truncated(), "customer count");
        require(orders.columns().size()==19 && customers.columns().size()==6,"columns");
        require(orders.columns().get(1).name().equals(customers.columns().get(0).name()),"join column name");
        require(orders.columns().get(2).sqlType().equals("DATETIME"),"order timestamp inference");
        Set<String> ids = new HashSet<>(), keys = new HashSet<>(), active = new HashSet<>(), days = new HashSet<>();
        long net=0,profit=0,paid=0,refund=0;
        for(var row:customers.rows()) require(keys.add(row.get(0)),"duplicate customer");
        for(var row:orders.rows()) {
            require(ids.add(row.get(0)),"duplicate order");
            require(keys.contains(row.get(1)),"orphan customer");
            active.add(row.get(1)); days.add(row.get(2).substring(0,10));
            long p=cents(row.get(13)),r=cents(row.get(14)),c=cents(row.get(15)),n=cents(row.get(16)),g=cents(row.get(17));
            require(p-r==n && n-c==g,"money identity");
            net+=n;profit+=g;paid+=p;refund+=r;
        }
        require(active.size()==1800 && days.size()==184,"coverage");
        require(net==964081045L && profit==409928845L && paid==1022143765L && refund==58062720L,"source control totals");
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("parser","Actual project ExcelParser using Apache POI and TypeInferrer, no application server/database");
        result.put("passed",true);result.put("orderRows",orders.totalRows());result.put("customerRows",customers.totalRows());
        result.put("joinKey",orders.columns().get(1).name());result.put("orderTimeType",orders.columns().get(2).sqlType());
        result.put("orderColumns",orders.columns().stream().map(c->Map.of("name",c.name(),"type",c.sqlType())).toList());
        result.put("netRevenueCents",net);result.put("grossProfitCents",profit);result.put("orphans",0);result.put("days",184);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(base.resolve("验收参考_不要上传知识库/实际Excel解析校验.json").toFile(),result);
        System.out.println("Actual ExcelParser passed: 48000 + 2000 rows, 184 days, typed dates, intact keys and exact monetary totals");
    }
}
