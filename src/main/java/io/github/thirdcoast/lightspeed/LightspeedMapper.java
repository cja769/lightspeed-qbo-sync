package io.github.thirdcoast.lightspeed;

import io.github.thirdcoast.qbo.model.DailySalesSummary;
import io.github.thirdcoast.qbo.model.Money;
import java.util.Map;
import java.util.stream.Collectors;

public final class LightspeedMapper {
    public DailySalesSummary map(LightspeedDailyReport r, String currency) {
        var tenders = r.payments().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> new Money(e.getValue(), currency)));
        return new DailySalesSummary("lightspeed:" + r.shopId() + ":" + r.date(), "lightspeed", r.shopId(), r.date(),
                new Money(r.grossSales(), currency), new Money(r.discounts(), currency), new Money(r.returns(), currency),
                new Money(r.tax(), currency), new Money(r.tips(), currency), new Money(r.giftCardsSold(), currency),
                new Money(r.giftCardsRedeemed(), currency), new Money(r.fees(), currency), tenders);
    }
}
