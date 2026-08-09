package com.pm.transactionservice.export;

import com.pm.transactionservice.entity.Transaction;
import com.pm.transactionservice.enums.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the CSV rendering: the sign convention that lets an exported file be imported
 * back, and the escaping that keeps a description from breaking — or hijacking — the file.
 */
class TransactionCsvWriterTest {

    private static final Map<Long, String> CATEGORIES = Map.of(4L, "Food", 1L, "Salary");

    private final TransactionCsvWriter writer = new TransactionCsvWriter();

    @Test
    void writesTheHeaderEvenWithNoRows() {
        assertThat(writer.write(List.of(), CATEGORIES))
                .isEqualTo(TransactionCsvWriter.HEADER + "\r\n");
    }

    @Test
    void expenseIsNegativeAndIncomeIsPositive() {
        // The import page's default mode reads a negative row as money out, so this is what makes
        // an exported file re-importable without the user configuring any columns.
        String csv = writer.write(List.of(
                transaction(TransactionType.EXPENSE, "42.50", 4L, "Lunch"),
                transaction(TransactionType.INCOME, "1500.00", 1L, "Salary")), CATEGORIES);

        // Trailing zeros are stripped: the column is DECIMAL(19,4) and a VND amount would
        // otherwise come out as 250000.0000.
        assertThat(dataRows(csv)).containsExactly(
                "2026-06-01,-42.5,USD,EXPENSE,Food,Lunch," + ID,
                "2026-06-01,1500,USD,INCOME,Salary,Salary," + ID);
    }

    @Test
    void transferLeavesTheWalletSoItIsNegativeToo() {
        String csv = writer.write(
                List.of(transaction(TransactionType.TRANSFER, "100.00", 11L, "To savings")),
                CATEGORIES);

        assertThat(dataRows(csv).get(0)).startsWith("2026-06-01,-100,USD,TRANSFER,");
    }

    @Test
    void anUnknownCategoryIsBlankRatherThanANumber() {
        String csv = writer.write(
                List.of(transaction(TransactionType.EXPENSE, "5.00", 999L, "Mystery")), Map.of());

        assertThat(dataRows(csv).get(0)).contains(",EXPENSE,,Mystery,");
    }

    @Test
    void quotesADescriptionContainingADelimiterOrAQuote() {
        String csv = writer.write(List.of(
                transaction(TransactionType.EXPENSE, "5.00", 4L, "Coffee, black"),
                transaction(TransactionType.EXPENSE, "6.00", 4L, "The \"usual\"")), CATEGORIES);

        assertThat(dataRows(csv).get(0)).endsWith("\"Coffee, black\"," + ID);
        assertThat(dataRows(csv).get(1)).endsWith("\"The \"\"usual\"\"\"," + ID);
    }

    @Test
    void neutralisesADescriptionASpreadsheetWouldRunAsAFormula() {
        // A description is free text from the user. Left alone, "=1+1" is a formula the moment
        // the file is opened in Excel or Sheets — the CSV injection every export has to handle.
        String csv = writer.write(
                List.of(transaction(TransactionType.EXPENSE, "5.00", 4L, "=SUM(A1:A9)")),
                CATEGORIES);

        // Quoted because of the tab, and the tab is what stops the leading '=' being a formula.
        assertThat(dataRows(csv).get(0)).endsWith(",\"\t=SUM(A1:A9)\"," + ID);
    }

    @Test
    void aMissingDescriptionIsAnEmptyField() {
        String csv = writer.write(
                List.of(transaction(TransactionType.EXPENSE, "5.00", 4L, null)), CATEGORIES);

        assertThat(dataRows(csv).get(0)).isEqualTo("2026-06-01,-5,USD,EXPENSE,Food,," + ID);
    }

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static Transaction transaction(TransactionType type, String amount, Long categoryId,
                                           String description) {
        return Transaction.builder()
                .id(ID)
                .userId(1L)
                .type(type)
                .amount(new BigDecimal(amount))
                .currency("USD")
                .categoryId(categoryId)
                .description(description)
                .transactionDate(LocalDate.of(2026, 6, 1))
                .build();
    }

    /** The rows after the header, without the trailing blank produced by the final CRLF. */
    private static List<String> dataRows(String csv) {
        return List.of(csv.split("\r\n")).subList(1, csv.split("\r\n").length);
    }
}
