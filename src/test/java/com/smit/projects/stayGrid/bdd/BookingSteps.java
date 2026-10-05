package com.smit.projects.stayGrid.bdd;

import com.smit.projects.stayGrid.dto.BookingRequest;
import com.smit.projects.stayGrid.entity.*;
import com.smit.projects.stayGrid.entity.enums.BookingStatus;
import com.smit.projects.stayGrid.repository.*;
import com.smit.projects.stayGrid.service.CheckoutService;
import com.smit.projects.stayGrid.service.impl.BookingServiceImpl;
import com.smit.projects.stayGrid.strategy.PricingService;
import io.cucumber.java.After;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.mockito.ArgumentCaptor;
import org.modelmapper.ModelMapper;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class BookingSteps {

    private static final int STAY_NIGHTS = 3;

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final HotelRepository hotelRepository = mock(HotelRepository.class);
    private final RoomRepository roomRepository = mock(RoomRepository.class);
    private final InventoryRepository inventoryRepository = mock(InventoryRepository.class);
    private final PricingService pricingService = mock(PricingService.class);

    private final BookingServiceImpl bookingService = new BookingServiceImpl(
            mock(GuestRepository.class), mock(ModelMapper.class), bookingRepository,
            hotelRepository, roomRepository, inventoryRepository,
            mock(CheckoutService.class), pricingService);

    private final Hotel hotel = new Hotel();
    private final Room room = new Room();
    private User guest;
    private Booking existingBooking;
    private Exception error;

    @Given("a logged-in guest")
    public void aLoggedInGuest() {
        guest = new User();
        guest.setId(1L);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(guest, null));

        hotel.setId(10L);
        room.setId(20L);
        room.setHotel(hotel);
        when(hotelRepository.findById(10L)).thenReturn(Optional.of(hotel));
        when(roomRepository.findById(20L)).thenReturn(Optional.of(room));
    }

    @And("{int} of the {int} nights in the stay are still available")
    public void nightsAreStillAvailable(int available, int nights) {
        List<Inventory> lockedRows = new ArrayList<>();
        for (int i = 0; i < available; i++) lockedRows.add(new Inventory());

        when(inventoryRepository.findAndLockAvailableInventory(anyLong(), any(), any(), anyInt()))
                .thenReturn(lockedRows);
        when(pricingService.calculateTotalPrice(lockedRows)).thenReturn(new BigDecimal("3000.00"));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @And("the guest has a booking with status {word}")
    public void theGuestHasABookingWithStatus(String status) {
        existingBooking = Booking.builder()
                .id(5L).hotel(hotel).room(room).user(guest).roomsCount(1)
                .checkInDate(LocalDate.now().plusDays(10))
                .checkOutDate(LocalDate.now().plusDays(12))
                .bookingStatus(BookingStatus.valueOf(status))
                .createdAt(LocalDateTime.now())
                .guests(new HashSet<>())
                .amount(new BigDecimal("3000.00"))
                .build();
        when(bookingRepository.findById(5L)).thenReturn(Optional.of(existingBooking));
    }

    @When("the guest books {int} room(s) for the stay")
    public void theGuestBooksRoomsForTheStay(int roomsCount) {
        LocalDate checkIn = LocalDate.now().plusDays(10);
        BookingRequest request = BookingRequest.builder()
                .hotelId(10L).roomId(20L)
                .checkInDate(checkIn)
                .checkOutDate(checkIn.plusDays(STAY_NIGHTS - 1))
                .roomsCount(roomsCount)
                .build();
        try {
            bookingService.initialiseBooking(request);
        } catch (Exception e) {
            error = e;
        }
    }

    @When("the guest cancels the booking")
    public void theGuestCancelsTheBooking() {
        try {
            bookingService.cancelBooking(existingBooking.getId());
        } catch (Exception e) {
            error = e;
        }
    }

    @Then("the booking is saved with status {word}")
    public void theBookingIsSavedWithStatus(String status) {
        assertThat(error).isNull();
        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(saved.capture());
        assertThat(saved.getValue().getBookingStatus()).isEqualTo(BookingStatus.valueOf(status));
    }

    @Then("the booking is rejected with {string}")
    public void theBookingIsRejectedWith(String message) {
        assertThat(error).isInstanceOf(IllegalStateException.class).hasMessage(message);
        verify(bookingRepository, never()).save(any());
    }

    @And("the rooms are held in inventory")
    public void theRoomsAreHeldInInventory() {
        verify(inventoryRepository).initBooking(eq(20L), any(), any(), anyInt());
    }

    @And("no rooms are held in inventory")
    public void noRoomsAreHeldInInventory() {
        verify(inventoryRepository, never()).initBooking(anyLong(), any(), any(), anyInt());
    }

    @And("no rooms are released from inventory")
    public void noRoomsAreReleasedFromInventory() {
        verify(inventoryRepository, never()).cancelBooking(anyLong(), any(), any(), anyInt());
    }

    @After
    public void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }
}
