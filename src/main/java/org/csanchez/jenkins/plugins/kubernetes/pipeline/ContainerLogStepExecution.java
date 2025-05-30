/*
 * Copyright (C) 2017 Original Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.csanchez.jenkins.plugins.kubernetes.pipeline;

import hudson.model.TaskListener;
import hudson.util.LogTaskListener;
import io.fabric8.kubernetes.client.dsl.ContainerResource;
import io.fabric8.kubernetes.client.dsl.TailPrettyLoggable;
import io.fabric8.kubernetes.client.dsl.TimeTailPrettyLoggable;
import java.io.IOException;
import java.io.PrintStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;

public class ContainerLogStepExecution extends SynchronousNonBlockingStepExecution<String> {
    private static final long serialVersionUID = 5588861066775717487L;
    private static final Logger LOGGER = Logger.getLogger(ContainerLogStepExecution.class.getName());

    private final ContainerLogStep step;

    ContainerLogStepExecution(ContainerLogStep step, StepContext context) {
        super(context);
        this.step = step;
    }

    private PrintStream logger() {
        TaskListener l = null;
        StepContext context = getContext();
        try {
            l = context.get(TaskListener.class);
        } catch (IOException | InterruptedException x) {
            LOGGER.log(Level.WARNING, "Failed to find TaskListener in context");
        } finally {
            if (l == null) {
                l = new LogTaskListener(LOGGER, Level.FINE);
            }
        }
        return l.getLogger();
    }

    @Override
    protected String run() throws Exception {
        boolean returnLog = step.isReturnLog();
        String containerName = step.getName();
        int tailingLines = step.getTailingLines();
        int sinceSeconds = step.getSinceSeconds();
        int limitBytes = step.getLimitBytes();

        try {
            LOGGER.log(Level.FINE, "Starting containerLog step.");

            KubernetesNodeContext nodeContext = new KubernetesNodeContext(getContext());

            String podName = nodeContext.getPodName();
            ContainerResource containerResource = nodeContext.getPodResource().inContainer(containerName);
            TimeTailPrettyLoggable limited =
                    limitBytes > 0 ? containerResource.limitBytes(limitBytes) : containerResource;

            TailPrettyLoggable since = sinceSeconds > 0 ? limited.sinceSeconds(sinceSeconds) : limited;

            String log = (tailingLines > 0 ? since.tailingLines(tailingLines) : since).getLog();

            if (returnLog) {
                return log;
            } else {
                logger().println("> start log of container '" + containerName + "' in pod '" + podName + "'");
                logger().print(log);
                if (log.length() > 0 && log.charAt(log.length() - 1) != '\n') {
                    logger().println();
                }
                logger().println("> end log of container '" + containerName + "' in pod '" + podName + "'");
            }

            return "";
        } catch (InterruptedException e) {
            logger().println("Interrupted while getting logs of container");
            LOGGER.log(Level.FINE, "interrupted while getting logs of container {1}", containerName);
            return "";
        } catch (Exception e) {
            String message = "Failed to get logs for container";
            logger().println(message);
            LOGGER.log(Level.WARNING, message, e);
            return "";
        }
    }

    @Override
    public void stop(Throwable cause) throws Exception {
        LOGGER.log(Level.FINE, "Stopping container log step.");
        super.stop(cause);
    }
}
