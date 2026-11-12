package io.github.themusicianone.ticketing_backend.hold;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "app.holds.strategy=conditional")
class ConditionalUpdateRaceTest extends HoldRaceTest {

    @Override
    protected Class<? extends SeatHoldStrategy> expectedStrategy() {
        return ConditionalUpdateStrategy.class;
    }
}