package com.servora.usage;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Prices used to estimate the provider cost of each AI request, in US dollars per million tokens,
 * for the one provider that is configured. Zero means "not configured": usage is still counted, but
 * the estimated cost shows as unknown. Take the figures from the provider's own pricing page.
 */
@ConfigurationProperties("servora.usage")
@Validated
public record UsageProperties(
        @NotNull @DecimalMin("0") BigDecimal inputPricePerMillionTokensUsd,
        @NotNull @DecimalMin("0") BigDecimal outputPricePerMillionTokensUsd) {

    public boolean costConfigured() {
        return inputPricePerMillionTokensUsd.signum() > 0 || outputPricePerMillionTokensUsd.signum() > 0;
    }

    /**
     * Millionths of a dollar. A price per million tokens in dollars is exactly the price per token in
     * millionths of a dollar, so the cost is tokens times price, rounded half up.
     */
    public long costMicros(int inputTokens, int outputTokens) {
        return inputPricePerMillionTokensUsd
                .multiply(BigDecimal.valueOf(Math.max(inputTokens, 0)))
                .add(outputPricePerMillionTokensUsd.multiply(BigDecimal.valueOf(Math.max(outputTokens, 0))))
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .longValueExact();
    }
}
