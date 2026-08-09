package com.pm.riskservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.pm.riskservice.entity.ExpenseObservation;
import com.pm.riskservice.entity.RecurringSeries;
import com.pm.riskservice.recurring.RecurringDetector;
import com.pm.riskservice.repository.ObservedExpenseRepository;
import com.pm.riskservice.repository.RecurringSeriesRepository;
import com.pm.riskservice.rule.RiskRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-context tests for recurring-charge detection (Phase G.1) against real MySQL. These exist
 * for the two things the unit tests cannot reach: the JPQL — the price band is expressed as
 * factors of the series' own amount, and the seed lookup is an ordered one-row query — and the
 * V10 migration Hibernate validates the entity against.
 */
class RecurringApiIntegrationTest extends AbstractMockMvcIntegrationTest {

    private static final long USER = 9301L;
    private static final long CATEGORY = 7L;
    private static final String CUR = "USD";
    private static final LocalDate JUN_13 = LocalDate.of(2026, 6, 13);

    @Autowired
    private RecurringDetector detector;
    @Autowired
    private RecurringSeriesRepository seriesRepository;
    @Autowired
    private ObservedExpenseRepository expenseRepository;

    @BeforeEach
    void clean() {
        seriesRepository.deleteAll();
        expenseRepository.deleteAll();
    }

    @Test
    void threeMonthlyChargesOpenConfirmAndListASeries() throws Exception {
        // April and May: the second charge opens the series, quietly.
        seedExpense("9.99", JUN_13.minusDays(60));
        assertThat(charge("9.99", JUN_13.minusDays(30))).isEmpty();

        // June: the third confirms it.
        assertThat(charge("9.99", JUN_13)).containsExactly(RiskRule.RECURRING_CHARGE_DETECTED);

        JsonNode series = onlySeries();
        assertThat(series.path("userId").asLong()).isEqualTo(USER);
        assertThat(series.path("intervalDays").asInt()).isEqualTo(30);
        assertThat(series.path("occurrences").asInt()).isEqualTo(3);
        assertThat(series.path("status").asText()).isEqualTo(RecurringSeries.ACTIVE);
        assertThat(series.path("nextExpected").asText()).isEqualTo(JUN_13.plusDays(30).toString());
        assertThat(new BigDecimal(series.path("typicalAmount").asText()))
                .isEqualByComparingTo("9.99");
    }

    @Test
    void aPriceRiseStillMatchesItsOwnSeries() {
        // The point of the 25% match band being wider than the 15% rise threshold: a charge that
        // went up must be recognised as the same subscription, not seed a second series.
        seedExpense("10.00", JUN_13.minusDays(60));
        charge("10.00", JUN_13.minusDays(30));
        charge("10.00", JUN_13);

        assertThat(charge("11.50", JUN_13.plusDays(30)))
                .containsExactly(RiskRule.RECURRING_PRICE_INCREASE);
        List<RecurringSeries> all = seriesRepository.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getTypicalAmount()).isEqualByComparingTo("11.50");
    }

    @Test
    void aWeeklyGroceryRunDoesNotBecomeAMonthlySeries() {
        // Seeded charges seven days apart open a weekly series, not a monthly one — the cadence
        // bands do not overlap, so the interval recorded is the one that actually fits.
        seedExpense("40.00", JUN_13.minusDays(7));
        charge("40.00", JUN_13);

        assertThat(seriesRepository.findAll())
                .singleElement()
                .satisfies(series -> assertThat(series.getIntervalDays()).isEqualTo(7));
    }

    @Test
    void listIsEmptyWhenNothingRecurs() throws Exception {
        mockMvc.perform(get("/api/v1/recurring"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private JsonNode onlySeries() throws Exception {
        String body = mockMvc.perform(get("/api/v1/recurring"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get(0);
    }

    /** Runs the detector for a charge, as the rule engine would once it has recorded it. */
    private List<RiskRule> charge(String amount, LocalDate date) {
        seedExpense(amount, date);
        return detector.evaluate(USER, CATEGORY, CUR, new BigDecimal(amount),
                UUID.randomUUID(), date);
    }

    private void seedExpense(String amount, LocalDate date) {
        expenseRepository.save(new ExpenseObservation(
                UUID.randomUUID(), USER, CATEGORY, new BigDecimal(amount), CUR,
                date.atStartOfDay().toInstant(ZoneOffset.UTC), date));
    }
}
