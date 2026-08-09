package com.pm.transactionservice.export;

import com.pm.transactionservice.entity.Transaction;
import com.pm.transactionservice.enums.TransactionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Renders transactions as CSV — the mirror of the statement import, and deliberately in a shape
 * this platform can read back.
 *
 * <h4>Why the amount is signed</h4>
 * <p>The import page reads a statement whose amounts are signed (its default "SIGN" mode: a
 * negative row is money out). Exporting the same way means a file that leaves here can be
 * re-imported without the user configuring anything, and it makes a spreadsheet SUM over the
 * column produce the net figure rather than nonsense. A TRANSFER is written negative too: it
 * leaves the wallet the row is filed under.
 *
 * <h4>Header names</h4>
 * <p>{@code date}, {@code amount} and {@code description} are exactly the names the import page
 * auto-detects, so a round trip needs no column mapping. The rest are for the human reading it.
 */
@Component
public class TransactionCsvWriter {

    static final String HEADER = "date,amount,currency,type,category,description,id";

    /** RFC 4180 line ending: Excel is the overwhelmingly likely reader. */
    private static final String CRLF = "\r\n";

    /**
     * Writes {@code transactions} as a CSV document, resolving category ids to names through
     * {@code categoryNames} (an id with no entry is written blank rather than as a bare number,
     * which would read as a second amount column).
     */
    public String write(List<Transaction> transactions, Map<Long, String> categoryNames) {
        StringBuilder csv = new StringBuilder(HEADER).append(CRLF);
        for (Transaction transaction : transactions) {
            csv.append(transaction.getTransactionDate()).append(',')
                    .append(signedAmount(transaction.getType(), transaction.getAmount())).append(',')
                    .append(escape(transaction.getCurrency())).append(',')
                    .append(transaction.getType()).append(',')
                    .append(escape(categoryNames.get(transaction.getCategoryId()))).append(',')
                    .append(escape(transaction.getDescription())).append(',')
                    .append(transaction.getId())
                    .append(CRLF);
        }
        return csv.toString();
    }

    /**
     * Money out is negative, money in is positive — amounts are stored unsigned.
     *
     * <p>Trailing zeros are stripped because the column is {@code DECIMAL(19,4)}: a VND amount
     * would otherwise export as {@code 250000.0000}. Stripping is safe for every currency,
     * where forcing two decimals would round a three-decimal one. {@code toPlainString} keeps
     * it out of scientific notation, which no spreadsheet reads back as the same number.
     */
    private static String signedAmount(TransactionType type, BigDecimal amount) {
        if (amount == null) {
            return "";
        }
        BigDecimal signed = type == TransactionType.INCOME ? amount : amount.negate();
        return signed.stripTrailingZeros().toPlainString();
    }

    /**
     * Quotes a field when it contains a delimiter, a quote or a newline, doubling any quote —
     * RFC 4180. A description is free text a user typed, so this is not optional: an unescaped
     * comma in it would shift every later column of that row.
     *
     * <p>A leading {@code =}, {@code +}, {@code -} or {@code @} is prefixed with a tab as well.
     * Excel and Sheets treat such a field as a formula, which turns a description someone else
     * chose into code that runs when the file is opened.
     */
    static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String field = value;
        if ("=+-@".indexOf(field.charAt(0)) >= 0) {
            field = "\t" + field;
        }
        if (field.indexOf(',') >= 0 || field.indexOf('"') >= 0
                || field.indexOf('\n') >= 0 || field.indexOf('\r') >= 0 || field.startsWith("\t")) {
            return '"' + field.replace("\"", "\"\"") + '"';
        }
        return field;
    }
}
