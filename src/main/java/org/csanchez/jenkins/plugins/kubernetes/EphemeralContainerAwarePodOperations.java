package org.csanchez.jenkins.plugins.kubernetes;

import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.Client;
import io.fabric8.kubernetes.client.dsl.*;
import io.fabric8.kubernetes.client.dsl.internal.OperationContext;
import io.fabric8.kubernetes.client.dsl.internal.PodOperationContext;
import io.fabric8.kubernetes.client.dsl.internal.core.v1.PodOperationsImpl;
import org.csanchez.jenkins.plugins.kubernetes.pipeline.ContainerExecDecorator;

/**
 * Hacked version of PodOperations that supports ephemeral containers. This can be removed once the client api
 * natively supports this feature. https://github.com/fabric8io/kubernetes-client/pull/4763
 */
public class EphemeralContainerAwarePodOperations extends PodOperationsImpl {

    public EphemeralContainerAwarePodOperations(Client clientContext) {
        super(clientContext);
    }

    EphemeralContainerAwarePodOperations(PodOperationContext podOperationContext, OperationContext operationContext) {
        super(podOperationContext, operationContext);
    }

    @Override
    public PodOperationsImpl newInstance(OperationContext context) {
        return new EphemeralContainerAwarePodOperations(getContext(), context);
    }

    @Override
    public PodOperationsImpl inContainer(String containerId) {
        PodOperationsImpl ops = super.inContainer(containerId);
        return new EphemeralContainerAwarePodOperations(ops.getContext(), this.context);
    }

    @Override
    public PodOperationsImpl redirectingInput(Integer bufferSize) {
        PodOperationsImpl ops = super.redirectingInput(bufferSize);
        return new EphemeralContainerAwarePodOperations(ops.getContext(), this.context);
    }

    @Override
    public TtyExecErrorable writingOutput(OutputStream out) {
        PodOperationsImpl ops = (PodOperationsImpl) super.writingOutput(out);
        return new EphemeralContainerAwarePodOperations(ops.getContext(), this.context);
    }

    @Override
    public TtyExecErrorChannelable writingError(OutputStream err) {
        PodOperationsImpl ops = (PodOperationsImpl) super.writingError(err);
        return new EphemeralContainerAwarePodOperations(ops.getContext(), this.context);
    }

    @Override
    public Execable usingListener(ExecListener execListener) {
        PodOperationsImpl ops = (PodOperationsImpl) super.usingListener(execListener);
        return new EphemeralContainerAwarePodOperations(ops.getContext(), this.context);
    }

    @Override
    public ExecListenable withTTY() {
        PodOperationsImpl ops = (PodOperationsImpl) super.withTTY();
        return new EphemeralContainerAwarePodOperations(ops.getContext(), this.context);
    }

    @Override
    public Pod require() {
        return patchPodEphemeralContainers(super.require());
    }

    @Override
    public Pod waitUntilCondition(Predicate<Pod> condition, long amount, TimeUnit timeUnit) {
        return patchPodEphemeralContainers(super.waitUntilCondition(condition, amount, timeUnit));
    }

    private Pod patchPodEphemeralContainers(Pod p) {
        // hack to make ephemeral container appear to be a container
        p.getSpec().getEphemeralContainers().forEach(ec -> {
            p.getSpec().getContainers().add(new ContainerBuilder().withName(ec.getName()).build());
        });

        return p;
    }

}
