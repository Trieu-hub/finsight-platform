package com.pm.analyticsservice.summarizer;

import java.math.BigDecimal;

/** One category line fed to the summarizer: its name, total, and share of expense (%). */
public record CategoryFigure(String name, BigDecimal amount, double share) {
}
