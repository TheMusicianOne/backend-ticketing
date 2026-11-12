package io.github.themusicianone.ticketing_backend.hold;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "app.holds.strategy=pessimistic")
class PessimisticLockingRaceTest extends HoldRaceTest {

    @Override
    protected Class<? extends SeatHoldStrategy> expectedStrategy() {
        return PessimisticLockingStrategy.class;
    }
}