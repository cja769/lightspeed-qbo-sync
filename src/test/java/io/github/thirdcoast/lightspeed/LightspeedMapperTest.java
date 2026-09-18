package io.github.thirdcoast.lightspeed;

import io.github.thirdcoast.qbo.SummaryValidator;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LightspeedMapperTest {
    @Test void mapsAndReconciles() {
        var source = new LightspeedDailyReport("shop", LocalDate.of(2026,9,18), new BigDecimal("100.00"),
                new BigDecimal("5.00"), new BigDecimal("10.00"), new BigDecimal("7.01"), BigDecimal.ZERO.setScale(2),
                new BigDecimal("20.00"), new BigDecimal("15.00"), new BigDecimal("3.00"), Map.of("card", new BigDecimal("97.01")));
        var normalized = new LightspeedMapper().map(source, "USD");
        SummaryValidator.validate(normalized);
        assertEquals("lightspeed:shop:2026-09-18", normalized.idempotencyKey());
    }
}
