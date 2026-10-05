package com.smit.projects.stayGrid.service.impl;

import com.smit.projects.stayGrid.dto.BookingRequest;
import com.smit.projects.stayGrid.dto.HotelReportDto;
import com.smit.projects.stayGrid.entity.*;
import com.smit.projects.stayGrid.entity.enums.BookingStatus;
import com.smit.projects.stayGrid.exception.ResourceNotFoundException;
import com.smit.projects.stayGrid.exception.UnAuthorizedException;
import com.smit.projects.stayGrid.repository.*;
import com.smit.projects.stayGrid.service.CheckoutService;
import com.smit.projects.stayGrid.strategy.PricingService;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.param.RefundCreateParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.modelmapper.ModelMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceImplTest {

    private static final String FRONTEND_URL = "https://frontend.test";

    @Mock private GuestRepository guestRepository;
    @Mock private ModelMapper modelMapper;
    @Mock private BookingRepository bookingRepository;
    @Mock private HotelRepository hotelRepository;
    @Mock private RoomRepository roomRepository;
    @Mock private InventoryRepository inventoryRepository;
    @Mock private CheckoutService checkoutService;
    @Mock private PricingService pricingService;

    @InjectMocks private BookingServiceImpl bookingService;

    private User currentUser;
    private Hotel hotel;
    private Room room;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(bookingService, "frontendUrl", FRONTEND_URL);

        currentUser = user(1L);
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken(currentUser, null));

        hotel = new Hotel();
        hotel.setId(10L);
        hotel.setOwner(currentUser);

        room = new Room();
        room.setId(20L);
        room.setHotel(hotel);
        room.setBasePrice(new BigDecimal("1000.00"));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---------- helpers ----------

    private static User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setEmail("user" + id + "@test.com");
        return user;
    }

    private Booking booking(Long id, BookingStatus status, User owner, LocalDateTime createdAt) {
        return Booking.builder()
                .id(id)
                .hotel(hotel)
                .room(room)
                .user(owner)
                .roomsCount(2)
                .checkInDate(LocalDate.now().plusDays(10))
                .checkOutDate(LocalDate.now().plusDays(12))
                .bookingStatus(status)
                .createdAt(createdAt)
                .guests(new HashSet<>())
                .amount(new BigDecimal("6000.00"))
                .paymentSessionId("cs_test_123")
                .build();
    }

    private BookingRequest request(int nights, int roomsCount) {
        LocalDate checkIn = LocalDate.now().plusDays(10);
        return BookingRequest.builder()
                .hotelId(hotel.getId())
                .roomId(room.getId())
                .checkInDate(checkIn)
                .checkOutDate(checkIn.plusDays(nights - 1L))
                .roomsCount(roomsCount)
                .build();
    }

    private static List<Inventory> inventoryRows(int count) {
        List<Inventory> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) rows.add(new Inventory());
        return rows;
    }

    // ---------- initialiseBooking ----------

    @Nested
    @DisplayName("initialiseBooking")
    class InitialiseBooking {

        @Test
        @DisplayName("locks inventory, reserves rooms and saves a RESERVED booking priced for all rooms")
        void reservesAndPricesBooking() {
            BookingRequest req = request(3, 2);
            List<Inventory> locked = inventoryRows(3);

            when(hotelRepository.findById(hotel.getId())).thenReturn(Optional.of(hotel));
            when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));
            when(inventoryRepository.findAndLockAvailableInventory(room.getId(),
                    req.getCheckInDate(), req.getCheckOutDate(), 2)).thenReturn(locked);
            when(pricingService.calculateTotalPrice(locked)).thenReturn(new BigDecimal("3000.00"));
            when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

            bookingService.initialiseBooking(req);

            InOrder order = inOrder(inventoryRepository);
            order.verify(inventoryRepository).findAndLockAvailableInventory(room.getId(),
                    req.getCheckInDate(), req.getCheckOutDate(), 2);
            order.verify(inventoryRepository).initBooking(room.getId(),
                    req.getCheckInDate(), req.getCheckOutDate(), 2);

            ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
            verify(bookingRepository).save(saved.capture());
            assertThat(saved.getValue().getBookingStatus()).isEqualTo(BookingStatus.RESERVED);
            assertThat(saved.getValue().getUser()).isEqualTo(currentUser);
            // 3000 for one room across the stay x 2 rooms
            assertThat(saved.getValue().getAmount()).isEqualByComparingTo("6000.00");
        }

        @Test
        @DisplayName("rejects the booking and reserves nothing when any night is no longer available")
        void preventsDoubleBookingWhenInventoryMissing() {
            BookingRequest req = request(3, 1);

            when(hotelRepository.findById(hotel.getId())).thenReturn(Optional.of(hotel));
            when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));
            // Only 2 of the 3 nights could be locked: another request took the last room
            when(inventoryRepository.findAndLockAvailableInventory(anyLong(), any(), any(), anyInt()))
                    .thenReturn(inventoryRows(2));

            assertThatThrownBy(() -> bookingService.initialiseBooking(req))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not available");

            verify(inventoryRepository, never()).initBooking(anyLong(), any(), any(), anyInt());
            verify(bookingRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws when the hotel does not exist, before touching inventory")
        void unknownHotel() {
            when(hotelRepository.findById(hotel.getId())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bookingService.initialiseBooking(request(2, 1)))
                    .isInstanceOf(ResourceNotFoundException.class);

            verifyNoInteractions(inventoryRepository);
        }
    }

    // ---------- addGuests ----------

    @Nested
    @DisplayName("addGuests")
    class AddGuests {

        @Test
        @DisplayName("attaches guests and moves booking to GUESTS_ADDED")
        void addsGuests() {
            Booking booking = booking(5L, BookingStatus.RESERVED, currentUser, LocalDateTime.now());
            Guest g1 = new Guest();
            g1.setId(100L);
            Guest g2 = new Guest();
            g2.setId(101L);

            when(bookingRepository.findById(5L)).thenReturn(Optional.of(booking));
            when(guestRepository.findById(100L)).thenReturn(Optional.of(g1));
            when(guestRepository.findById(101L)).thenReturn(Optional.of(g2));

            bookingService.addGuests(5L, List.of(100L, 101L));

            assertThat(booking.getGuests()).containsExactlyInAnyOrder(g1, g2);
            assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.GUESTS_ADDED);
            verify(bookingRepository).save(booking);
        }

        @Test
        @DisplayName("rejects a user who does not own the booking")
        void rejectsOtherUser() {
            Booking booking = booking(5L, BookingStatus.RESERVED, user(99L), LocalDateTime.now());
            when(bookingRepository.findById(5L)).thenReturn(Optional.of(booking));

            assertThatThrownBy(() -> bookingService.addGuests(5L, List.of(100L)))
                    .isInstanceOf(UnAuthorizedException.class);

            verify(bookingRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects a booking older than the 10-minute hold")
        void rejectsExpiredBooking() {
            Booking booking = booking(5L, BookingStatus.RESERVED, currentUser,
                    LocalDateTime.now().minusMinutes(11));
            when(bookingRepository.findById(5L)).thenReturn(Optional.of(booking));

            assertThatThrownBy(() -> bookingService.addGuests(5L, List.of(100L)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("expired");
        }

        @Test
        @DisplayName("rejects a booking that is not in RESERVED state")
        void rejectsWrongState() {
            Booking booking = booking(5L, BookingStatus.CONFIRMED, currentUser, LocalDateTime.now());
            when(bookingRepository.findById(5L)).thenReturn(Optional.of(booking));

            assertThatThrownBy(() -> bookingService.addGuests(5L, List.of(100L)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("reserved state");
        }
    }

    // ---------- initiatePayments ----------

    @Nested
    @DisplayName("initiatePayments")
    class InitiatePayments {

        @Test
        @DisplayName("creates a checkout session and moves booking to PAYMENT_PENDING")
        void createsCheckoutSession() {
            Booking booking = booking(5L, BookingStatus.GUESTS_ADDED, currentUser, LocalDateTime.now());
            String statusUrl = FRONTEND_URL + "/payments/5/status";

            when(bookingRepository.findById(5L)).thenReturn(Optional.of(booking));
            when(checkoutService.getCheckoutSession(booking, statusUrl, statusUrl))
                    .thenReturn("https://checkout.stripe.test/session");

            String url = bookingService.initiatePayments(5L);

            assertThat(url).isEqualTo("https://checkout.stripe.test/session");
            assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.PAYMENT_PENDING);
            verify(bookingRepository).save(booking);
        }

        @Test
        @DisplayName("does not create a session for an expired booking")
        void rejectsExpiredBooking() {
            Booking booking = booking(5L, BookingStatus.GUESTS_ADDED, currentUser,
                    LocalDateTime.now().minusMinutes(15));
            when(bookingRepository.findById(5L)).thenReturn(Optional.of(booking));

            assertThatThrownBy(() -> bookingService.initiatePayments(5L))
                    .isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(checkoutService);
        }
    }

    // ---------- capturePayment (Stripe webhook event) ----------

    @Nested
    @DisplayName("capturePayment")
    class CapturePayment {

        private Event completedCheckoutEvent(String sessionId) {
            Session session = mock(Session.class);
            when(session.getId()).thenReturn(sessionId);

            EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
            when(deserializer.getObject()).thenReturn(Optional.of(session));

            Event event = mock(Event.class);
            when(event.getType()).thenReturn("checkout.session.completed");
            when(event.getDataObjectDeserializer()).thenReturn(deserializer);
            return event;
        }

        @Test
        @DisplayName("confirms the booking and moves reserved inventory to booked, under lock")
        void confirmsBooking() {
            Booking booking = booking(5L, BookingStatus.PAYMENT_PENDING, currentUser, LocalDateTime.now());
            when(bookingRepository.findByPaymentSessionId("cs_test_123")).thenReturn(Optional.of(booking));

            bookingService.capturePayment(completedCheckoutEvent("cs_test_123"));

            assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
            verify(bookingRepository).save(booking);

            InOrder order = inOrder(inventoryRepository);
            order.verify(inventoryRepository).findAndLockReservedInventory(room.getId(),
                    booking.getCheckInDate(), booking.getCheckOutDate(), 2);
            order.verify(inventoryRepository).confirmBooking(room.getId(),
                    booking.getCheckInDate(), booking.getCheckOutDate(), 2);
        }

        @Test
        @DisplayName("throws when no booking matches the checkout session")
        void unknownSession() {
            when(bookingRepository.findByPaymentSessionId("cs_unknown")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bookingService.capturePayment(completedCheckoutEvent("cs_unknown")))
                    .isInstanceOf(ResourceNotFoundException.class);

            verifyNoInteractions(inventoryRepository);
        }

        @Test
        @DisplayName("ignores event types other than checkout.session.completed")
        void ignoresOtherEvents() {
            Event event = mock(Event.class);
            when(event.getType()).thenReturn("payment_intent.created");

            bookingService.capturePayment(event);

            verifyNoInteractions(bookingRepository, inventoryRepository);
        }
    }

    // ---------- cancelBooking ----------

    @Nested
    @DisplayName("cancelBooking")
    class CancelBooking {

        @Test
        @DisplayName("cancels a confirmed booking, releases inventory and refunds the payment")
        void cancelsAndRefunds() {
            Booking booking = booking(5L, BookingStatus.CONFIRMED, currentUser, LocalDateTime.now());
            when(bookingRepository.findById(5L)).thenReturn(Optional.of(booking));

            Session session = mock(Session.class);
            when(session.getPaymentIntent()).thenReturn("pi_test_456");

            try (MockedStatic<Session> sessionStatic = mockStatic(Session.class);
                 MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {

                sessionStatic.when(() -> Session.retrieve("cs_test_123")).thenReturn(session);

                bookingService.cancelBooking(5L);

                ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
                refundStatic.verify(() -> Refund.create(params.capture()));
                assertThat(params.getValue().getPaymentIntent()).isEqualTo("pi_test_456");
            }

            assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.CANCELLED);
            verify(inventoryRepository).cancelBooking(room.getId(),
                    booking.getCheckInDate(), booking.getCheckOutDate(), 2);
        }

        @Test
        @DisplayName("refuses to cancel a booking that is not confirmed")
        void rejectsUnconfirmedBooking() {
            Booking booking = booking(5L, BookingStatus.PAYMENT_PENDING, currentUser, LocalDateTime.now());
            when(bookingRepository.findById(5L)).thenReturn(Optional.of(booking));

            assertThatThrownBy(() -> bookingService.cancelBooking(5L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only confirmed bookings");

            verifyNoInteractions(inventoryRepository);
        }

        @Test
        @DisplayName("refuses to cancel another user's booking")
        void rejectsOtherUser() {
            Booking booking = booking(5L, BookingStatus.CONFIRMED, user(99L), LocalDateTime.now());
            when(bookingRepository.findById(5L)).thenReturn(Optional.of(booking));

            assertThatThrownBy(() -> bookingService.cancelBooking(5L))
                    .isInstanceOf(UnAuthorizedException.class);

            verifyNoInteractions(inventoryRepository);
        }
    }

    // ---------- getHotelReport ----------

    @Nested
    @DisplayName("getHotelReport")
    class HotelReport {

        @Test
        @DisplayName("counts only confirmed bookings in revenue and average")
        void reportsConfirmedRevenueOnly() {
            Booking confirmed1 = booking(1L, BookingStatus.CONFIRMED, currentUser, LocalDateTime.now());
            confirmed1.setAmount(new BigDecimal("3000.00"));
            Booking confirmed2 = booking(2L, BookingStatus.CONFIRMED, currentUser, LocalDateTime.now());
            confirmed2.setAmount(new BigDecimal("5000.00"));
            Booking cancelled = booking(3L, BookingStatus.CANCELLED, currentUser, LocalDateTime.now());
            cancelled.setAmount(new BigDecimal("9999.00"));

            when(hotelRepository.findById(hotel.getId())).thenReturn(Optional.of(hotel));
            when(bookingRepository.findByHotelAndCreatedAtBetween(eq(hotel), any(), any()))
                    .thenReturn(List.of(confirmed1, confirmed2, cancelled));

            HotelReportDto report = bookingService.getHotelReport(hotel.getId(),
                    LocalDate.now().minusDays(30), LocalDate.now());

            assertThat(report.getBookingCount()).isEqualTo(2L);
            assertThat(report.getTotalRevenue()).isEqualByComparingTo("8000.00");
            assertThat(report.getAvgRevenue()).isEqualByComparingTo("4000.00");
        }

        @Test
        @DisplayName("returns zero average when there are no confirmed bookings")
        void zeroAverageWhenNoBookings() {
            when(hotelRepository.findById(hotel.getId())).thenReturn(Optional.of(hotel));
            when(bookingRepository.findByHotelAndCreatedAtBetween(eq(hotel), any(), any()))
                    .thenReturn(List.of());

            HotelReportDto report = bookingService.getHotelReport(hotel.getId(),
                    LocalDate.now().minusDays(30), LocalDate.now());

            assertThat(report.getBookingCount()).isZero();
            assertThat(report.getAvgRevenue()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("denies the report to a user who does not own the hotel")
        void deniesNonOwner() {
            hotel.setOwner(user(99L));
            when(hotelRepository.findById(hotel.getId())).thenReturn(Optional.of(hotel));

            assertThatThrownBy(() -> bookingService.getHotelReport(hotel.getId(),
                    LocalDate.now().minusDays(30), LocalDate.now()))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }
}
