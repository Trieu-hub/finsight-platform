-- Holdout accuracy for each fitted model: the evidence that serving it is an improvement.
--
-- V3 stored the fit but nothing about whether it works. The smoothing constants are chosen by
-- minimising IN-SAMPLE error, which only measures how well the model memorised its own training
-- window, so a model that fits noise scored well and was served anyway. The nightly trainer now
-- withholds the last two weeks, fits on the rest, and scores both the model and the run-rate
-- projection it would replace over those withheld days.
--
-- All three columns are NULLABLE and mean "not measured yet" — a series with too little history
-- to withhold two weeks and still fit gets a model row with no accuracy, and /forecast keeps
-- answering from the run rate. Defaulting them to 0 would read as "perfect model".
ALTER TABLE spending_model
    -- Days withheld from training and scored.
    ADD COLUMN holdout_days INT            NULL,
    -- Mean absolute error over those days, in currency units. Directly comparable to each other.
    ADD COLUMN model_mae    DECIMAL(18, 6) NULL,
    ADD COLUMN baseline_mae DECIMAL(18, 6) NULL;
