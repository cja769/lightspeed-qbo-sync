package io.github.thirdcoast.lightspeed;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public record LightspeedDailyReport(String shopId, LocalDate date, BigDecimal grossSales, BigDecimal discounts,
                                    BigDecimal returns, BigDecimal tax, BigDecimal tips, BigDecimal giftCardsSold,
                                    BigDecimal giftCardsRedeemed, BigDecimal fees, Map<String, BigDecimal> payments) {}
