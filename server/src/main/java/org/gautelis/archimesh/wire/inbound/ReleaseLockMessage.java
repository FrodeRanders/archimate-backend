package org.gautelis.archimesh.wire.inbound;

import org.gautelis.archimesh.model.Actor;

import java.util.List;

public record ReleaseLockMessage(Actor actor, List<String> targets) {
}
