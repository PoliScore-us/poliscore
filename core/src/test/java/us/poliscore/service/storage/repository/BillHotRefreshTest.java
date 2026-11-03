package us.poliscore.service.storage.repository;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import us.poliscore.model.bill.*;

class BillHotRefreshTest {
    @Test void updatesOnlyRankingAndIdWithoutSendingBillPayloads() {
        var bindings = new HashMap<Integer, Object>();
        var repo = new BillRepository() {
            @Override protected String qualifiedTableName() { return "test.bill"; }
            @Override protected void executeBatch(String sql, List<Bill> bills, SqlBinder<Bill> binder) {
                assertEquals("UPDATE test.bill SET hot_value = ? WHERE id = ?", sql);
                var statement = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { PreparedStatement.class }, (proxy, method, args) -> {
                        if (method.getName().startsWith("set")) bindings.put((Integer)args[0], args[1]);
                        return null;
                    });
                try { binder.bind(statement, bills.getFirst()); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
        };
        var bill = new Bill() {
            @Override public int getHot() { return 42; }
            @Override public BillInterpretation getInterpretation() { return new BillInterpretation(); }
        };
        bill.setId("BIL/us/congress/119/hr/1");
        bill.setStatus(new BillStatus("Law", 1f, "ENACTED:SIGNED"));
        bill.setLastActionDate(LocalDate.of(2026, 1, 1));
        repo.refreshHotValues(List.of(bill));
        assertEquals(42L, bindings.get(1));
        assertEquals(bill.getId(), bindings.get(2));
        assertEquals(2, bindings.size());
    }
}
