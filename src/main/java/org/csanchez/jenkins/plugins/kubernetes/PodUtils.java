/*
 * Copyright 2020 CloudBees, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.csanchez.jenkins.plugins.kubernetes;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Util;
import hudson.model.Queue;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.EphemeralContainer;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import jenkins.model.Jenkins;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;

public final class PodUtils {
    private static final Logger LOGGER = Logger.getLogger(PodUtils.class.getName());

    public static final Predicate<ContainerStatus> CONTAINER_IS_TERMINATED = cs -> cs.getState().getTerminated() != null;
    public static final Predicate<ContainerStatus> CONTAINER_IS_WAITING = cs -> cs.getState().getWaiting() != null;

    public static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*");

    @NonNull
    public static List<ContainerStatus> getTerminatedContainers(Pod pod) {
        return getContainers(pod, CONTAINER_IS_TERMINATED);
    }

    public static List<ContainerStatus> getWaitingContainers(Pod pod) {
        return getContainers(pod, CONTAINER_IS_WAITING);
    }

    /**
     * Get all container statuses (does not include ephemeral or init containers).
     * @param pod pod to get container statuses for
     * @return list of statuses, possibly empty, never null
     */
    @NonNull
    public static List<ContainerStatus> getContainerStatus(@NonNull Pod pod) {
        PodStatus podStatus = pod.getStatus();
        if (podStatus == null) {
            return Collections.emptyList();
        }
        return podStatus.getContainerStatuses();
    }

    /**
     * Get pod container statuses (does not include ephemeral or init containers) that match the given filter.
     * @param pod pod to get container statuses for
     * @param predicate container status predicate
     * @return list of statuses, possibly empty, never null
     */
    public static List<ContainerStatus> getContainers(@NonNull Pod pod, Predicate<ContainerStatus> predicate) {
        return getContainerStatus(pod).stream().filter(predicate).collect(Collectors.toList());
    }

    /**
     * Lookup container status (either main container or ephemeral container).
     * @param pod pod resource to inspect
     * @param containerName container to locate
     * @return container status if found
     */
    public static Optional<ContainerStatus> getContainerStatus(Pod pod, String containerName) {
        if (pod == null) {
            return Optional.empty();
        }

        PodStatus podStatus = pod.getStatus();
        if (podStatus == null) {
            return Optional.empty();
        }

        Predicate<ContainerStatus> finder = cs -> StringUtils.equals(cs.getName(), containerName);
        Optional<ContainerStatus> status = podStatus.getContainerStatuses()
                .stream()
                .filter(finder)
                .findFirst();

        if (!status.isPresent()) {
            status = podStatus.getEphemeralContainerStatuses()
                    .stream()
                    .filter(finder)
                    .findFirst();
        }

        return status;
    }

    /**
     * Lookup pod container working dir
     * @param pod pod to inspect
     * @param containerName container to search for
     * @return optional working dir if container found and working dir not null
     */
    public static Optional<String> getContainerWorkingDir(Pod pod, String containerName) {
        if (pod == null) {
            return Optional.empty();
        }

        Optional<String> containerWorkingDir = pod.getSpec().getContainers().stream()
                .filter(c -> StringUtils.equals(c.getName(), containerName))
                .findAny()
                .map(Container::getWorkingDir);

        if (!containerWorkingDir.isPresent()) {
            containerWorkingDir = pod.getSpec().getEphemeralContainers().stream()
                    .filter(c -> StringUtils.equals(c.getName(), containerName))
                    .findAny()
                    .map(EphemeralContainer::getWorkingDir);
        }

        return containerWorkingDir;
    }

    /**
     * Cancel queue items matching the given pod.
     * It uses the annotation "runUrl" added to the pod to do the matching.
     *
     * It uses the current thread context to list item queues,
     * so make sure to be in the right context before calling this method.
     *
     * @param pod The pod to cancel items for.
     * @param reason The reason the item are being cancelled.
     */
    public static void cancelQueueItemFor(Pod pod, String reason) {
        Queue q = Jenkins.get().getQueue();
        boolean cancelled = false;
        ObjectMeta metadata = pod.getMetadata();
        if (metadata == null) {
            return;
        }
        Map<String, String> annotations = metadata.getAnnotations();
        if (annotations == null) {
            LOGGER.log(Level.FINE, "Pod .metadata.annotations is null: {0}/{1}", new Object[] {metadata.getNamespace(), metadata.getName()});
            return;
        }
        String runUrl = annotations.get("runUrl");
        if (runUrl == null) {
            LOGGER.log(Level.FINE, "Pod .metadata.annotations.runUrl is null: {0}/{1}", new Object[] {metadata.getNamespace(), metadata.getName()});
            return;
        }
        for (Queue.Item item: q.getItems()) {
            Queue.Task task = item.task;
            if (runUrl.equals(task.getUrl())) {
                LOGGER.log(Level.FINE, "Cancelling queue item: \"{0}\"\n{1}",
                        new Object[]{ task.getDisplayName(), !StringUtils.isBlank(reason) ? "due to " + reason : ""});
                q.cancel(item);
                cancelled = true;
                break;
            }
        }
        if (!cancelled) {
            LOGGER.log(Level.FINE, "No queue item found for pod: {0}/{1}", new Object[] {metadata.getNamespace(), metadata.getName()});
        }
    }

    @CheckForNull
    public static String logLastLines(@NonNull Pod pod, @NonNull KubernetesClient client) {
        PodStatus status = pod.getStatus();
        ObjectMeta metadata = pod.getMetadata();
        if (status == null || metadata == null) {
             return null;
        }
        String podName = metadata.getName();
        String namespace = metadata.getNamespace();
        List<ContainerStatus> containers = status.getContainerStatuses();
        StringBuilder sb = new StringBuilder();
        if (containers != null) {
            for (ContainerStatus containerStatus : containers) {
                sb.append("\n");
                sb.append("- ");
                sb.append(containerStatus.getName());
                if (containerStatus.getState().getTerminated() != null) {
                    sb.append(" -- terminated (");
                    sb.append(containerStatus.getState().getTerminated().getExitCode());
                    sb.append(")");
                }
                if (containerStatus.getState().getRunning() != null) {
                    sb.append(" -- running");
                }
                if (containerStatus.getState().getWaiting() != null) {
                    sb.append(" -- waiting");
                }
                sb.append("\n");
                try {
                    String log = client.pods()
                            .inNamespace(namespace)
                            .withName(podName)
                            .inContainer(containerStatus.getName())
                            .tailingLines(30)
                            .getLog();
                    sb.append("-----Logs-------------\n");
                    sb.append(log);
                    sb.append("\n");
                } catch (KubernetesClientException e) {
                    LOGGER.log(Level.FINE, e, () -> namespace + "/" + podName + " Unable to retrieve container logs as the pod is already gone");
                }
            }
        }
        return Util.fixEmpty(sb.toString());
    }

    /**
     * Generate a random string to be used as the suffix for dynamic resource names.
     * @return random string suitable for kubernetes resources
     */
    @NonNull
    public static String generateRandomSuffix() {
        return RandomStringUtils.random(5, "bcdfghjklmnpqrstvwxz0123456789");
    }

    /**
     * Create kubernetes resource name with a random suffix appended to the given base name. This method
     * performs some basic transforms to make the base name compliant (i.e. spaces and underscores). The
     * returned string will also be truncated to a max of 63 characters.
     * @param name base name to append to
     * @return resource name with random suffix and maximum length of 63 characters
     */
    @NonNull
    public static String createNameWithRandomSuffix(String name) {
        String suffix = generateRandomSuffix();
        // no spaces
        name = name.replaceAll("[ _]", "-").toLowerCase(Locale.getDefault());
        // keep it under 63 chars (62 is used to account for the '-')
        name = name.substring(0, Math.min(name.length(), 62 - suffix.length()));
        return String.join("-", name, suffix);
    }

    /**
     * Check if the given name is a valid pod resource name. Does not validate string length.
     * @param name name to check
     * @return true if the given string contains valid pod resource name characters
     */
    public static boolean isValidName(@NonNull String name) {
        return PodUtils.NAME_PATTERN.matcher(name).matches();
    }
}
