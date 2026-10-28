package com.adobe.support.scrlockdump.core.lockdump;

import java.util.concurrent.atomic.AtomicBoolean;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.runtime.ServiceComponentRuntime;
import org.osgi.service.component.runtime.dto.ComponentDescriptionDTO;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forces two threads to request {@link SlowActivationService} at the same time from two different
 * consumer bundles, so the second one hits the SCR state-lock timeout.
 * <ol>
 * <li>{@value #HOLDER_THREAD} calls {@code getService()} as the system bundle. SCR takes the
 * component state lock and runs the slow {@code activate()}.</li>
 * <li>{@value #WAITER_THREAD} calls {@code getService()} as this bundle a moment later. It waits for
 * the same state lock, times out, SCR logs its thread dump at DEBUG and getService() returns null.</li>
 * </ol>
 * Two different consumer bundles are required: for the same consumer bundle, the Felix framework
 * makes the second caller wait on its own latch in {@code ServiceRegistry.getService()} and it never
 * reaches the SCR lock.
 * <p>
 * Only active when a configuration exists ({@code configurationPolicy = REQUIRE}).
 */
@Component(service = ScrLockContentionRunner.class, immediate = true,
        configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd = ScrLockContentionRunner.Config.class)
public class ScrLockContentionRunner {

    static final String DRIVER_THREAD = "scr-lock-test-driver";
    static final String HOLDER_THREAD = "scr-lock-test-holder";
    static final String WAITER_THREAD = "scr-lock-test-waiter";

    @ObjectClassDefinition(
            name = "SCR Lock Dump Test - Contention Runner",
            description = "Reproduces an SCR 'Could not obtain lock' timeout on SlowActivationServiceImpl.")
    public @interface Config {

        @AttributeDefinition(name = "Run on activate",
                description = "Start one run when this component activates (for example at instance startup).")
        boolean run_on_activate() default true;

        @AttributeDefinition(name = "Waiter start delay (ms)",
                description = "How long after the holder thread starts the waiter thread calls getService().")
        long waiter_start_delay_ms() default 1000L;

        @AttributeDefinition(name = "Service wait (ms)",
                description = "How long to wait for SlowActivationService to be registered after it is re-enabled.")
        long service_wait_ms() default 30000L;
    }

    private static final Logger LOG = LoggerFactory.getLogger(ScrLockContentionRunner.class);

    private final AtomicBoolean running = new AtomicBoolean();

    @Reference
    private ServiceComponentRuntime scr;

    private BundleContext bundleContext;

    private Config config;

    @Activate
    protected void activate(final BundleContext bundleContext, final Config config) {
        this.bundleContext = bundleContext;
        this.config = config;
        if (config.run_on_activate()) {
            start("component activation");
        }
    }

    /**
     * Starts one contention run in the background.
     *
     * @param reason free text written to the log
     * @return {@code false} if a run is already in progress
     */
    public boolean start(final String reason) {
        if (!running.compareAndSet(false, true)) {
            LOG.warn("A run is already in progress; ignoring start request ({})", reason);
            return false;
        }
        final Thread driver = new Thread(() -> {
            try {
                runOnce(reason);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warn("Run interrupted", e);
            } catch (final Exception e) {
                LOG.error("Run failed", e);
            } finally {
                running.set(false);
            }
        }, DRIVER_THREAD);
        driver.setDaemon(true);
        driver.start();
        return true;
    }

    private void runOnce(final String reason) throws Exception {
        LOG.info("Run started ({}). Resetting {} so the next getService() runs activate() again",
                reason, SlowActivationServiceImpl.class.getName());
        resetSlowComponent();

        final ServiceReference<SlowActivationService> ref = awaitServiceReference();
        final BundleContext holderContext =
                bundleContext.getBundle(Constants.SYSTEM_BUNDLE_ID).getBundleContext();

        final Thread holder = new Thread(() -> consume("holder", holderContext, ref), HOLDER_THREAD);
        final Thread waiter = new Thread(() -> consume("waiter", bundleContext, ref), WAITER_THREAD);
        holder.setDaemon(true);
        waiter.setDaemon(true);

        holder.start();
        Thread.sleep(Math.max(0L, config.waiter_start_delay_ms()));
        waiter.start();

        holder.join();
        waiter.join();
        LOG.info("Run finished. Search error.log for 'Could not obtain lock' and for the DEBUG 'Thread dump' entry "
                + "logged by {}", SlowActivationServiceImpl.class.getName());
    }

    /** Disable and re-enable the slow component so it is not already active from a previous run. */
    private void resetSlowComponent() throws Exception {
        final ComponentDescriptionDTO description = scr.getComponentDescriptionDTO(
                bundleContext.getBundle(), SlowActivationServiceImpl.class.getName());
        if (description == null) {
            throw new IllegalStateException("Component not found: " + SlowActivationServiceImpl.class.getName());
        }
        if (scr.isComponentEnabled(description)) {
            scr.disableComponent(description).getValue();
        }
        scr.enableComponent(description).getValue();
    }

    private ServiceReference<SlowActivationService> awaitServiceReference() throws InterruptedException {
        final long deadline = System.currentTimeMillis() + Math.max(0L, config.service_wait_ms());
        while (true) {
            final ServiceReference<SlowActivationService> ref =
                    bundleContext.getServiceReference(SlowActivationService.class);
            if (ref != null) {
                return ref;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("SlowActivationService was not registered within "
                        + config.service_wait_ms() + " ms");
            }
            Thread.sleep(100L);
        }
    }

    private void consume(final String role, final BundleContext consumer,
            final ServiceReference<SlowActivationService> ref) {
        final Bundle bundle = consumer.getBundle();
        final String consumerName = bundle.getSymbolicName() + " (" + bundle.getBundleId() + ")";
        final long start = System.currentTimeMillis();
        LOG.info("[{}] getService() START on thread [{}] as consumer bundle {}",
                role, Thread.currentThread().getName(), consumerName);
        final SlowActivationService service;
        try {
            service = consumer.getService(ref);
        } catch (final RuntimeException e) {
            LOG.warn("[{}] getService() threw after {} ms", role, System.currentTimeMillis() - start, e);
            return;
        }
        final long took = System.currentTimeMillis() - start;
        if (service == null) {
            LOG.warn("[{}] getService() returned null after {} ms (expected for the waiter: SCR could not obtain "
                    + "the component state lock)", role, took);
            return;
        }
        try {
            LOG.info("[{}] getService() returned after {} ms: {}", role, took, service.describe());
        } finally {
            consumer.ungetService(ref);
        }
    }
}
