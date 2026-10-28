package com.adobe.support.scrlockdump.core.lockdump;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class SlowActivationServiceImplTest {

    @Test
    void activateSleepsForConfiguredDelay() {
        final SlowActivationServiceImpl.Config config = mock(SlowActivationServiceImpl.Config.class);
        when(config.activation_delay_ms()).thenReturn(200L);
        final SlowActivationServiceImpl service = new SlowActivationServiceImpl();

        final long start = System.currentTimeMillis();
        service.activate(config);
        final long took = System.currentTimeMillis() - start;

        assertTrue(took >= 200L, "activate() returned after " + took + " ms");
        assertNotEquals("SlowActivationServiceImpl activated at 0", service.describe());
    }
}
