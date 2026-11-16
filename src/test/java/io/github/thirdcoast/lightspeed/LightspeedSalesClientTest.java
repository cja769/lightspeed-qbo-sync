package io.github.thirdcoast.lightspeed;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.thirdcoast.qbo.SummaryValidator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class LightspeedSalesClientTest {
    @Test
    void preservesSignedTaxAndTenderOnRefundOnlyDay() throws Exception {
        var totals = new LightspeedSalesClient.Totals();
        var refund = new ObjectMapper().readTree("""
                {"totals":{"tax":"-0.82","price":"-10.00","price_incl_tax":"-10.82"},
                 "return":{"is_return":true},
                 "line_items":[{"pricing":{"discount_total":0}}],
                 "payments":[{"amount":"-10.82","type":{"config_id":"card"}}]}
                """);
        LightspeedSalesClient.addSale(refund, totals);
        assertEquals(new BigDecimal("10.00"), totals.returns);
        assertEquals(new BigDecimal("-0.82"), totals.tax);
        var zero = BigDecimal.ZERO;
        var source = new LightspeedDailyReport("outlet", LocalDate.of(2026, 10, 20), totals.gross,
                totals.discounts, totals.returns, totals.tax, zero, zero, zero, zero, totals.tenders);
        assertDoesNotThrow(() -> SummaryValidator.validate(new LightspeedMapper().map(source, "USD")));
    }
}
