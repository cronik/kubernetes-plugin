package org.csanchez.jenkins.plugins.kubernetes.pipeline;

import static org.csanchez.jenkins.plugins.kubernetes.pipeline.Resources.closeQuietly;

import java.io.Closeable;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.StepContext;

/**
 * Pipeline body execution callback that quietly closes all referenced {@link Closeable}.
 */
@SuppressFBWarnings("SE_BAD_FIELD")
class CloseableExecCallback extends BodyExecutionCallback.TailCall {

    private static final long serialVersionUID = 6385838254761750483L;

    private final Closeable[] closeables;

    public CloseableExecCallback(@NonNull Closeable... closeables) {
        this.closeables = closeables;
    }
    @Override
    public void finished(StepContext context) {
        closeQuietly(context, closeables);
    }
}