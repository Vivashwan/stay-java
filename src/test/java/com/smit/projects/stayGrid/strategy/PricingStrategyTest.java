package com.smit.projects.stayGrid.strategy;

import com.smit.projects.stayGrid.entity.Inventory;
import com.smit.projects.stayGrid.entity.Room;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the Decorator-based pricing engine.
 * Each decorator is tested in isolation by wrapping a fixed-price stub,
 * then the full chain is tested through PricingService.
 */
class PricingStrategyTest {

    private static final BigDecimal BASE_PRICE = new BigDecimal("1000.00");

    /** A stub inner strategy that always returns BASE_PRICE, so each decorator is tested alone. */
    private static final PricingStrategy FIXED_PRICE = inventory -> BASE_PRICE;

    private static Inventory inventory(String surgeFactor, int booked, int reserved, LocalDate date) {
        Room room = new Room();
        room.setBasePrice(BASE_PRICE);
        return Inventory.builder()
                .room(room)
                .surgeFactor(new BigDecimal(surgeFactor))
                .bookedCount(booked)
                .reservedCount(reserved)
                .totalCount(10)
                .date(date)
                .build();
    }

    private static Inventory inventoryFarInFuture() {
        return inventory("1.0", 0, 1, LocalDate.now().plusDays(30));
    }

    @Test
    @DisplayName("Base strategy returns the room's base price")
    void baseStrategy_returnsRoomBasePrice() {
        BigDecimal price = new BasePricingStrategy().calculatePrice(inventoryFarInFuture());

        assertThat(price).isEqualByComparingTo("1000.00");
    }

    @Nested
    @DisplayName("Surge pricing")
    class SurgePricing {

        @ParameterizedTest(name = "surge factor {0} -> {1}")
        @CsvSource({"1.0, 1000.00", "1.5, 1500.00", "2.25, 2250.00", "0.8, 800.00"})
        void multipliesWrappedPriceBySurgeFactor(String surgeFactor, String expected) {
            Inventory inv = inventory(surgeFactor, 0, 1, LocalDate.now().plusDays(30));

            BigDecimal price = new SurgePricingStrategy(FIXED_PRICE).calculatePrice(inv);

            assertThat(price).isEqualByComparingTo(expected);
        }
    }

    @Nested
    @DisplayName("Occupancy pricing")
    class OccupancyPricing {

        @Test
        @DisplayName("adds 20% when occupancy is above 80%")
        void addsPremiumAboveThreshold() {
            Inventory inv = inventory("1.0", 9, 10, LocalDate.now().plusDays(30));

            BigDecimal price = new OccupancyPricingStrategy(FIXED_PRICE).calculatePrice(inv);

            assertThat(price).isEqualByComparingTo("1200.00");
        }

        @Test
        @DisplayName("leaves price unchanged at exactly 80% (threshold is exclusive)")
        void noPremiumAtThreshold() {
            Inventory inv = inventory("1.0", 8, 10, LocalDate.now().plusDays(30));

            BigDecimal price = new OccupancyPricingStrategy(FIXED_PRICE).calculatePrice(inv);

            assertThat(price).isEqualByComparingTo("1000.00");
        }

        @Test
        @DisplayName("leaves price unchanged when nothing is booked")
        void noPremiumWhenEmpty() {
            Inventory inv = inventory("1.0", 0, 10, LocalDate.now().plusDays(30));

            BigDecimal price = new OccupancyPricingStrategy(FIXED_PRICE).calculatePrice(inv);

            assertThat(price).isEqualByComparingTo("1000.00");
        }
    }

    @Nested
    @DisplayName("Urgency pricing")
    class UrgencyPricing {

        @ParameterizedTest(name = "check-in in {0} day(s) -> +15%")
        @ValueSource(ints = {0, 1, 3, 6})
        void addsPremiumWithinSevenDays(int daysFromToday) {
            Inventory inv = inventory("1.0", 0, 1, LocalDate.now().plusDays(daysFromToday));

            BigDecimal price = new UrgencyPricingStrategy(FIXED_PRICE).calculatePrice(inv);

            assertThat(price).isEqualByComparingTo("1150.00");
        }

        @ParameterizedTest(name = "date offset {0} day(s) -> no premium")
        @ValueSource(ints = {-1, 7, 8, 30})
        void noPremiumOutsideWindow(int daysFromToday) {
            Inventory inv = inventory("1.0", 0, 1, LocalDate.now().plusDays(daysFromToday));

            BigDecimal price = new UrgencyPricingStrategy(FIXED_PRICE).calculatePrice(inv);

            assertThat(price).isEqualByComparingTo("1000.00");
        }
    }

    @Test
    @DisplayName("Holiday strategy currently always applies +25% (holiday lookup is still a stub)")
    void holidayStrategy_currentlyAlwaysApplies() {
        BigDecimal price = new HolidayPricingStrategy(FIXED_PRICE).calculatePrice(inventoryFarInFuture());

        assertThat(price).isEqualByComparingTo("1250.00");
    }

    @Nested
    @DisplayName("PricingService (full decorator chain)")
    class FullChain {

        private final PricingService pricingService = new PricingService();

        @Test
        @DisplayName("applies surge, occupancy, urgency and holiday multipliers together")
        void appliesAllStrategiesInChain() {
            // 1000 x 1.2 (surge) x 1.2 (occupancy 90%) x 1.15 (urgency) x 1.25 (holiday) = 2070
            Inventory inv = inventory("1.2", 9, 10, LocalDate.now().plusDays(2));

            BigDecimal price = pricingService.calculateDynamicPricing(inv);

            assertThat(price).isEqualByComparingTo("2070.00");
        }

        @Test
        @DisplayName("applies only surge and holiday when the other rules do not trigger")
        void appliesOnlyMatchingStrategies() {
            // 1000 x 1.5 (surge) x 1.25 (holiday) = 1875
            Inventory inv = inventory("1.5", 0, 1, LocalDate.now().plusDays(30));

            BigDecimal price = pricingService.calculateDynamicPricing(inv);

            assertThat(price).isEqualByComparingTo("1875.00");
        }

        @Test
        @DisplayName("sums the dynamic price of every night in the stay")
        void totalPriceSumsEachNight() {
            List<Inventory> stay = List.of(
                    inventory("1.0", 0, 1, LocalDate.now().plusDays(30)),   // 1250
                    inventory("1.5", 0, 1, LocalDate.now().plusDays(31)),   // 1875
                    inventory("1.0", 9, 10, LocalDate.now().plusDays(32))   // 1500
            );

            BigDecimal total = pricingService.calculateTotalPrice(stay);

            assertThat(total).isEqualByComparingTo("4625.00");
        }

        @Test
        @DisplayName("returns zero for an empty stay")
        void totalPriceOfEmptyStayIsZero() {
            assertThat(pricingService.calculateTotalPrice(List.of())).isEqualByComparingTo("0");
        }
    }
}
