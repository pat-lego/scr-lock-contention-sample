package com.adobe.support.scrlockdump.core.lockdump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

@ExtendWith({AemContextExtension.class, MockitoExtension.class})
class ScrLockDumpRunServletTest {

    @Mock
    private ScrLockContentionRunner runner;

    @InjectMocks
    private ScrLockDumpRunServlet servlet;

    @Test
    void startsRun(final AemContext context) throws Exception {
        when(runner.start(anyString())).thenReturn(true);
        final MockSlingHttpServletResponse response = context.response();

        servlet.doPost(context.request(), response);

        assertEquals(202, response.getStatus());
    }

    @Test
    void rejectsConcurrentRun(final AemContext context) throws Exception {
        when(runner.start(anyString())).thenReturn(false);
        final MockSlingHttpServletResponse response = context.response();

        servlet.doPost(context.request(), response);

        assertEquals(409, response.getStatus());
    }
}
