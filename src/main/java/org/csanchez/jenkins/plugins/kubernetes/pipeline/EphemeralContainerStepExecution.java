package org.csanchez.jenkins.plugins.kubernetes.pipeline;

import static org.csanchez.jenkins.plugins.kubernetes.pipeline.Resources.closeQuietly;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.codahale.metrics.MetricRegistry;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Launcher;
import hudson.LauncherDecorator;
import hudson.console.ModelHyperlinkNote;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.DescribableList;

import io.fabric8.kubernetes.api.model.ContainerStateWaiting;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.EphemeralContainer;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.KubernetesClientTimeoutException;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.kubernetes.client.dsl.PodResource;
import jenkins.metrics.api.Metrics;
import jenkins.model.Jenkins;

import org.apache.commons.lang.StringUtils;
import org.csanchez.jenkins.plugins.kubernetes.ContainerTemplate;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesCloud;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesSlave;
import org.csanchez.jenkins.plugins.kubernetes.MetricNames;
import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;
import org.csanchez.jenkins.plugins.kubernetes.PodTemplateBuilder;
import org.csanchez.jenkins.plugins.kubernetes.PodUtils;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.GeneralNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContext;


public class EphemeralContainerStepExecution extends GeneralNonBlockingStepExecution {

    private static final long serialVersionUID = 7634132798345235774L;

    private static final Logger LOGGER = Logger.getLogger(EphemeralContainerStepExecution.class.getName());

    @SuppressFBWarnings(value = "SE_TRANSIENT_FIELD_NOT_RESTORED", justification = "not needed on deserialization")
    private final transient EphemeralContainerStep step;

    @CheckForNull
    private ContainerExecDecorator decorator;

    EphemeralContainerStepExecution(@NonNull EphemeralContainerStep step, @NonNull StepContext context) {
        super(context);
        this.step = step;
    }

    @Override
    public boolean start() throws Exception {
        KubernetesNodeContext nodeContext = new KubernetesNodeContext(getContext());
        KubernetesSlave slave = nodeContext.getKubernetesSlave();
        KubernetesCloud cloud = slave.getKubernetesCloud();
        if (!cloud.isEphemeralContainersEnabled()) {
            throw new AbortException("Ephemeral containers not enabled on " + cloud.getDisplayName());
        }

        run(this::startEphemeralContainer);
        return false;
    }

    protected void startEphemeralContainer() throws Exception {
        LOGGER.log(Level.FINE, "Starting ephemeral container step.");
        KubernetesNodeContext nodeContext = new KubernetesNodeContext(getContext());
        KubernetesSlave slave = nodeContext.getKubernetesSlave();
        KubernetesCloud cloud = slave.getKubernetesCloud();
        if (!cloud.isEphemeralContainersEnabled()) {
            throw new AbortException("Ephemeral containers not enabled on " + cloud.getDisplayName());
        }

        // Build container template
        String containerName = PodUtils.createNameWithRandomSuffix("jenkins-ephemeral");
        ContainerTemplate template = new ContainerTemplate(containerName, step.getImage());
        template.setEnvVars(step.getEnvVars());
        template.setShell(step.getShell());
        template.setAlwaysPullImage(step.isAlwaysPullImage());
        template.setRunAsUser(step.getRunAsUser());
        template.setRunAsGroup(step.getRunAsGroup());
        template.setTtyEnabled(true);
        if (template.getRunAsUser() == null && template.getRunAsGroup() == null) {
            setDefaultRunAsUser(template);
        }

        // Create ephemeral container from container template
        PodTemplate pt = slave.getTemplate();
        EphemeralContainer ec = new PodTemplateBuilder(pt, slave).createEphemeralContainer(template);
        ec.setTargetContainerName(step.getTargetContainer());
        // Windows containers not yet supported
        // Our file monitor script that will exit the container when the step ends to return resources to the Pod.
        List<String> monitorCmd = Arrays.asList("sh", "-c", "set -e; { while ! test -f /tmp/" + containerName + "-jenkins-step-is-done-monitor ; do sleep 3; done }");
        if (step.getCommand() == null) {
            // Use default container entrypoint. It is assumed to be able to handle taking an executable as the first
            // arg. Specifically "sh" so the file monitory and be run.
            ec.setArgs(monitorCmd);
        } else if (step.getCommand().isEmpty()) {
            // if command is empty array it tells us the user wants us to override the entrypoint
            // the equivalent of `--entrypoint=''` in docker speak.
            ec.setCommand(monitorCmd);
        } else {
            // Use the user supplied entrypoint. Like the default entrypoint it is assumed to handle taking an executable
            // as the first arg.
            ec.setCommand(step.getCommand());
            ec.setArgs(monitorCmd);
        }

        LOGGER.finest(() -> "Adding Ephemeral Container: " + ec);
        // Display link in the build console to the new container
        TaskListener listener = getContext().get(TaskListener.class);
        String containerUrl = ModelHyperlinkNote.encodeTo("/computer/" + nodeContext.getPodName() + "/container?name=" + containerName, containerName);
        if (listener != null) {
            String runningAs = "";
            SecurityContext sc = ec.getSecurityContext();
            if (sc != null) {
                runningAs = String.format(" (running as %s:%s)", sc.getRunAsUser(), sc.getRunAsGroup());
            }

            // Add link to the container logs
            listener.getLogger().println("Starting ephemeral container " + containerUrl + " with image " + ec.getImage() + runningAs);
        }

        // Patch the Pod with the new ephemeral container
        KubernetesClient client = nodeContext.connectToCloud();
        PodResource podResource = client.pods().withName(nodeContext.getPodName());
        MetricRegistry metrics = Metrics.metricRegistry();
        try {
            // Current implementation of ephemeral containers only allows ephemeral containers to be added
            // so patching may fail if different threads attempt to add using the same resource version
            // which would effectively act as a "delete" when the second patch was processed. If this
            // situation is detected the patch will be retried.
            int maxRetries = 3;
            int retries = 0;
            do {
                try {
                    podResource.edit(pod -> new PodBuilder(pod)
                            .editSpec()
                            .addToEphemeralContainers(ec)
                            .endSpec()
                            .build());

                    break; // Success
                } catch (KubernetesClientException kce) {
                    Status status = kce.getStatus();
                    if (retries < maxRetries
                            && status != null
                            && StringUtils.contains(status.getMessage(), "Forbidden: existing ephemeral containers")) {
                        retries++;
                        LOGGER.info("Ephemeral container patch failed due to optimistic locking, trying again (" + retries + " of " + maxRetries + "): " + kce.getMessage());
                    } else {
                        throw kce;
                    }
                }
            } while (true);
        } catch (KubernetesClientException kce) {
            metrics.counter(MetricNames.EPHEMERAL_CONTAINERS_CREATION_FAILED).inc();
            LOGGER.log(Level.WARNING, "Failed to add ephemeral container " + containerName + " to pod " + slave.getPodName() + " on cloud " + cloud.name, kce);
            String message = "Ephemeral container could not be added.";
            Status status = kce.getStatus();
            if (status != null) {
                if (status.getMessage() != null) {
                    message += status.getMessage();
                }

                message += " (" + status.getReason() + ")";
            }

            throw new AbortException(message);
        }

        // Wait until ephemeral container has started
        LOGGER.fine(() -> "Waiting for Ephemeral Container to start: " + containerName);
        try {
            podResource.waitUntilCondition(new EphemeralContainerRunningCondition(containerName, containerUrl, listener), pt.getSlaveConnectTimeout(), TimeUnit.SECONDS);
            LOGGER.fine(() -> "Ephemeral Container started: " + containerName);
            metrics.counter(MetricNames.EPHEMERAL_CONTAINERS_CREATED).inc();
        } catch (KubernetesClientException kce) {
            metrics.counter(MetricNames.EPHEMERAL_CONTAINERS_CREATION_FAILED).inc();
            if (kce instanceof KubernetesClientTimeoutException) {
                String status;
                try {
                    status = PodUtils.getContainerStatus(podResource.get(), containerName)
                            .map(cs -> cs.getState().toString())
                            .orElse("no status available");
                } catch (Exception ignored) {
                    status = "failed to get status";
                }

                throw new AbortException("Ephemeral container failed to start after " + pt.getSlaveConnectTimeout() + " seconds: " + status);
            } else {
                Throwable cause = kce.getCause();
                if (cause instanceof InterruptedException) {
                    LOGGER.log(Level.FINEST, "Ephemeral container step interrupted", kce);
                    return;
                } else {
                    LOGGER.log(Level.FINEST, "Ephemeral container failed to start due to kubernetes client exception", kce);
                    throw new AbortException("Ephemeral container " + containerName + " failed to start: " + kce.getMessage());
                }
            }
        }

        EnvironmentExpander env = EnvironmentExpander.merge(
                getContext().get(EnvironmentExpander.class),
                EnvironmentExpander.constant(Collections.singletonMap("POD_CONTAINER", containerName))
        );

        EnvVars globalVars = null;
        Jenkins instance = Jenkins.get();

        DescribableList<NodeProperty<?>, NodePropertyDescriptor> globalNodeProperties = instance.getGlobalNodeProperties();
        List<EnvironmentVariablesNodeProperty> envVarsNodePropertyList = globalNodeProperties
                .getAll(EnvironmentVariablesNodeProperty.class);
        if (envVarsNodePropertyList != null && envVarsNodePropertyList.size() != 0) {
            globalVars = envVarsNodePropertyList.get(0).getEnvVars();
        }

        EnvVars rcEnvVars = null;
        Run<?, ?> run = getContext().get(Run.class);
        if (run != null && listener != null) {
            rcEnvVars = run.getEnvironment(listener);
        }

        decorator = new EphemeralContainerExecDecorator();
        decorator.setNodeContext(nodeContext);
        decorator.setContainerName(containerName);
        decorator.setEnvironmentExpander(env);
        decorator.setGlobalVars(globalVars);
        decorator.setRunContextEnvVars(rcEnvVars);
        decorator.setShell(step.getShell());
        getContext().newBodyInvoker()
                .withContexts(
                        BodyInvoker.mergeLauncherDecorators(getContext().get(LauncherDecorator.class), decorator),
                        env
                )
                .withCallback(new CloseableExecCallback(decorator))
                .withCallback(new TerminateEphemeralContainerExecCallback(containerName))
                .start();
    }

    @Override
    public void stop(@NonNull Throwable cause) throws Exception {
        LOGGER.finest("Stopping ephemeral container step.");
        super.stop(cause);
        if (decorator != null) {
            closeQuietly(getContext(), decorator);
            terminateEphemeralContainer(getContext(), decorator.getContainerName());
        }
    }

    private void setDefaultRunAsUser(ContainerTemplate template) throws IOException, InterruptedException {
        Launcher launcher = getContext().get(Launcher.class);
        if (launcher != null && launcher.isUnix()) {
            ByteArrayOutputStream userId = new ByteArrayOutputStream();
            launcher.launch()
                    .cmds("id", "-u")
                    .quiet(true)
                    .stdout(userId)
                    .start()
                    .joinWithTimeout(60, TimeUnit.SECONDS, launcher.getListener());

            ByteArrayOutputStream groupId = new ByteArrayOutputStream();
            launcher.launch()
                    .cmds("id", "-g")
                    .quiet(true)
                    .stdout(groupId)
                    .start()
                    .joinWithTimeout(60, TimeUnit.SECONDS, launcher.getListener());

            final Charset charset = Charset.defaultCharset();
            template.setRunAsUser(userId.toString(charset).trim());
            template.setRunAsGroup(groupId.toString(charset).trim());
        }
    }

    private static void terminateEphemeralContainer(StepContext context, String containerName) throws Exception {
        LOGGER.fine(() -> "Removing ephemeral container: " + containerName);
        KubernetesNodeContext nodeContext = new KubernetesNodeContext(context);
        KubernetesClient client = nodeContext.connectToCloud();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PodResource resource = client.pods().withName(nodeContext.getPodName());
        try (ExecWatch ignored = resource.inContainer(containerName)
                .redirectingInput()
                .writingOutput(out)
                .writingError(out)
                .withTTY()
                .exec("touch", "/tmp/" + containerName + "-jenkins-step-is-done-monitor")) {
            resource.waitUntilCondition(new EphemeralContainerStatusCondition(containerName, false), 10, TimeUnit.SECONDS);
            LOGGER.finest(() -> "ephemeral container stopped: " + containerName);
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "failed to terminate ephemeral container: " + containerName, ex);
        }


        LOGGER.finest(() -> {
            try {
                ContainerStatus status = PodUtils.getContainerStatus(resource.get(), containerName).orElse(null);
                return "Ephemeral container status after step: " + containerName + " -> " + status;
            } catch (KubernetesClientException ignored) {
                return "Failed to get container status after step";
            }
        });
    }

    private static class TerminateEphemeralContainerExecCallback extends BodyExecutionCallback.TailCall {

        private static final long serialVersionUID = 6385838254761750483L;
        private final String containerName;

        private TerminateEphemeralContainerExecCallback(String containerName) {
            this.containerName = containerName;
        }

        @Override
        public void finished(StepContext context) throws Exception {
            terminateEphemeralContainer(context, containerName);
        }

    }

    private static class EphemeralContainerStatusCondition implements Predicate<Pod> {
        private final String containerName;
        private final boolean running;

        EphemeralContainerStatusCondition(String containerName, boolean running) {
            this.containerName = containerName;
            this.running = running;
        }

        @Override
        public boolean test(Pod pod) {
            // pod could be null if informer list is empty
            if (pod == null) {
                return !running;
            }

            return pod.getStatus()
                    .getEphemeralContainerStatuses()
                    .stream()
                    .filter(status -> StringUtils.equals(status.getName(), containerName))
                    .anyMatch(status -> {
                        onStatus(status);
                        if (running) {
                            return status.getState().getRunning() != null;
                        } else {
                            return status.getState().getTerminated() != null;
                        }
                    });
        }

        protected void onStatus(ContainerStatus status) {
        }

    }

    private static class EphemeralContainerRunningCondition extends EphemeralContainerStatusCondition {

        private static final Set<String> IGNORE_REASONS = new HashSet<>(Arrays.asList("ContainerCreating", "PodInitializing"));
        @CheckForNull
        private final TaskListener taskListener;
        private final String containerUrl;

        EphemeralContainerRunningCondition(String containerName, String containerUrl, @CheckForNull TaskListener listener) {
            super(containerName, true);
            this.containerUrl = containerUrl;
            this.taskListener = listener;
        }

        @Override
        protected void onStatus(ContainerStatus status) {
            if (taskListener != null) {
                ContainerStateWaiting waiting = status.getState().getWaiting();
                // skip initial "ContainerCreating" event
                if (waiting != null  && !IGNORE_REASONS.contains(waiting.getReason())) {
                    StringBuilder logMsg = new StringBuilder()
                            .append("Ephemeral container ")
                            .append(containerUrl);
                    String message = waiting.getMessage();
                    if (message != null) {
                        logMsg.append(" ").append(message);
                    }

                    logMsg.append(" (").append(waiting.getReason()).append(")");
                    taskListener.getLogger().println(logMsg);
                }
            }
        }

    }

}
