package net.thucydides.core.reports.html;

import com.beust.jcommander.internal.Lists;
import net.serenitybdd.core.SerenitySystemProperties;
import net.serenitybdd.core.time.Stopwatch;
import net.thucydides.core.ThucydidesSystemProperty;
import net.thucydides.core.guice.Injectors;
import net.thucydides.core.issues.IssueTracking;
import net.thucydides.core.reports.*;
import net.thucydides.core.requirements.RequirementsProviderService;
import net.thucydides.core.requirements.RequirementsService;
import net.thucydides.core.requirements.model.RequirementsConfiguration;
import net.thucydides.core.requirements.reports.RequirmentsOutcomeFactory;
import net.thucydides.core.util.EnvironmentVariables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Generates an aggregate acceptance test report in HTML form.
 * Reads all the reports from the output directory to generates aggregate HTML reports
 * summarizing the results.
 */
public class HtmlAggregateStoryReporter extends HtmlReporter implements UserStoryTestReporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(HtmlAggregateStoryReporter.class);
    public static final int REPORT_GENERATION_THREAD_POOL_SIZE = 16;
    private static final int MAX_BATCHES = 128;

    private String projectName;
    private String relativeLink;
    private ReportNameProvider reportNameProvider;
    private final IssueTracking issueTracking;
    private final RequirementsService requirementsService;
    private final RequirmentsOutcomeFactory requirementsFactory;
    private final RequirementsConfiguration requirementsConfiguration;
    private final EnvironmentVariables environmentVariables;
    private FormatConfiguration formatConfiguration;

    private Stopwatch stopwatch = new Stopwatch();

    public HtmlAggregateStoryReporter(final String projectName) {
        this(projectName, "");
    }

    public HtmlAggregateStoryReporter(final String projectName, final String relativeLink) {
        this(projectName, relativeLink,
                Injectors.getInjector().getInstance(IssueTracking.class),
                Injectors.getInjector().getInstance(RequirementsService.class),
                Injectors.getInjector().getProvider(EnvironmentVariables.class).get());
    }

    public HtmlAggregateStoryReporter(final String projectName,
                                      final IssueTracking issueTracking) {
        this(projectName, "", issueTracking, Injectors.getInjector().getInstance(RequirementsService.class),
                Injectors.getInjector().getProvider(EnvironmentVariables.class).get());
    }

    public HtmlAggregateStoryReporter(final String projectName,
                                      final String relativeLink,
                                      final IssueTracking issueTracking,
                                      final RequirementsService requirementsService,
                                      final EnvironmentVariables environmentVariables) {
        this.projectName = projectName;
        this.relativeLink = relativeLink;
        this.issueTracking = issueTracking;
        this.reportNameProvider = new ReportNameProvider();

        RequirementsProviderService requirementsProviderService = Injectors.getInjector().getInstance(RequirementsProviderService.class);
        this.requirementsFactory = new RequirmentsOutcomeFactory(requirementsProviderService.getRequirementsProviders(), issueTracking);
        this.requirementsService = requirementsService;
        this.requirementsConfiguration = new RequirementsConfiguration(getEnvironmentVariables());
        this.environmentVariables = environmentVariables;
        this.formatConfiguration = new FormatConfiguration(environmentVariables);
    }

    public OutcomeFormat getFormat() {
        return formatConfiguration.getPreferredFormat();
    }

    public String getProjectName() {
        return projectName;
    }

    public TestOutcomes generateReportsForTestResultsFrom(final File sourceDirectory) throws IOException {

        copyScreenshotsFrom(sourceDirectory);

        TestOutcomes allTestOutcomes = loadTestOutcomesFrom(sourceDirectory);

        generateReportsForTestResultsIn(allTestOutcomes);

        return allTestOutcomes;
    }

    private void copyScreenshotsFrom(File sourceDirectory) {
        if ((getOutputDirectory() == null) || (getOutputDirectory().equals(sourceDirectory))) {
            return;
        }

        CopyOption[] options = new CopyOption[]{StandardCopyOption.COPY_ATTRIBUTES};

        Path targetPath = Paths.get(getOutputDirectory().toURI());
        Path sourcePath = Paths.get(sourceDirectory.toURI());
        try (DirectoryStream<Path> directoryContents = Files.newDirectoryStream(sourcePath)) {
            for (Path sourceFile : directoryContents) {
                Path destinationFile = targetPath.resolve(sourceFile.getFileName());
                if (Files.notExists(destinationFile)) {
                    Files.copy(sourceFile, destinationFile, options);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Error during copying files to the target directory", e);
        }
    }

    public void generateReportsForTestResultsIn(TestOutcomes testOutcomes) throws IOException {

        copyResourcesToOutputDirectory();

        FreemarkerContext context = new FreemarkerContext(environmentVariables, requirementsService, issueTracking, relativeLink);

        List<ReportingTask> reportingTasks = Lists.newArrayList();

        reportingTasks.add(new AggregateReportingTask(context, environmentVariables, getOutputDirectory()));
        reportingTasks.add(new TagReportingTask(context, environmentVariables, getOutputDirectory(), reportNameProvider));
        reportingTasks.add(new TagTypeReportingTask(context, environmentVariables, getOutputDirectory(), reportNameProvider));
        reportingTasks.add(new ResultReportingTask(context, environmentVariables, getOutputDirectory(), reportNameProvider));
        reportingTasks.add(new RequirementsReportingTask(context, environmentVariables, getOutputDirectory(),
                                                                  reportNameProvider, requirementsFactory,
                                                                  requirementsService, relativeLink));
        addAssociatedTagReporters(testOutcomes, context, reportingTasks);

        generateReportsFor(testOutcomes, reportingTasks);

        copyTestResultsToOutputDirectory();
    }

    private void addAssociatedTagReporters(TestOutcomes testOutcomes, FreemarkerContext context, List<ReportingTask> reportingTasks) {
        int maxPossibleBatches = testOutcomes.getTags().size();
        int totalBatches = (MAX_BATCHES < maxPossibleBatches) ? MAX_BATCHES : maxPossibleBatches;
        for(int batch = 1; batch <= totalBatches; batch++) {
            reportingTasks.add(new AssociatedTagReportingTask(context, environmentVariables, getOutputDirectory(), reportNameProvider)
                               .forBatch(batch,totalBatches));
        }
    }

    private void generateReportsFor(final TestOutcomes testOutcomes, List<ReportingTask> reportingTasks) throws IOException {
        stopwatch.start();

        ExecutorService executor = Executors.newFixedThreadPool(REPORT_GENERATION_THREAD_POOL_SIZE);

        for(final ReportingTask reportingTask : reportingTasks) {
            Runnable worker = new Runnable(){

                @Override
                public void run() {
                    try {
                        reportingTask.generateReportsFor(testOutcomes);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            };
            executor.execute(worker);
        }
        LOGGER.debug("Shutting down Test outcome reports generation");
        executor.shutdown();
        while (!executor.isTerminated()) {}

        LOGGER.debug("Test outcome reports generated in {} ms", stopwatch.stop());
    }

    private TestOutcomes loadTestOutcomesFrom(File sourceDirectory) throws IOException {
        return TestOutcomeLoader.loadTestOutcomes().inFormat(getFormat()).from(sourceDirectory).withHistory().withRequirementsTags();
    }

    protected SerenitySystemProperties getSystemProperties() {
        return SerenitySystemProperties.getProperties();
    }

    public void setIssueTrackerUrl(String issueTrackerUrl) {
        if (issueTrackerUrl != null) {
            getSystemProperties().setValue(ThucydidesSystemProperty.THUCYDIDES_ISSUE_TRACKER_URL, issueTrackerUrl);
        }
    }

    public void setJiraUrl(String jiraUrl) {
        if (jiraUrl != null) {
            getSystemProperties().setValue(ThucydidesSystemProperty.JIRA_URL, jiraUrl);
        }
    }

    public void setJiraProject(String jiraProject) {
        if (jiraProject != null) {
            getSystemProperties().setValue(ThucydidesSystemProperty.JIRA_PROJECT, jiraProject);
        }
    }

    public void setJiraUsername(String jiraUsername) {
        if (jiraUsername != null) {
            getSystemProperties().setValue(ThucydidesSystemProperty.JIRA_USERNAME, jiraUsername);
        }
    }

    public void setJiraPassword(String jiraPassword) {
        if (jiraPassword != null) {
            getSystemProperties().setValue(ThucydidesSystemProperty.JIRA_PASSWORD, jiraPassword);
        }
    }

    public List<String> getRequirementTypes() {
        List<String> types = requirementsService.getRequirementTypes();
        if (types.isEmpty()) {
            LOGGER.warn("No requirement types found in the test outcome requirements: using default requirements");
            return requirementsConfiguration.getRequirementTypes();
        } else {
            return types;
        }
    }
}

