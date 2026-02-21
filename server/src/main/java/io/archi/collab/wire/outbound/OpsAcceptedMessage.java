package io.archi.collab.wire.outbound;

import io.archi.collab.model.RevisionRange;

public record OpsAcceptedMessage(String opBatchId, long baseRevision, RevisionRange assignedRevisionRange) {
}
