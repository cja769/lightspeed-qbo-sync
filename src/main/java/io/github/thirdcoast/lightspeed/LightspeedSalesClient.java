package io.github.thirdcoast.lightspeed;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.thirdcoast.qbo.DailySalesSummaryProvider;
import io.github.thirdcoast.qbo.JsonHttp;
import io.github.thirdcoast.qbo.model.DailySalesSummary;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static io.github.thirdcoast.qbo.JsonHttp.*;

final class LightspeedSalesClient implements DailySalesSummaryProvider {
    private static final int PAGE_SIZE = 250;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private final JsonHttp http;
    private final URI search;
    private final String authorization;
    private final String outlet;
    private final ZoneId zone;

    LightspeedSalesClient(JsonHttp http, String shop, String token, String outlet, ZoneId zone) {
        if (!shop.matches("[a-z0-9-]+")) throw new IllegalArgumentException("Invalid Lightspeed shop prefix");
        this.http = http;
        this.search = URI.create("https://" + shop + ".retail.lightspeed.app/api/2026-10/search");
        this.authorization = "Bearer " + token;
        this.outlet = outlet;
        this.zone = zone;
    }

    @Override
    public DailySalesSummary load(LocalDate date) {
        var start = date.atStartOfDay(zone).toInstant();
        var end = date.plusDays(1).atStartOfDay(zone).toInstant();
        var query = "?type=sales&state=closed&outlet_id=" + encode(outlet) + "&date_from=" + encode(start.toString())
                + "&date_to=" + encode(end.toString()) + "&page_size=" + PAGE_SIZE + "&order_by=date&order_direction=asc";
        var totals = new Totals();
        var seen = new HashSet<String>();
        for (int offset = 0; ; ) {
            var response = http.get(URI.create(search + query + "&offset=" + offset), authorization);
            var sales = array(response, "data");
            for (var sale : sales) {
                var id = text(sale, "id");
                if (!seen.add(id)) throw new IllegalStateException("Duplicate Lightspeed sale in paginated response");
                var timestamp = Instant.parse(text(sale, "date"));
                if (timestamp.equals(end)) continue;
                if (timestamp.isBefore(start) || timestamp.isAfter(end)
                        || !outlet.equals(text(sale.path("source"), "outlet_id"))) {
                    throw new IllegalStateException("Lightspeed sale is outside the requested outlet or time window");
                }
                if (!"closed".equals(text(sale, "state"))) {
                    throw new IllegalStateException("Lightspeed search returned a sale that is not closed");
                }
                try {
                    addSale(sale, totals);
                } catch (IllegalStateException e) {
                    throw new IllegalStateException("Lightspeed receipt " + id + ": " + e.getMessage());
                }
            }
            offset += sales.size();
            var count = response.path("total_count");
            if (count.isIntegralNumber()) {
                if (offset >= count.asLong()) break;
                if (sales.isEmpty()) throw new IllegalStateException("Incomplete Lightspeed sales page");
            } else if (sales.size() < PAGE_SIZE) {
                break;
            }
        }
        var report = new LightspeedDailyReport(outlet, date, totals.gross, totals.discounts,
                totals.returns, totals.tax, ZERO, ZERO, ZERO, ZERO, totals.tenders);
        return new LightspeedMapper().map(report, "USD");
    }

    static void addSale(JsonNode sale, Totals totals) {
        var attributes = sale.path("attributes");
        for (var attribute : attributes) {
            if (java.util.Set.of("onaccount", "layby").contains(attribute.asText())) {
                throw new IllegalStateException("Lightspeed account or layby sale needs separate review");
            }
        }
        if (!sale.path("ecom_custom_charges").isEmpty() || !sale.path("adjustments").isEmpty()) {
            throw new IllegalStateException("Lightspeed sale has e-commerce charges or adjustments requiring a separate mapping");
        }
        var receipt = sale.path("totals");
        var tax = decimal(receipt, "tax");
        var netSales = decimal(receipt, "price");
        var amountDue = decimal(receipt, "price_incl_tax");
        if (netSales.add(tax).compareTo(amountDue) != 0) {
            throw new IllegalStateException("Lightspeed sale totals do not agree with receipt tax");
        }
        var discounts = ZERO;
        boolean fulfillmentRequired = false;
        for (var attribute : attributes) {
            if ("delivery".equals(attribute.asText()) || "pickup".equals(attribute.asText())) fulfillmentRequired = true;
        }
        for (var line : array(sale, "line_items")) {
            if (fulfillmentRequired && decimal(line, "quantity_recognized").compareTo(decimal(line, "quantity")) != 0) {
                throw new IllegalStateException("Lightspeed order has partially recognized revenue");
            }
            if (line.hasNonNull("gift_card_number")) {
                throw new IllegalStateException("Lightspeed gift card issue needs a liability mapping");
            }
            discounts = discounts.add(decimal(line.path("pricing"), "discount_total"));
        }
        var paid = ZERO;
        for (var payment : array(sale, "payments")) {
            if (payment.hasNonNull("deleted_at")) continue;
            var surcharge = payment.path("surcharge");
            if (surcharge.hasNonNull("amount") && decimal(surcharge, "amount").signum() != 0) {
                throw new IllegalStateException("Lightspeed payment surcharge needs a separate mapping");
            }
            var amount = decimal(payment, "amount");
            var tender = text(payment.path("type"), "config_id");
            if (amount.signum() != 0) totals.tenders.merge(tender, amount, BigDecimal::add);
            paid = paid.add(amount);
        }
        if (paid.compareTo(amountDue) != 0) throw new IllegalStateException("Lightspeed tenders do not match the closed receipt");
        totals.tax = totals.tax.add(tax);
        if (sale.path("return").path("is_return").asBoolean() || netSales.signum() < 0) {
            if (netSales.signum() > 0) throw new IllegalStateException("Lightspeed exchange needs separate review");
            totals.returns = totals.returns.subtract(netSales);
        } else {
            if (discounts.signum() < 0) throw new IllegalStateException("Lightspeed sale contains a negative discount");
            totals.gross = totals.gross.add(netSales).add(discounts);
            totals.discounts = totals.discounts.add(discounts);
        }
    }

    private static BigDecimal decimal(JsonNode parent, String field) {
        var value = parent.path(field);
        if (value.isNumber()) return value.decimalValue();
        if (value.isTextual() && value.asText().matches("-?[0-9]+(\\.[0-9]+)?")) {
            return new BigDecimal(value.asText());
        }
        throw new IllegalStateException("Missing or invalid Lightspeed amount " + field);
    }

    static final class Totals {
        BigDecimal gross = ZERO;
        BigDecimal discounts = ZERO;
        BigDecimal returns = ZERO;
        BigDecimal tax = ZERO;
        final Map<String, BigDecimal> tenders = new HashMap<>();
    }
}
