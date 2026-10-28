package com.adobe.support.scrlockdump.core.lockdump;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delayed component (it provides a service and is not immediate) whose {@code activate()} sleeps
 * longer than the SCR lock timeout ({@code ds.lock.timeout.milliseconds}, default 5000 ms).
 * <p>
 * Felix SCR creates and activates a delayed component inside
 * {@code SingleComponentManager.getServiceInternal()} while holding the component's state lock.
 * Any other consumer bundle calling {@code getService()} for this service during that time waits
 * in {@code AbstractComponentManager.obtainLock()}; after the timeout SCR calls
 * {@code dumpThreads()} (DEBUG only) and throws {@code IllegalStateException("Could not obtain lock")}.
 * <p>
 * The SCR thread dump is logged with this component's logger, whose name is this implementation
 * class name: {@code com.adobe.support.scrlockdump.core.lockdump.SlowActivationServiceImpl}.
 */
@Component(service = SlowActivationService.class)
@Designate(ocd = SlowActivationServiceImpl.Config.class)
public class SlowActivationServiceImpl implements SlowActivationService {

    @ObjectClassDefinition(
            name = "SCR Lock Dump Test - Slow Activation Service",
            description = "Delayed component whose activate() sleeps longer than the SCR lock timeout.")
    public @interface Config {

        @AttributeDefinition(
                name = "Activation delay (ms)",
                description = "How long activate() sleeps. Must be longer than the SCR lock timeout "
                        + "(ds.lock.timeout.milliseconds, default 5000) plus the runner's waiter start delay.")
        long activation_delay_ms() default 15000L;
    }

    private static final Logger LOG = LoggerFactory.getLogger(SlowActivationServiceImpl.class);

    private volatile long activatedAt;

    @Activate
    protected void activate(final Config config) {
        final long delay = Math.max(0L, config.activation_delay_ms());
        final long start = System.currentTimeMillis();
        LOG.info("activate() START on thread [{}]; sleeping {} ms while SCR holds this component's state lock",
                Thread.currentThread().getName(), delay);
        try {
            Thread.sleep(delay);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("activate() interrupted on thread [{}] after {} ms",
                    Thread.currentThread().getName(), System.currentTimeMillis() - start);
        }
        activatedAt = System.currentTimeMillis();
        LOG.info("activate() END on thread [{}] after {} ms",
                Thread.currentThread().getName(), activatedAt - start);
    }

    @Deactivate
    protected void deactivate() {
        LOG.info("deactivate() on thread [{}]", Thread.currentThread().getName());
    }

    @Override
    public String describe() {
        return "SlowActivationServiceImpl activated at " + activatedAt;
    }
}
