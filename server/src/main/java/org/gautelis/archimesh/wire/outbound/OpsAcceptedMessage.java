package org.gautelis.archimesh.wire.outbound;

import org.gautelis.archimesh.model.RevisionRange;

public record OpsAcceptedMessage(String opBatchId, long baseRevision, RevisionRange assignedRevisionRange) {
}
