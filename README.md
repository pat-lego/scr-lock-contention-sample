# SCR Lock Dump Test

Generated with `aem-project-archetype` 58 (`aemVersion=cloud`). It reproduces the Felix SCR error
`IllegalStateException: Could not obtain lock` on purpose and turns on the DEBUG logging that makes
SCR write a full thread dump just before it throws.

## What it does

All code is in `core/src/main/java/com/adobe/support/scrlockdump/core/lockdump/`.

| Class | Role |
|---|---|
| `SlowActivationServiceImpl` | Delayed component (provides `SlowActivationService`). `activate()` sleeps `activation.delay.ms` (15000) while SCR holds the component's state lock. |
| `ScrLockContentionRunner` | Author only (needs its config). Re-enables the slow component, then starts two threads that call `getService()` on it from two different consumer bundles. |
| `ScrLockDumpRunServlet` | `POST /bin/scrlockdump/run` starts another run. |

One run:

1. `scr-lock-test-holder` calls `getService()` as the **system bundle**. SCR takes the state lock and runs the slow `activate()`.
2. One second later `scr-lock-test-waiter` calls `getService()` as **this bundle**. It waits for the same lock in `AbstractComponentManager.obtainLock()`.
3. After `ds.lock.timeout.milliseconds` (default 5000) SCR calls `dumpThreads()` and throws `Could not obtain lock`. The waiter gets `null`.

Two different consumer bundles are needed. For the same consumer bundle, the Felix framework makes the
second caller wait on its own latch in `ServiceRegistry.getService()`, so it never reaches the SCR lock
([ServiceRegistry.java#L346-L396](https://github.com/apache/felix-dev/blob/org.apache.felix.framework-7.0.5/framework/src/main/java/org/apache/felix/framework/ServiceRegistry.java#L346-L396)).

## Logging configuration (`ui.config/.../osgiconfig/config/`)

SCR writes the dump only when the component's logger is at DEBUG
([AbstractComponentManager.java#L235-L243](https://github.com/apache/felix-dev/blob/org.apache.felix.scr-2.2.18/scr/src/main/java/org/apache/felix/scr/impl/manager/AbstractComponentManager.java#L235-L243),
[#L302-L315](https://github.com/apache/felix-dev/blob/org.apache.felix.scr-2.2.18/scr/src/main/java/org/apache/felix/scr/impl/manager/AbstractComponentManager.java#L302-L315)).
The logger name is the component's implementation class. Two gates must both be at DEBUG:

| File | Gate |
|---|---|
| `org.osgi.service.log.admin.cfg.json` | Felix Log (OSGi Log Admin, spec 101.4.1). Decides whether SCR builds and logs the dump at all. |
| `org.apache.sling.commons.log.LogManager.factory.config~scrlockdump-scr-threaddump.cfg.json` | Sling logging. Decides whether the entry reaches `logs/error.log`. |

Both files also list `org.apache.sling.rewriter.impl.ProcessorManagerImpl` and
`org.apache.sling.rewriter.impl.RewriterFilter`, so the real `RewriterFilter` → `ProcessorManager`
lock failure is captured too if it happens on the same instance. With these on, `ProcessorManagerImpl`
also logs `Found new processor configuration ...` from inside its `activate()`, which shows the thread
that activates it.

## Run it

Build (Java 21, as in `.cloudmanager/java-version`) and install on a local AEM SDK author:

```
mvn clean install -PautoInstallSinglePackage
```

If the package manager login fails, copying `all/target/scr-lock-dump-test.all-1.0.0-SNAPSHOT.zip`
into `crx-quickstart/install/` also installs it. Removing the file from that folder can uninstall it again.

A run starts when `ScrLockContentionRunner` activates (install and every startup). To run again:

```
curl -u admin:<password> -X POST -H "Referer: http://localhost:4502" http://localhost:4502/bin/scrlockdump/run
```

Then search `crx-quickstart/logs/error.log`:

```
grep -n -E "scr-lock-test|Could not obtain lock|: Thread dump" crx-quickstart/logs/error.log
```

Output from a local AEM SDK author (2026.8), 28 Oct 2026:

```
09:41:11.799 *INFO* [scr-lock-test-holder] ...SlowActivationServiceImpl activate() START on thread [scr-lock-test-holder]; sleeping 15000 ms ...
09:41:12.803 *INFO* [scr-lock-test-waiter] ...ScrLockContentionRunner [waiter] getService() START ... as consumer bundle scr-lock-dump-test.core (652)
09:41:17.878 *WARN* [scr-lock-test-waiter] ...ScrLockContentionRunner [waiter] getService() returned null after 5075 ms ...
09:41:17.878 *DEBUG* [FelixLogListener] com.adobe.support.scrlockdump.core.lockdump.SlowActivationServiceImpl bundle scr-lock-dump-test.core:1.0.0.SNAPSHOT (652)[...SlowActivationServiceImpl(4637)] : Thread dump___ThreadId: 9 ...
09:41:17.879 *ERROR* [FelixLogListener] Events.Framework.scr-lock-dump-test.core FrameworkEvent ERROR (... Service factory exception: Could not obtain lock)
09:41:26.804 *INFO* [scr-lock-test-holder] ...SlowActivationServiceImpl activate() END on thread [scr-lock-test-holder] after 15005 ms
```

### Reading the dump

AEM logging writes the whole dump on one line and replaces line breaks with `_`. Format it with:

```
python3 tools/format_scr_threaddump.py crx-quickstart/logs/error.log > threaddump-formatted.txt
```

It handles every `Thread dump` entry in the file. The output has a summary (thread states, the SCR lock
holder and waiter, groups of idle threads with the same stack), then the SCR-related threads, then all
threads in jstack style. Quick alternative without Python:

```
grep ': Thread dump' crx-quickstart/logs/error.log | perl -pe 's/__(?=ThreadId: )/\n\n/g; s/_  /\n  /g' > threaddump.txt
```

The dump does not name the owner of the component lock. `LockOwnerId` is `-1` for every thread, because
the waiting thread has already stopped waiting when it takes the dump, and SCR does not print locked
synchronizers ([ThreadDump.java](https://github.com/apache/felix-dev/blob/org.apache.felix.scr-2.2.18/scr/src/main/java/org/apache/felix/scr/impl/manager/ThreadDump.java)).
Find the holder by its stack instead: the thread inside
`SingleComponentManager.getServiceInternal` → `createComponent` for the same component. Here:

```
ThreadId: 972 : name: scr-lock-test-holder State: TIMED_WAITING
  com.adobe.support.scrlockdump.core.lockdump.SlowActivationServiceImpl.activate(SlowActivationServiceImpl.java:52)
  org.apache.felix.scr.impl.manager.SingleComponentManager.createComponent(SingleComponentManager.java:115)
  org.apache.felix.scr.impl.manager.SingleComponentManager.getServiceInternal(SingleComponentManager.java:974)
```

The thread that takes the dump is the waiter, inside `AbstractComponentManager.obtainLock` ←
`SingleComponentManager.getService(SingleComponentManager.java:907)`.

For the real `RewriterFilter` case, look for a thread whose stack contains `ProcessorManagerImpl.activate`
(or `FactoryCache`) under `SingleComponentManager.getServiceInternal`.

On AEM as a Cloud Service, deploy through Cloud Manager to a development environment. The runner and
the slow service configs are in `config.author`, so nothing runs on publish.

---

# Sample AEM project template

This is a project template for AEM-based applications. It is intended as a best-practice set of examples as well as a potential starting point to develop your own functionality.

## Modules

The main parts of the template are:

* [core:](core/README.md) Java bundle containing all core functionality like OSGi services, listeners or schedulers, as well as component-related Java code such as servlets or request filters.
* ui.config: contains runmode specific OSGi configs for the project
* all: a single content package that embeds all of the compiled modules (bundles and content packages) including any vendor dependencies
* analyse: this module runs analysis on the project which provides additional validation for deploying into AEMaaCS

## How to build

To build all the modules run in the project root directory the following command with Maven 3:

    mvn clean install

To build all the modules and deploy the `all` package to a local instance of AEM, run in the project root directory the following command:

    mvn clean install -PautoInstallSinglePackage

Or to deploy it to a publish instance, run

    mvn clean install -PautoInstallSinglePackagePublish

Or alternatively

    mvn clean install -PautoInstallSinglePackage -Daem.port=4503

Or to deploy only the bundle to the author, run

    mvn clean install -PautoInstallBundle

Or to deploy only a single content package, run in the sub-module directory (i.e `ui.apps`)

    mvn clean install -PautoInstallPackage

## Documentation

The build process also generates documentation in the form of README.md files in each module directory for easy reference. Depending on the options you select at build time, the content may be customized to your project.

## Testing

There are three levels of testing contained in the project:

### Unit tests

This show-cases classic unit testing of the code contained in the bundle. To
test, execute:

    mvn clean test

### Integration tests

This allows running integration tests that exercise the capabilities of AEM via
HTTP calls to its API. To run the integration tests, run:

    mvn clean verify -Plocal

Test classes must be saved in the `src/main/java` directory (or any of its
subdirectories), and must be contained in files matching the pattern `*IT.java`.

The configuration provides sensible defaults for a typical local installation of
AEM. If you want to point the integration tests to different AEM author and
publish instances, you can use the following system properties via Maven's `-D`
flag.

| Property              | Description                                         | Default value           |
|-----------------------|-----------------------------------------------------|-------------------------|
| `it.author.url`       | URL of the author instance                          | `http://localhost:4502` |
| `it.author.user`      | Admin user for the author instance                  | `admin`                 |
| `it.author.password`  | Password of the admin user for the author instance  | `admin`                 |
| `it.publish.url`      | URL of the publish instance                         | `http://localhost:4503` |
| `it.publish.user`     | Admin user for the publish instance                 | `admin`                 |
| `it.publish.password` | Password of the admin user for the publish instance | `admin`                 |

The integration tests in this archetype use the [AEM Testing
Clients](https://github.com/adobe/aem-testing-clients) and showcase some
recommended [best
practices](https://github.com/adobe/aem-testing-clients/wiki/Best-practices) to
be put in use when writing integration tests for AEM.

## Static Analysis

The `analyse` module performs static analysis on the project for deploying into AEMaaCS. It is automatically
run when executing

    mvn clean install

from the project root directory. Additional information about this analysis and how to further configure it
can be found here https://github.com/adobe/aemanalyser-maven-plugin

### UI tests

They will test the UI layer of your AEM application using Cypress framework.

Check README file in `ui.tests` module for more details.

Examples of UI tests in different frameworks can be found here: https://github.com/adobe/aem-test-samples

## ClientLibs

The frontend module is made available using an [AEM ClientLib](https://helpx.adobe.com/experience-manager/6-5/sites/developing/using/clientlibs.html). When executing the NPM build script, the app is built and the [`aem-clientlib-generator`](https://github.com/wcm-io-frontend/aem-clientlib-generator) package takes the resulting build output and transforms it into such a ClientLib.

A ClientLib will consist of the following files and directories:

- `css/`: CSS files which can be requested in the HTML
- `css.txt` (tells AEM the order and names of files in `css/` so they can be merged)
- `js/`: JavaScript files which can be requested in the HTML
- `js.txt` (tells AEM the order and names of files in `js/` so they can be merged
- `resources/`: Source maps, non-entrypoint code chunks (resulting from code splitting), static assets (e.g. icons), etc.

## Simplified Index Management

If you're building for AEM as a Cloud Service, this project includes a `diff.index` under
`ui.apps/src/main/content/jcr_root/_oak_index` ([documentation](https://oak-indexing.github.io/oakTools/simplified.html)).
Packaging it triggers a benign Maven build warning from the `jackrabbit-nodetypes` validator
(`... is not allowed as child of node with potential default types [nt:folder] ... /oak:index/diff.index`),
because `/oak:index`'s real node type isn't declared in any content package. It's a known false
positive and safe to ignore.

## Maven settings

The project comes with the auto-public repository configured. To setup the repository in your Maven settings, refer to:

    http://helpx.adobe.com/experience-manager/kb/SetUpTheAdobeMavenRepository.html
