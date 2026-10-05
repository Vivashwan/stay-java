package com.smit.projects.stayGrid.bdd;

import com.smit.projects.stayGrid.entity.Inventory;
import com.smit.projects.stayGrid.entity.Room;
import com.smit.projects.stayGrid.strategy.PricingService;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class PricingSteps {

    private final PricingService pricingService = new PricingService();

    private final Room room = new Room();
    private BigDecimal surgeFactor;
    private int booked;
    private int reserved;
    private final List<LocalDate> stayDates = new ArrayList<>();
    private BigDecimal price;

    @Given("a room with a base price of {bigdecimal}")
    public void aRoomWithABasePriceOf(BigDecimal basePrice) {
        room.setBasePrice(basePrice);
    }

    @Given("a surge factor of {bigdecimal}")
    public void aSurgeFactorOf(BigDecimal surge) {
        this.surgeFactor = surge;
    }

    @And("{int} of {int} reserved rooms are already booked")
    public void reservedRoomsAreAlreadyBooked(int booked, int reserved) {
        this.booked = booked;
        this.reserved = reserved;
    }

    @And("the stay date is {int} days from today")
    public void theStayDateIsDaysFromToday(int days) {
        stayDates.clear();
        stayDates.add(LocalDate.now().plusDays(days));
    }

    @And("a stay of {int} nights starting {int} days from today")
    public void aStayOfNightsStartingDaysFromToday(int nights, int days) {
        stayDates.clear();
        for (int i = 0; i < nights; i++) {
            stayDates.add(LocalDate.now().plusDays(days + i));
        }
    }

    @When("the dynamic price is calculated")
    public void theDynamicPriceIsCalculated() {
        price = pricingService.calculateDynamicPricing(inventoryFor(stayDates.get(0)));
    }

    @When("the total price of the stay is calculated")
    public void theTotalPriceOfTheStayIsCalculated() {
        price = pricingService.calculateTotalPrice(stayDates.stream().map(this::inventoryFor).toList());
    }

    @Then("the price should be {bigdecimal}")
    public void thePriceShouldBe(BigDecimal expected) {
        assertThat(price).isEqualByComparingTo(expected);
    }

    private Inventory inventoryFor(LocalDate date) {
        return Inventory.builder()
                .room(room)
                .surgeFactor(surgeFactor)
                .bookedCount(booked)
                .reservedCount(reserved)
                .totalCount(10)
                .date(date)
                .build();
    }
}
