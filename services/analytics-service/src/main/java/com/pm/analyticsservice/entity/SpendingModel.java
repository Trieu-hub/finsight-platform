package com.pm.analyticsservice.entity;

import com.pm.analyticsservice.forecast.SeasonalTrendModel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The persisted parameters of one user's fitted daily-spend model — the artefact that makes
 * this "trained" rather than "computed on the fly": the nightly trainer writes it, and every
 * forecast request afterwards reads it back instead of re-deriving it.
 *
 * <p>The seven weekday indices are separate columns so a fit can be inspected with a plain
 * SELECT.
 */
@Entity
@Table(name = "spending_model")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpendingModel {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", updatable = false, nullable = false, length = 36)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "level_value", nullable = false, precision = 18, scale = 6)
    private BigDecimal levelValue;

    @Column(name = "trend_value", nullable = false, precision = 18, scale = 6)
    private BigDecimal trendValue;

    @Column(name = "dow_mon", nullable = false, precision = 10, scale = 6)
    private BigDecimal dowMon;

    @Column(name = "dow_tue", nullable = false, precision = 10, scale = 6)
    private BigDecimal dowTue;

    @Column(name = "dow_wed", nullable = false, precision = 10, scale = 6)
    private BigDecimal dowWed;

    @Column(name = "dow_thu", nullable = false, precision = 10, scale = 6)
    private BigDecimal dowThu;

    @Column(name = "dow_fri", nullable = false, precision = 10, scale = 6)
    private BigDecimal dowFri;

    @Column(name = "dow_sat", nullable = false, precision = 10, scale = 6)
    private BigDecimal dowSat;

    @Column(name = "dow_sun", nullable = false, precision = 10, scale = 6)
    private BigDecimal dowSun;

    @Column(name = "sigma", nullable = false, precision = 18, scale = 6)
    private BigDecimal sigma;

    @Column(name = "sample_days", nullable = false)
    private int sampleDays;

    /** Last day covered by the training window — the origin every prediction counts from. */
    @Column(name = "trained_upto", nullable = false)
    private LocalDate trainedUpto;

    @Column(name = "trained_at", nullable = false)
    private LocalDateTime trainedAt;

    /** Rebuilds the in-memory model this row was written from. */
    public SeasonalTrendModel toSeasonalTrendModel() {
        double[] dow = {
                dowMon.doubleValue(), dowTue.doubleValue(), dowWed.doubleValue(),
                dowThu.doubleValue(), dowFri.doubleValue(), dowSat.doubleValue(),
                dowSun.doubleValue()
        };
        return new SeasonalTrendModel(
                levelValue.doubleValue(), trendValue.doubleValue(), dow,
                sigma.doubleValue(), sampleDays);
    }

    /** Copies a freshly fitted model onto this row. */
    public void apply(SeasonalTrendModel model, LocalDate trainedUpto, LocalDateTime now) {
        double[] dow = model.dowIndex();
        this.levelValue = BigDecimal.valueOf(model.level());
        this.trendValue = BigDecimal.valueOf(model.trend());
        this.dowMon = BigDecimal.valueOf(dow[0]);
        this.dowTue = BigDecimal.valueOf(dow[1]);
        this.dowWed = BigDecimal.valueOf(dow[2]);
        this.dowThu = BigDecimal.valueOf(dow[3]);
        this.dowFri = BigDecimal.valueOf(dow[4]);
        this.dowSat = BigDecimal.valueOf(dow[5]);
        this.dowSun = BigDecimal.valueOf(dow[6]);
        this.sigma = BigDecimal.valueOf(model.sigma());
        this.sampleDays = model.sampleDays();
        this.trainedUpto = trainedUpto;
        this.trainedAt = now;
    }
}
