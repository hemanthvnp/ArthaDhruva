package com.arthadhruva.riskengine.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortfolioRunSupervisorTest {

    @Test
    void anEmptyWindowIsAlwaysOpen() {
        assertTrue(PortfolioRunSupervisor.inWindow("", 14));
        assertTrue(PortfolioRunSupervisor.inWindow(null, 3));
    }

    @Test
    void aWindowIncludesItsStartAndExcludesItsEnd() {
        assertFalse(PortfolioRunSupervisor.inWindow("1-5", 0));
        assertTrue(PortfolioRunSupervisor.inWindow("1-5", 1));
        assertTrue(PortfolioRunSupervisor.inWindow("1-5", 4));
        assertFalse(PortfolioRunSupervisor.inWindow("1-5", 5));
    }

    @Test
    void aWindowMayWrapMidnight() {
        assertTrue(PortfolioRunSupervisor.inWindow("22-6", 23));
        assertTrue(PortfolioRunSupervisor.inWindow("22-6", 0));
        assertTrue(PortfolioRunSupervisor.inWindow(" 22 - 6 ", 5));
        assertFalse(PortfolioRunSupervisor.inWindow("22-6", 6));
        assertFalse(PortfolioRunSupervisor.inWindow("22-6", 14));
    }

    @Test
    void aMalformedWindowIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> PortfolioRunSupervisor.inWindow("night", 1));
        assertThrows(IllegalArgumentException.class, () -> PortfolioRunSupervisor.inWindow("22", 1));
        assertThrows(IllegalArgumentException.class, () -> PortfolioRunSupervisor.inWindow("22-6-8", 1));
        assertThrows(IllegalArgumentException.class, () -> PortfolioRunSupervisor.inWindow("25-6", 1));
    }
}
