package org.jenkinsci.plugins.statsdnotifier;

import com.timgroup.statsd.NonBlockingStatsDClient;
import com.timgroup.statsd.StatsDClient;
import com.timgroup.statsd.StatsDClientErrorHandler;
import com.timgroup.statsd.StatsDClientException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.analysis.core.BuildResult;
import hudson.plugins.analysis.core.ParserResult;
import hudson.plugins.analysis.core.PluginDescriptor;
import hudson.plugins.analysis.core.ResultAction;
import hudson.plugins.analysis.util.model.FileAnnotation;
import hudson.plugins.checkstyle.CheckStyleResultAction;
import hudson.plugins.pmd.PmdResultAction;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import hudson.tasks.junit.TestResultAction;
import hudson.tasks.junit.TestResult;
import hudson.Util;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Collection;


public class StatsdNotifier extends Publisher implements SimpleBuildStep {

    private final String prefix;
    private Boolean sendCheckStyle = false;
    private Boolean sendPMD = false;
	private Boolean sendJunit = false;
    private final String checkstylePrefix;
    private final String pmdPrefix;
    private final String junitPrefix;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public StatsdNotifier(String prefix, boolean sendCheckStyle, boolean sendPMD, boolean sendJunit, String checkstylePrefix, String pmdPrefix, String junitPrefix) {
        this.prefix = prefix;
        this.sendCheckStyle = sendCheckStyle;
        this.sendPMD = sendPMD;
        this.sendJunit = sendJunit;
        this.checkstylePrefix = checkstylePrefix;
        this.pmdPrefix = pmdPrefix;
		this.junitPrefix = junitPrefix;
    }

    public Boolean getSendCheckStyle() {
        return sendCheckStyle;
    }

    public void setSendCheckStyle(Boolean sendCheckStyle) {
        this.sendCheckStyle = sendCheckStyle;
    }

    public Boolean getSendPMD() {
        return sendPMD;
    }

    public void setSendPMD(Boolean sendPMD) {
        this.sendPMD = sendPMD;
    }
	
	public Boolean getSendJunit() {
        return sendJunit;
    }

    public void setSendJunit(Boolean sendJunit) {
        this.sendJunit = sendJunit;
    }

    public String getCheckstylePrefix() {
        return checkstylePrefix;
    }

    public String getPmdPrefix() {
        return pmdPrefix;
    }

	public String getJunitPrefix() {
        return junitPrefix;
    }

    // We'll use this from the {@code global.jelly}.
    public String getPrefix() {
        return prefix;
    }

    @Override
    public void perform(Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener) {

        PrintStream logger = listener.getLogger();
        StatsDClient client = null;
        try {
            client = initClient(logger);
        } catch (StatsDClientException exc) {
            logger.println("Error when creating StatsD client");
            logger.println(exc.getMessage());
            return;
        }

        if (getSendCheckStyle()) {
            handleCheckstyleMetrics(build, client, logger);
        }

        if (getSendPMD()) {
            handlePMDMetrics(build, client, logger);
        }
		
		if (getSendJunit()) {
            handleJunitMetrics(build, client, logger);
        }
    }

    private void handlePMDMetrics(Run<?, ?> build, StatsDClient client, PrintStream logger) {
        if (!DescriptorImpl.isPMDInstalled()) {
            logger.println("PMD metric can't be handled. PMD plugin is not installed");
            return;
        }

        ResultAction<? extends BuildResult> action = build.getAction(PmdResultAction.class);
        if (action != null) {
            BuildResult actualResult = action.getResult();
            client.recordGaugeValue(getPrefix() + "." + getPmdPrefix(), actualResult.getNumberOfWarnings());
        } else {
            logger.println("Can not find pmd metrics to be sent to StatsD");
        }
    }

    private void handleCheckstyleMetrics(Run<?, ?> build, StatsDClient client, PrintStream logger) {
        if (!DescriptorImpl.isCheckStyleInstalled()) {
            logger.println("Checkstyle metric can't be handled. Checkstyle plugin is not installed");
            return;
        }

        ResultAction<? extends BuildResult> action = build.getAction(CheckStyleResultAction.class);
        if (action != null) {
            BuildResult actualResult = action.getResult();
            client.recordGaugeValue(getPrefix() + "." + getCheckstylePrefix(), actualResult.getNumberOfWarnings());
        } else {
            logger.println("Can not find checkstyle metrics to be sent to StatsD");
        }
    }
	
    private void handleJunitMetrics(Run<?, ?> build, StatsDClient client, PrintStream logger) {
        if (!DescriptorImpl.isJunitInstalled()) {
            logger.println("Junit metric can't be handled. Junit plugin is not installed");
            return;
        }

        TestResultAction action = build.getAction(TestResultAction.class);
        if (action != null) {
            TestResult actualResult = action.getResult();
            long durationInSeconds = (System.currentTimeMillis() - build.getStartTimeInMillis()) / 1000;
            
            client.recordGaugeValue(getPrefix() + "." + getJunitPrefix() + ".TotalTests", actualResult.getTotalCount());
			client.recordGaugeValue(getPrefix() + "." + getJunitPrefix() + ".FailedTests", actualResult.getFailCount());
			client.recordGaugeValue(getPrefix() + "." + getJunitPrefix() + ".SkippedTests", actualResult.getSkipCount());
			client.recordGaugeValue(getPrefix() + "." + getJunitPrefix() + ".BuildDuration" , durationInSeconds);

//	        String duration = Util.getTimeSpanString(durationInSeconds);

        } else {
            logger.println("Can not find Junit metrics to be sent to StatsD");
        }
    }
	
    private StatsDClient initClient(PrintStream logger) {
        DescriptorImpl descriptor = getDescriptor();
        String statsDHost = descriptor.getHost();
        Integer statsDPort = descriptor.getPort();
        String statsDPrefix = descriptor.getPrefix();

        return new NonBlockingStatsDClient(
                statsDPrefix,
                statsDHost,
                statsDPort,
                new StatsDErrorHandler(logger)
        );
    }

    private static class StatsDErrorHandler implements StatsDClientErrorHandler {

        private PrintStream logger;

        StatsDErrorHandler(PrintStream logger) {
            this.logger = logger;
        }

        @Override
        public void handle(Exception e) {
            this.logger.println("Error: " + e.getClass());
            this.logger.println(e.getMessage());
        }
    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    /**
     * Descriptor for {@link StatsdNotifier}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     * 
     * See {@code src/main/resources/hudson/plugins/hello_world/StatsdNotifier/*.jelly}
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        public static final String CHECKSTYLE = "checkstyle";
        public static final String PMD = "pmd";
		public static final String JUNIT = "junit";


        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return isCheckStyleInstalled() || isPMDInstalled();
        }

        public static boolean isCheckStyleInstalled() {
            return PluginDescriptor.isPluginInstalled(CHECKSTYLE);
        }

        public static boolean isPMDInstalled() {
            return PluginDescriptor.isPluginInstalled(PMD);
        }
		
        public static boolean isJunitInstalled() {
            return PluginDescriptor.isPluginInstalled(JUNIT);
        }

        /**
         * This human readable prefix is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Publish results to StatsD";
        }

        private String prefix;
        private String host;
        private int port;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }

        public DescriptorImpl() {
            load();
        }

        public FormValidation doCheckPrefix(@QueryParameter String prefix) throws IOException, ServletException {
            return FormValidation.ok();
        }

        public FormValidation doCheckHost(@QueryParameter String host) throws IOException, ServletException {
            return FormValidation.ok();
        }

        public FormValidation doCheckPort(@QueryParameter String port) throws IOException, ServletException {
            return FormValidation.ok();
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            this.prefix = formData.getString("prefix");
            this.host = formData.getString("host");
            this.port = formData.getInt("port");

            save();
            return super.configure(req, formData);
        }
    }
}

