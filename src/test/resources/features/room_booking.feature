Feature: Room booking
  As a guest
  I want my booking to succeed only when every night is still available
  So that two guests can never be sold the same room

  Scenario: Booking succeeds when every night of the stay is available
    Given a logged-in guest
    And 3 of the 3 nights in the stay are still available
    When the guest books 2 rooms for the stay
    Then the booking is saved with status RESERVED
    And the rooms are held in inventory

  Scenario: Booking is rejected when another guest took one of the nights
    Given a logged-in guest
    And 2 of the 3 nights in the stay are still available
    When the guest books 1 room for the stay
    Then the booking is rejected with "Room is not available anymore"
    And no rooms are held in inventory

  Scenario: Only confirmed bookings can be cancelled
    Given a logged-in guest
    And the guest has a booking with status PAYMENT_PENDING
    When the guest cancels the booking
    Then the booking is rejected with "Only confirmed bookings can be cancelled"
    And no rooms are released from inventory
