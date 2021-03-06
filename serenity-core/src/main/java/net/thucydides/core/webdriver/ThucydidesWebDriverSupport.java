package net.thucydides.core.webdriver;

import net.thucydides.core.annotations.TestCaseAnnotations;
import net.thucydides.core.guice.Injectors;
import net.thucydides.core.pages.Pages;
import net.thucydides.core.steps.StepAnnotations;
import net.thucydides.core.steps.StepFactory;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.SessionId;

/**
 * A utility class that provides services to initialize web testing and reporting-related fields in arbitrary objects.
 * It is designed to help integrate Thucydides into other testing tools such as Cucumber.
 */
public class ThucydidesWebDriverSupport {

    private static final ThreadLocal<WebdriverManager> webdriverManagerThreadLocal = new ThreadLocal<WebdriverManager>();
    private static final ThreadLocal<Pages> pagesThreadLocal = new ThreadLocal<Pages>();
    private static final ThreadLocal<StepFactory> stepFactoryThreadLocal = new ThreadLocal<StepFactory>();
    private static final ThreadLocal<String> defaultDriverType = new ThreadLocal<>();

    public static void initialize() {
        if (!webdriversInitialized()) {
            setWebdriverManager(Injectors.getInjector().getInstance(WebdriverManager.class));
        }
    }

    public static void initialize(String requestedDriver) {
        setWebdriverManager(Injectors.getInjector().getInstance(WebdriverManager.class));
        getWebdriverManager().overrideDefaultDriverType(requestedDriver);
    }

    public static void initialize(WebdriverManager webdriverManager, String requestedDriver) {
        setupWebdriverManager(webdriverManager, requestedDriver);
        initPagesObjectUsing(getDriver());
    }

    public static void reset() {
        if (webdriverManagerThreadLocal.get() != null) {
            webdriverManagerThreadLocal.get().reset();
        }
        webdriverManagerThreadLocal.remove();
        pagesThreadLocal.remove();
        stepFactoryThreadLocal.remove();
        defaultDriverType.remove();
    }

    public static boolean isInitialised() {
        return (webdriverManagerThreadLocal.get() != null);
    }

    private static boolean webdriversInitialized() {
        return (webdriverManagerThreadLocal.get() != null);
    }

//    private static void lazyInitalize() {
//        if (!webdriversInitialized()) {
//            initialize();
//        }
//    }

    public static void initializeFieldsIn(final Object testCase) {
        initialize();
        getDriver();
        injectDriverInto(testCase);
        injectAnnotatedPagesObjectInto(testCase);
    }

    public static StepFactory getStepFactory() {
        if (pagesThreadLocal.get() == null) {
            initPagesObjectUsing(getDriver());
        }
        return stepFactoryThreadLocal.get();
    }

    public static void useDefaultDriver(String driverName) {
        defaultDriverType.set(driverName);
    }

    public static void clearDefaultDriver() {
        defaultDriverType.remove();
        if (webdriverManagerThreadLocal.get() != null) {
            webdriverManagerThreadLocal.get().overrideDefaultDriverType("");
        }
    }

    public static void useDriver(WebDriver driver) {
        initialize();
        getWebdriverManager().registerDriver(driver);
    }


    public static WebDriver getDriver() {
        WebDriver driver;

        if (webdriverManagerThreadLocal.get() == null) {
            return null;
        }

        if (defaultDriverType.get() != null) {
            driver = getWebdriverManager().getWebdriver(defaultDriverType.get());
        } else {
            driver = (getWebdriverManager().getCurrentDriver() != null) ?
                    getWebdriverManager().getCurrentDriver() : getWebdriverManager().getWebdriver();
        }

        initPagesObjectUsing(driver);

        return driver;

    }

    public static void closeAllDrivers() {
        if (webdriversInitialized()) {
            getWebdriverManager().closeAllDrivers();
        }
    }

    private static void setupWebdriverManager(WebdriverManager webdriverManager, String requestedDriver) {
        setWebdriverManager(webdriverManager);
        getWebdriverManager().overrideDefaultDriverType(requestedDriver);
    }

    private static void initStepFactoryUsing(final Pages pagesObject) {
        stepFactoryThreadLocal.set(new StepFactory(pagesObject));
    }

    public static WebdriverManager getWebdriverManager(WebDriverFactory webDriverFactory, Configuration configuration) {
        initialize(new SerenityWebdriverManager(webDriverFactory, configuration), "");
        return webdriverManagerThreadLocal.get();
    }

    public static WebdriverManager getWebdriverManager() {
//        lazyInitalize();
        return webdriverManagerThreadLocal.get();
    }

    private static void initPagesObjectUsing(final WebDriver driver) {
        pagesThreadLocal.set(new Pages(driver));
        initStepFactoryUsing(getPages());
    }

    public static Pages getPages() {
        if (pagesThreadLocal.get() == null) {
            initPagesObjectUsing(getDriver());
        }
        return pagesThreadLocal.get();
    }

    /**
     * Instantiate the @Managed-annotated WebDriver instance with current WebDriver.
     */
    protected static void injectDriverInto(final Object testCase) {
        TestCaseAnnotations.forTestCase(testCase).injectDrivers(getDriver(), getWebdriverManager());
    }

    /**
     * Instantiates the @ManagedPages-annotated Pages instance using current WebDriver.
     */
    protected static void injectAnnotatedPagesObjectInto(final Object testCase) {
        StepAnnotations.injectOptionalAnnotatedPagesObjectInto(testCase, getPages());
    }

    public static <T extends WebDriver> T getProxiedDriver() {
        return (T) ((WebDriverFacade) getDriver()).getProxiedDriver();
}

    public static Class<? extends WebDriver> getDriverClass() {
        if (getDriver() instanceof WebDriverFacade) {
            return ((WebDriverFacade) getDriver()).getDriverClass();
        }
        return getDriver().getClass();
    }

    public static SessionId getSessionId() {
        return getWebdriverManager().getSessionId();

    }

    public static String getCurrentDriverName() {
        if (!webdriversInitialized()) {
            return "";
        }
        return getWebdriverManager().getCurrentDriverType();
    }

    public static String getDriversUsed() {
        if (getWebdriverManager() == null) {
            return "";
        }
        return getWebdriverManager().getActiveDriverTypes().get(0);
    }

    public static boolean isDriverInstantiated() {
        return isInitialised() && getWebdriverManager().hasAnInstantiatedDriver();
    }

    private static void setWebdriverManager(WebdriverManager webdriverManager) {
        webdriverManagerThreadLocal.set(webdriverManager);
    }
}
