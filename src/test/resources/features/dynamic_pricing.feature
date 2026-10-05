Feature: Dynamic room pricing
  As a hotel manager
  I want room prices to react to demand, urgency and surge settings
  So that revenue follows real booking conditions

  Background:
    Given a room with a base price of 1000.00

  Scenario Outline: Price for one night depends on surge, occupancy and urgency
    Given a surge factor of <surge>
    And <booked> of <reserved> reserved rooms are already booked
    And the stay date is <days> days from today
    When the dynamic price is calculated
    Then the price should be <price>

    Examples:
      | surge | booked | reserved | days | price   |
      | 1.0   | 0      | 1        | 30   | 1250.00 |
      | 1.5   | 0      | 1        | 30   | 1875.00 |
      | 1.0   | 9      | 10       | 30   | 1500.00 |
      | 1.0   | 0      | 1        | 3    | 1437.50 |
      | 1.2   | 9      | 10       | 2    | 2070.00 |

  Scenario: Total price of a stay is the sum of each night
    Given a surge factor of 1.0
    And 0 of 1 reserved rooms are already booked
    And a stay of 3 nights starting 30 days from today
    When the total price of the stay is calculated
    Then the price should be 3750.00
