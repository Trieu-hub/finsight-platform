package com.pm.transactionservice.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Group I: Vietnamese text survives the whole round trip byte for byte — JSON in, MySQL, JSON out,
 * CSV out.
 *
 * <p>Mojibake is invisible from inside the application: every layer hands the next one a Java
 * {@code String}, so a description reads back correctly even when the bytes on disk are wrong,
 * and the damage only shows up in a browser or a spreadsheet. These tests therefore assert the
 * <em>bytes</em> — {@code HEX()} straight from MySQL, and the raw response body — against
 * expectations spelled out in ASCII. An ASCII expectation is deliberate: it holds even if the
 * compiler were to read this source file in the wrong encoding, which a literal-to-literal
 * comparison would not catch.
 */
class VietnameseTextIntegrationTest extends AbstractMockMvcIntegrationTest {

    /**
     * Exercises the three widths UTF-8 uses for this language plus one beyond it: {@code ư}
     * (U+01B0) is two bytes, {@code ậ}/{@code ễ} (U+1EAD, U+1EC5) are three, and the noodle
     * bowl is four — which is stored only because the schema is utf8mb4. On utf8mb3 the
     * insert fails outright, so the emoji is what pins that choice down.
     */
    private static final String DESCRIPTION = "Ăn trưa tại Quán Nem Nướng — Nguyễn Huệ 🍜";

    /** Same idea for a name, the field a user is most likely to notice being wrong. */
    private static final String WALLET_NAME = "Ví tiền mặt của Đặng Thị Hường";

    /** {@link #DESCRIPTION} encoded as UTF-8. Written out so the assertion depends on no encoding. */
    private static final String DESCRIPTION_UTF8_HEX =
            "C4826E207472C6B0612074E1BAA169205175C3A16E204E656D204EC6B0E1BB9B6E67"
                    + "20E28094204E677579E1BB856E204875E1BB8720F09F8D9C";

    /** {@link #WALLET_NAME} encoded as UTF-8. */
    private static final String WALLET_NAME_UTF8_HEX =
            "56C3AD207469E1BB816E206DE1BAB7742063E1BBA76120C490E1BAB76E67205468E1BB8B2048C6B0E1BB9D6E67";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * The storage half: what MySQL actually holds after a request carrying UTF-8 bytes. A
     * connection negotiating latin1, or a column that is not utf8mb4, corrupts the value here —
     * and only here — so the assertion reads the stored bytes rather than the string JPA hands back.
     */
    @Test
    void storesAVietnameseDescriptionAsTheUtf8BytesTheClientSent() throws Exception {
        long userId = uniqueUserId();
        String id = createTransactionWithDescription(userId, DESCRIPTION);

        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT HEX(description) AS hex, CHAR_LENGTH(description) AS chars, "
                        + "LENGTH(description) AS bytes FROM transactions WHERE id = ?", id);

        assertThat(stored.get("hex")).isEqualTo(DESCRIPTION_UTF8_HEX);
        // MySQL counts characters, Java counts UTF-16 units — the emoji is one character and two
        // of those, so codePointCount is the comparison that holds.
        assertThat(((Number) stored.get("chars")).intValue())
                .isEqualTo(DESCRIPTION.codePointCount(0, DESCRIPTION.length()));
        assertThat(((Number) stored.get("bytes")).intValue())
                .isEqualTo(DESCRIPTION.getBytes(StandardCharsets.UTF_8).length);
    }

    /** The same for a wallet name, which is a shorter VARCHAR in a different table. */
    @Test
    void storesAVietnameseWalletNameAsTheUtf8BytesTheClientSent() throws Exception {
        long userId = uniqueUserId();
        long walletId = createWallet(userId, WALLET_NAME, "CASH", "VND", "0");

        String hex = jdbcTemplate.queryForObject(
                "SELECT HEX(name) FROM wallets WHERE id = ?", String.class, walletId);

        assertThat(hex).isEqualTo(WALLET_NAME_UTF8_HEX);
    }

    /**
     * The delivery half: the bytes on the wire. Asserted before decoding, because decoding the
     * response with the charset the response itself declares would agree with any charset it chose.
     */
    @Test
    void returnsAVietnameseDescriptionAsUtf8OverJson() throws Exception {
        long userId = uniqueUserId();
        String id = createTransactionWithDescription(userId, DESCRIPTION);

        byte[] body = mockMvc.perform(get("/api/v1/transactions/" + id)
                        .header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(new String(body, StandardCharsets.UTF_8))
                .contains(DESCRIPTION);
        // A latin1-encoded body would still decode to *something*; it would not contain these bytes.
        assertThat(body).containsSequence(DESCRIPTION.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The CSV download — the surface where mojibake is most likely to reach a user, since the file
     * is opened by a spreadsheet rather than a browser that was told the charset in a header.
     */
    @Test
    void exportsAVietnameseDescriptionAsUtf8Csv() throws Exception {
        long userId = uniqueUserId();
        createTransactionWithDescription(userId, DESCRIPTION);

        var response = mockMvc.perform(get("/api/v1/transactions/export")
                        .header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        byte[] body = response.getContentAsByteArray();

        // Without the charset parameter the browser and Excel both fall back to a locale default.
        assertThat(response.getHeader(HttpHeaders.CONTENT_TYPE)).isEqualTo("text/csv;charset=UTF-8");
        assertThat(body).containsSequence(DESCRIPTION.getBytes(StandardCharsets.UTF_8));
        assertThat(new String(body, StandardCharsets.UTF_8)).contains(DESCRIPTION);
        // BOM-free, as the export deliberately is: a BOM would be read back as part of the first
        // header name when the file is re-imported.
        assertThat(body).startsWith("date".getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Guards the schema rather than a single write: a later migration adding a text column with the
     * server default instead of utf8mb4 would silently reintroduce the problem for that column only.
     */
    @Test
    void everyTextColumnInTheSchemaIsUtf8mb4() {
        List<Map<String, Object>> offenders = jdbcTemplate.queryForList(
                "SELECT table_name, column_name, character_set_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND character_set_name IS NOT NULL "
                        + "AND character_set_name <> 'utf8mb4'");

        assertThat(offenders).isEmpty();
    }

    /** Creates a transaction carrying a description, posting the body as the UTF-8 bytes a client sends. */
    private String createTransactionWithDescription(long userId, String description) throws Exception {
        String body = """
                {"type":"EXPENSE","amount":85000,"currency":"VND","categoryId":4,\
                "transactionDate":"2026-06-01","description":"%s"}
                """.formatted(description);

        String response = mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).path("data").path("id").asText();
    }
}
