package io.github.themusicianone.ticketing_backend.hold;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "app.holds.strategy=optimistic")
class OptimisticLockingRaceTest extends HoldRaceTest {

    @Override
    protected Class<? extends SeatHoldStrategy> expectedStrategy() {
        return OptimisticLockingStrategy.class;
    }
}