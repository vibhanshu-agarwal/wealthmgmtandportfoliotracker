package com.wealth.portfolio.composition;

import java.math.BigDecimal;

/** Wire-level composition element before materialisation: canonical ticker + quantity only. */
public record RawIntent(String ticker, BigDecimal quantity) {}
