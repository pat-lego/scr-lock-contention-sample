package com.adobe.support.scrlockdump.core.lockdump;

import java.io.IOException;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * {@code POST /bin/scrlockdump/run} starts another contention run.
 * Only available where {@link ScrLockContentionRunner} is configured (author in this project).
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=" + ScrLockDumpRunServlet.PATH,
        "sling.servlet.methods=POST"
})
public class ScrLockDumpRunServlet extends SlingAllMethodsServlet {

    static final String PATH = "/bin/scrlockdump/run";

    private static final long serialVersionUID = 1L;

    @Reference
    private transient ScrLockContentionRunner runner;

    @Override
    protected void doPost(final SlingHttpServletRequest request, final SlingHttpServletResponse response)
            throws IOException {
        final boolean started = runner.start("POST " + PATH + " by " + request.getResourceResolver().getUserID());
        response.setContentType("text/plain");
        response.setCharacterEncoding("UTF-8");
        if (started) {
            response.setStatus(HttpServletResponse.SC_ACCEPTED);
            response.getWriter().write("Run started. Check error.log in about 20 seconds.\n");
        } else {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            response.getWriter().write("A run is already in progress.\n");
        }
    }
}
