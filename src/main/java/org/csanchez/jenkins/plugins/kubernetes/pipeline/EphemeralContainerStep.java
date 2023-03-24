package org.csanchez.jenkins.plugins.kubernetes.pipeline;

import java.io.Serializable;
import java.util.*;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener ;
import org.csanchez.jenkins.plugins.kubernetes.PodTemplateUtils;
import org.csanchez.jenkins.plugins.kubernetes.model.TemplateEnvVar;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Pipeline step that runs in an ephemeral container of the current Kubernetes Pod agent.
 */
public class EphemeralContainerStep extends Step implements Serializable {

    private static final long serialVersionUID = 5588861066775717487L;

    private final List<TemplateEnvVar> envVars = new ArrayList<>();
    private final String image;

    private boolean alwaysPullImage = true;

    private String targetContainer;

    private String shell;

    @CheckForNull
    private String runAsUser;

    @CheckForNull
    private String runAsGroup;


    @DataBoundConstructor
    public EphemeralContainerStep(String image) {
        if (!PodTemplateUtils.validateImage(image)) {
            throw new IllegalArgumentException("Invalid container image name: '" + image + "'");
        }

        this.image = image;
    }

    public String getImage() {
        return this.image;
    }

    public List<TemplateEnvVar> getEnvVars() {
        return envVars;
    }

    @DataBoundSetter
    public void setEnvVars(List<TemplateEnvVar> envVars) {
        this.envVars.addAll(envVars);
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new EphemeralContainerStepExecution(this, context);
    }

    public String getShell() {
        return shell;
    }

    @DataBoundSetter
    public void setShell(String shell) {
        this.shell = shell;
    }

    public String getTargetContainer() {
        return targetContainer;
    }

    @DataBoundSetter
    public void setTargetContainer(String targetContainer) {
        this.targetContainer = targetContainer;
    }

    public boolean isAlwaysPullImage() {
        return alwaysPullImage;
    }

    @DataBoundSetter
    public void setAlwaysPullImage(boolean alwaysPullImage) {
        this.alwaysPullImage = alwaysPullImage;
    }

    @CheckForNull
    public String getRunAsUser() {
        return runAsUser;
    }

    @DataBoundSetter
    public void setRunAsUser(@CheckForNull String runAsUser) {
        this.runAsUser = runAsUser;
    }

    @CheckForNull
    public String getRunAsGroup() {
        return runAsGroup;
    }

    @DataBoundSetter
    public void setRunAsGroup(@CheckForNull String runAsGroup) {
        this.runAsGroup = runAsGroup;
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "ephemeralContainer";
        }

        @Override
        public String getDisplayName() {
            return "Define an Ephemeral Container to add to the current Pod";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(Node.class, FilePath.class, Run.class, Launcher.class, TaskListener.class)));
        }
    }
}