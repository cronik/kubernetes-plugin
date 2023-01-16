package org.csanchez.jenkins.plugins.kubernetes.pipeline;

import static org.csanchez.jenkins.plugins.kubernetes.pipeline.Resources.closeQuietly;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

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
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.EphemeralContainer;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.fabric8.kubernetes.client.Client;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.internal.OperationContext;
import io.fabric8.kubernetes.client.dsl.internal.PodOperationContext;
import io.fabric8.kubernetes.client.dsl.internal.core.v1.PodOperationsImpl;
import jenkins.model.Jenkins;
import org.apache.commons.lang.RandomStringUtils;
import org.csanchez.jenkins.plugins.kubernetes.*;
import org.jenkinsci.plugins.workflow.steps.*;

public class EphemeralContainerStepExecution extends StepExecution {

    private static final long serialVersionUID = 7634132798345235774L;

    private static final Logger LOGGER = Logger.getLogger(EphemeralContainerStepExecution.class.getName());

    @SuppressFBWarnings(value = "SE_TRANSIENT_FIELD_NOT_RESTORED", justification = "not needed on deserialization")
    private final transient EphemeralContainerStep step;

    private ContainerExecDecorator decorator;

    EphemeralContainerStepExecution(EphemeralContainerStep step, StepContext context) {
        super(context);
        this.step = step;
    }

    @Override
    public boolean start() throws Exception {
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
        // Override entrypoint with a file monitor script that will exit the container when the step ends
        // to return resources to the Pod.
        ec.setCommand(Collections.singletonList("sh"));
        ec.setArgs(Arrays.asList("-c", "set -e; { while ! test -f /tmp/" + containerName + "-jenkins-step-is-done-monitor ; do sleep 3; done }"));
        LOGGER.finest(() -> "Adding Ephemeral Container: " + ec);

        // Patch the Pod with the new ephemeral container
        KubernetesClient client = nodeContext.connectToCloud();
        PodResource podResource = client.pods().withName(nodeContext.getPodName());
        Pod pod = podResource.get();
        pod.getSpec().getEphemeralContainers().add(ec);
        PodResource pr = EphemeralPodOperations.forPod(client, slave.getPodName());
        pr.patch(pod);

        TaskListener listener = getContext().get(TaskListener.class);
        if (listener != null) {
            String runningAs = "";
            SecurityContext sc = ec.getSecurityContext();
            if (sc != null) {
                runningAs = String.format(" (running as %s:%s)", sc.getRunAsUser(), sc.getRunAsGroup());
            }

            // Add link to the container logs
            String containerUrl = ModelHyperlinkNote.encodeTo("/computer/" + nodeContext.getPodName() + "/container?name=" + containerName, containerName);
            listener.getLogger().println("Starting ephemeral container " + containerUrl + " for image " + ec.getImage() + runningAs);
        }

        // Wait until ephemeral container has started
        LOGGER.fine(() -> "Waiting for Ephemeral Container to start: " + containerName);
        try {
            podResource.waitUntilCondition(new EphemeralContainerStartedCondition(containerName, true), pt.getSlaveConnectTimeout(), TimeUnit.SECONDS);
            slave.getPod().ifPresent(sp -> sp.getSpec().getEphemeralContainers().add(ec));
            LOGGER.fine(() -> "Ephemeral Container started: " + containerName);
        } catch (KubernetesClientException toe) {
            String status = PodUtils.getContainerStatus(podResource.get(), containerName).map(cs -> cs.getState().toString()).orElse("status unknown");
            throw new AbortException("Ephemeral container failed to start after " + pt.getSlaveConnectTimeout() + " seconds: " + status);
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
        Run run = getContext().get(Run.class);
        if (run != null && listener != null) {
            rcEnvVars = run.getEnvironment(listener);
        }

        decorator = new ContainerExecDecorator();
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
        return false;
    }

    @Override
    public void stop(@NonNull Throwable cause) throws Exception {
        LOGGER.fine("Stopping ephemeral container step.");
        closeQuietly(getContext(), decorator);
        terminateEphemeralContainer(getContext(), decorator.getContainerName());
    }

    private void setDefaultRunAsUser(ContainerTemplate template) throws IOException, InterruptedException {
        Launcher launcher = getContext().get(Launcher.class);
        if (launcher != null && launcher.isUnix()) {
            ByteArrayOutputStream userId = new ByteArrayOutputStream();
            launcher.launch().cmds("id", "-u").quiet(true).stdout(userId).start().joinWithTimeout(60, TimeUnit.SECONDS, launcher.getListener());

            ByteArrayOutputStream groupId = new ByteArrayOutputStream();
            launcher.launch().cmds("id", "-g").quiet(true).stdout(groupId).start().joinWithTimeout(60, TimeUnit.SECONDS, launcher.getListener());

            final String charsetName = Charset.defaultCharset().name();
            template.setRunAsUser(userId.toString(charsetName).trim());
            template.setRunAsGroup(groupId.toString(charsetName).trim());
        }
    }

    private static void terminateEphemeralContainer(StepContext context, String containerName) throws Exception {
        LOGGER.fine(() -> "Removing ephemeral container: " + containerName);
        KubernetesNodeContext nodeContext = new KubernetesNodeContext(context);
        KubernetesClient client = nodeContext.connectToCloud();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PodResource resource = client.pods().withName(nodeContext.getPodName());
        try (ExecWatch watch = new EphemeralContainerAwarePodOperations(client)
                .withName(nodeContext.getPodName())
                .inContainer(containerName)
                .redirectingInput()
                .writingOutput(out)
                .writingError(out)
                .withTTY()
                .exec("touch", "/tmp/" + containerName + "-jenkins-step-is-done-monitor")) {
            resource.waitUntilCondition(new EphemeralContainerStartedCondition(containerName, false), 10, TimeUnit.SECONDS);
            LOGGER.finest(() -> "ephemeral container stopped: " + containerName);
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "failed to terminate ephemeral container: " + containerName, ex);
        }

        LOGGER.finest(() -> {
            Pod pod = resource.get();
            ContainerStatus status = pod.getStatus().getEphemeralContainerStatuses().stream()
                    .filter(cs -> cs.getName().equals(containerName))
                    .findFirst().orElse(null);
            return "Ephemeral container status after step: " + containerName + " -> " + status;
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

    private static class EphemeralContainerStartedCondition implements Predicate<Pod> {
        private final String containerName;
        private final boolean running;

        EphemeralContainerStartedCondition(String containerName, boolean running) {
            this.containerName = containerName;
            this.running = running;
        }

        @Override
        public boolean test(Pod pod) {
            return pod.getStatus().getEphemeralContainerStatuses().stream()
                    .filter(status -> status.getName().equals(containerName))
                    .anyMatch(status -> {
                        LOGGER.finest(() -> "Ephemeral Container state: " + status.getState());
                        if (running) {
                            return status.getState().getRunning() != null;
                        } else {
                            return status.getState().getTerminated() != null;
                        }
                    });
        }
    }

    /**
     * Temp hack to enable ephemeral container support with the kubernetes client. Can be removed
     * when support is added to the client library natively.
     * <p>
     * <a href="https://github.com/fabric8io/kubernetes-client/pull/4763">Pending Pull Request</a>
     */
    private static class EphemeralPodOperations extends PodOperationsImpl {

        private String podName;

        static PodResource forPod(Client client, String podName) {
            return new EphemeralPodOperations(client, podName).withName(podName +  "/ephemeralcontainers");
        }

        EphemeralPodOperations(Client clientContext, String podName) {
            super(clientContext);
            this.podName = podName;
        }

        EphemeralPodOperations(PodOperationContext podOperationContext, OperationContext operationContext, String podName) {
            super(podOperationContext, operationContext);
            this.podName = podName;
        }

        @Override
        public PodOperationsImpl newInstance(OperationContext context) {
            return new EphemeralPodOperations(getContext(), context, podName);
        }

        @Override
        protected <T> String checkName(T item) {
            return getName();
        }
    }
}
