package com.rupeek.hotelbooking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point.
 *
 * <p>Scheduling is enabled for one reason: the hold-expiry sweeper. See
 * {@code HoldExpirySweeper} for why an unpaid booking cannot be allowed to hold rooms forever.
 */
@SpringBootApplication
@EnableScheduling
public class HotelBookingApplication {

    public static void main(String[] args) {
        SpringApplication.run(HotelBookingApplication.class, args);
    }
}
