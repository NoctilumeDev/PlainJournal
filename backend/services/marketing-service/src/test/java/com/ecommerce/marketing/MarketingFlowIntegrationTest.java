package com.ecommerce.marketing;

import com.ecommerce.marketing.application.exception.MarketingError;
import com.ecommerce.marketing.application.exception.MarketingException;
import com.ecommerce.marketing.application.model.MarketingModels.BenefitView;
import com.ecommerce.marketing.application.model.MarketingModels.CreateRuleCommand;
import com.ecommerce.marketing.application.model.MarketingModels.DeliveryRegion;
import com.ecommerce.marketing.application.model.MarketingModels.DiscountAllocation;
import com.ecommerce.marketing.application.model.MarketingModels.GrantBenefitCommand;
import com.ecommerce.marketing.application.model.MarketingModels.LockPricingCommand;
import com.ecommerce.marketing.application.model.MarketingModels.PricingLine;
import com.ecommerce.marketing.application.model.MarketingModels.PricingLockView;
import com.ecommerce.marketing.application.model.MarketingModels.RegionRestriction;
import com.ecommerce.marketing.application.model.OrderLifecycleCommand;
import com.ecommerce.marketing.application.service.MarketingService;
import com.ecommerce.marketing.application.service.OrderLifecycleHandler;
import com.ecommerce.marketing.domain.BenefitType;
import com.ecommerce.marketing.domain.RegionLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@SpringBootTest
class MarketingFlowIntegrationTest {

    private final MarketingService marketingService;
    private final OrderLifecycleHandler orderLifecycleHandler;
    private final JdbcTemplate jdbcTemplate;
    private final MockMvc mockMvc;

    @Autowired
    MarketingFlowIntegrationTest(
            MarketingService marketingService,
            OrderLifecycleHandler orderLifecycleHandler,
            JdbcTemplate jdbcTemplate,
            MockMvc mockMvc) {
        this.marketingService = marketingService;
        this.orderLifecycleHandler = orderLifecycleHandler;
        this.jdbcTemplate = jdbcTemplate;
        this.mockMvc = mockMvc;
    }

    @AfterEach
    void cleanData() {
        jdbcTemplate.update("DELETE FROM consumed_event");
        jdbcTemplate.update("DELETE FROM pricing_lock_allocation");
        jdbcTemplate.update("DELETE FROM pricing_lock_benefit");
        jdbcTemplate.update("DELETE FROM pricing_lock");
        jdbcTemplate.update("DELETE FROM user_benefit");
        jdbcTemplate.update("DELETE FROM marketing_rule_region");
        jdbcTemplate.update("DELETE FROM marketing_rule");
    }

    @Test
    void stacksThreeBenefitTypesAndAllocatesEveryCent() {
        BenefitView coupon = createBenefit("COUPON-10", BenefitType.COUPON,
                "100.00", "10.00", 10, new RegionRestriction(RegionLevel.DISTRICT, "330106"));
        BenefitView redPacket = createBenefit("RED-5", BenefitType.RED_PACKET,
                "0.00", "5.00", 20, new RegionRestriction(RegionLevel.PROVINCE, "330000"));
        BenefitView subsidy = createBenefit("SUBSIDY-2", BenefitType.SUBSIDY,
                "0.00", "2.00", 30);

        LockPricingCommand command = pricing("ORDER-1001", List.of(
                coupon.benefitNo(), redPacket.benefitNo(), subsidy.benefitNo()), "330106");
        PricingLockView locked = marketingService.lockPricing(command);
        PricingLockView repeated = marketingService.lockPricing(command);

        assertThat(repeated.lockNo()).isEqualTo(locked.lockNo());
        assertThat(locked.status()).isEqualTo("LOCKED");
        assertThat(locked.originalAmount()).isEqualByComparingTo("120.00");
        assertThat(locked.couponDiscount()).isEqualByComparingTo("10.00");
        assertThat(locked.redPacketDiscount()).isEqualByComparingTo("5.00");
        assertThat(locked.subsidyDiscount()).isEqualByComparingTo("2.00");
        assertThat(locked.discountAmount()).isEqualByComparingTo("17.00");
        assertThat(locked.payableAmount()).isEqualByComparingTo("103.00");
        assertThat(locked.appliedBenefits()).hasSize(3);
        assertThat(locked.appliedBenefits()).allSatisfy(applied ->
                assertThat(applied.allocations().stream().map(DiscountAllocation::discountAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(applied.discountAmount()));
        assertThat(locked.appliedBenefits().stream().flatMap(applied -> applied.allocations().stream())
                .map(DiscountAllocation::discountAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("17.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_benefit WHERE status = 'LOCKED'", Integer.class)).isEqualTo(3);
    }

    @Test
    void enforcesRegionThresholdOwnershipAndOneBenefitPerType() {
        BenefitView districtCoupon = createBenefit("DISTRICT", BenefitType.COUPON,
                "100.00", "10.00", 10, new RegionRestriction(RegionLevel.DISTRICT, "330106"));
        BenefitView secondCoupon = createBenefit("SECOND", BenefitType.COUPON,
                "0.00", "1.00", 20);

        assertThatThrownBy(() -> marketingService.lockPricing(
                pricing("ORDER-WRONG-REGION", List.of(districtCoupon.benefitNo()), "310101")))
                .isInstanceOf(MarketingException.class)
                .satisfies(error -> assertThat(((MarketingException) error).error())
                        .isEqualTo(MarketingError.BENEFIT_NOT_ELIGIBLE));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pricing_lock", Integer.class)).isZero();

        assertThatThrownBy(() -> marketingService.lockPricing(
                pricing("ORDER-DUPLICATE-TYPE", List.of(
                        districtCoupon.benefitNo(), secondCoupon.benefitNo()), "330106")))
                .isInstanceOf(MarketingException.class)
                .satisfies(error -> assertThat(((MarketingException) error).error())
                        .isEqualTo(MarketingError.DUPLICATE_BENEFIT_TYPE));

        LockPricingCommand wrongOwner = new LockPricingCommand(
                "ORDER-WRONG-OWNER", 99L, new BigDecimal("120.00"),
                new DeliveryRegion("330000", "330100", "330106"),
                List.of(new PricingLine(1, 101L, new BigDecimal("120.00"))),
                List.of(districtCoupon.benefitNo()));
        assertThatThrownBy(() -> marketingService.lockPricing(wrongOwner))
                .isInstanceOf(MarketingException.class)
                .satisfies(error -> assertThat(((MarketingException) error).error())
                        .isEqualTo(MarketingError.BENEFIT_NOT_ELIGIBLE));
    }

    @Test
    void releasesOnCancellationAndRedeemsAfterPaymentIdempotently() {
        BenefitView coupon = createBenefit("LIFECYCLE", BenefitType.COUPON,
                "0.00", "10.00", 10);
        PricingLockView first = marketingService.lockPricing(
                pricing("ORDER-CANCEL", List.of(coupon.benefitNo()), "330106"));

        OrderLifecycleCommand canceled = new OrderLifecycleCommand(
                "00000000-0000-0000-0000-000000000401", "OrderCanceled", "ORDER-CANCEL");
        orderLifecycleHandler.handle(canceled);
        orderLifecycleHandler.handle(canceled);
        PricingLockView released = marketingService.getLock("ORDER-CANCEL");
        PricingLockView repeatedRelease = marketingService.release("ORDER-CANCEL");
        assertThat(released.status()).isEqualTo("RELEASED");
        assertThat(repeatedRelease.version()).isEqualTo(released.version());
        assertThat(marketingService.listBenefits(1L).get(0).status()).isEqualTo("AVAILABLE");

        PricingLockView second = marketingService.lockPricing(
                pricing("ORDER-PAID", List.of(coupon.benefitNo()), "330106"));
        OrderLifecycleCommand paid = new OrderLifecycleCommand(
                "00000000-0000-0000-0000-000000000402", "OrderPaid", "ORDER-PAID");
        orderLifecycleHandler.handle(paid);
        orderLifecycleHandler.handle(paid);
        PricingLockView redeemed = marketingService.getLock("ORDER-PAID");
        PricingLockView repeatedRedeem = marketingService.redeem("ORDER-PAID");
        assertThat(second.lockNo()).isNotEqualTo(first.lockNo());
        assertThat(redeemed.status()).isEqualTo("REDEEMED");
        assertThat(repeatedRedeem.version()).isEqualTo(redeemed.version());
        BenefitView consumed = marketingService.listBenefits(1L).get(0);
        assertThat(consumed.status()).isEqualTo("REDEEMED");
        assertThat(consumed.redeemedOrderNo()).isEqualTo("ORDER-PAID");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM consumed_event", Integer.class)).isEqualTo(2);
    }

    @Test
    void protectsAdminAndInternalRoutes() throws Exception {
        mockMvc.perform(get("/api/v1/marketing/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.service").value("marketing-service"));
        mockMvc.perform(post("/api/v1/marketing/admin/benefits")
                        .with(jwt().jwt(token -> token.subject("1"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/marketing/internal/pricing-locks")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/marketing/internal/pricing-locks")
                        .header("X-Internal-Service", "payment-service")
                        .header("X-Internal-Token", "test-internal-service-token-with-at-least-32-characters")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/marketing/internal/pricing-locks")
                        .header("X-Internal-Service", "trade-service")
                        .header("X-Internal-Token", "test-internal-service-token-with-at-least-32-characters")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    private BenefitView createBenefit(
            String code,
            BenefitType type,
            String threshold,
            String discount,
            int stackOrder,
            RegionRestriction... regions) {
        Instant now = Instant.now();
        marketingService.createRule(new CreateRuleCommand(
                code, code, type, new BigDecimal(threshold), new BigDecimal(discount), stackOrder,
                now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.DAYS), List.of(regions)));
        return marketingService.grantBenefit(new GrantBenefitCommand(1L, code, "GRANT-" + code));
    }

    private LockPricingCommand pricing(String orderNo, List<String> benefits, String districtCode) {
        return new LockPricingCommand(orderNo, 1L, new BigDecimal("120.00"),
                new DeliveryRegion("330000", "330100", districtCode),
                List.of(
                        new PricingLine(1, 101L, new BigDecimal("70.00")),
                        new PricingLine(2, 102L, new BigDecimal("50.00"))),
                benefits);
    }
}
