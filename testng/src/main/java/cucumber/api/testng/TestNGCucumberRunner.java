package cucumber.api.testng;

import cucumber.api.event.TestRunFinished;
import cucumber.api.event.TestRunStarted;
import cucumber.runner.EventBus;
import cucumber.runner.TimeService;
import cucumber.runtime.BackendModuleBackendSupplier;
import cucumber.runtime.ClassFinder;
import cucumber.runtime.CucumberException;
import cucumber.runtime.FeatureCompiler;
import cucumber.runtime.FeaturePathFeatureSupplier;
import cucumber.runtime.filter.Filters;
import cucumber.runtime.formatter.Plugins;
import cucumber.runtime.filter.RerunFilters;
import cucumber.runtime.formatter.PluginFactory;
import cucumber.runtime.model.FeatureLoader;
import cucumber.runtime.ThreadLocalRunnerSupplier;
import cucumber.runtime.RuntimeGlueSupplier;
import cucumber.runtime.RuntimeOptions;
import cucumber.runtime.RuntimeOptionsFactory;
import cucumber.runtime.io.MultiLoader;
import cucumber.runtime.io.ResourceLoader;
import cucumber.runtime.io.ResourceLoaderClassFinder;
import cucumber.runtime.model.CucumberFeature;
import gherkin.events.PickleEvent;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Glue code for running Cucumber via TestNG.
 */
public class TestNGCucumberRunner {
    private final EventBus bus;
    private final Filters filters;
    private final FeaturePathFeatureSupplier featureSupplier;
    private final ThreadLocalRunnerSupplier runnerSupplier;
    private final Plugins plugins;
    private TestCaseResultListener testCaseResultListener;

    /**
     * Bootstrap the cucumber runtime
     *
     * @param clazz Which has the cucumber.api.CucumberOptions and org.testng.annotations.Test annotations
     */
    public TestNGCucumberRunner(Class clazz) {
        ClassLoader classLoader = clazz.getClassLoader();
        ResourceLoader resourceLoader = new MultiLoader(classLoader);

        RuntimeOptionsFactory runtimeOptionsFactory = new RuntimeOptionsFactory(clazz);
        RuntimeOptions runtimeOptions = runtimeOptionsFactory.create();

        TestNGReporter reporter = new TestNGReporter(new PrintStream(System.out) {
            @Override
            public void close() {
                // We have no intention to close System.out
            }
        });
        ClassFinder classFinder = new ResourceLoaderClassFinder(resourceLoader, classLoader);
        BackendModuleBackendSupplier backendSupplier = new BackendModuleBackendSupplier(resourceLoader, classFinder, runtimeOptions);
        bus = new EventBus(TimeService.SYSTEM);
        plugins = new Plugins(classLoader, new PluginFactory(), bus, runtimeOptions);
        FeatureLoader featureLoader = new FeatureLoader(resourceLoader);
        RerunFilters rerunFilters = new RerunFilters(runtimeOptions, featureLoader);
        filters = new Filters(runtimeOptions, rerunFilters);
        RuntimeGlueSupplier glueSupplier = new RuntimeGlueSupplier();
        this.runnerSupplier = new ThreadLocalRunnerSupplier(runtimeOptions, bus, backendSupplier, glueSupplier);
        featureSupplier = new FeaturePathFeatureSupplier(featureLoader, runtimeOptions);
        reporter.setEventPublisher(bus);
        testCaseResultListener = new TestCaseResultListener(runtimeOptions.isStrict());
        testCaseResultListener.setEventPublisher(bus);
    }

    public void runScenario(PickleEvent pickle) throws Throwable {
        testCaseResultListener.startPickle();
        runnerSupplier.get().runPickle(pickle);

        if (!testCaseResultListener.isPassed()) {
            throw testCaseResultListener.getError();
        }
    }

    public void finish() {
        bus.send(new TestRunFinished(bus.getTime()));
    }

    /**
     * @return returns the cucumber scenarios as a two dimensional array of {@link PickleEventWrapper}
     * scenarios combined with their {@link CucumberFeatureWrapper} feature.
     */
    public Object[][] provideScenarios() {
        try {
            List<Object[]> scenarios = new ArrayList<Object[]>();
            FeatureCompiler compiler = new FeatureCompiler();
            List<CucumberFeature> features = getFeatures();
            for (CucumberFeature feature : features) {
                List<PickleEvent> pickles = compiler.compileFeature(feature);

                for (PickleEvent pickle : pickles) {
                    if (filters.matchesFilters(pickle)) {
                        scenarios.add(new Object[]{new PickleEventWrapperImpl(pickle),
                            new CucumberFeatureWrapperImpl(feature)});
                    }
                }
            }
            return scenarios.toArray(new Object[][]{});
        } catch (CucumberException e) {
            return new Object[][]{new Object[]{new CucumberExceptionWrapper(e), null}};
        }
    }

    List<CucumberFeature> getFeatures() {

        List<CucumberFeature> features = featureSupplier.get();
        bus.send(new TestRunStarted(bus.getTime()));
        for (CucumberFeature feature : features) {
            feature.sendTestSourceRead(bus);
        }
        return features;
    }
}
