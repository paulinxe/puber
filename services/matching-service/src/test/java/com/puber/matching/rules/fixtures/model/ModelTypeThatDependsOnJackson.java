package com.puber.matching.rules.fixtures.model;

import tools.jackson.databind.JsonNode;

/**
 * A domain type carrying a transport concern.
 *
 * <p>Jackson 3 specifically. The rule used to enumerate banned packages and named {@code
 * com.fasterxml.jackson..} -- Jackson 2, which is not on the classpath at all -- while {@code
 * tools.jackson..}, the one that is, went unchallenged. This fixture is that hole.
 */
public final class ModelTypeThatDependsOnJackson {

    public String textOf(JsonNode node) {
        return node.asString();
    }
}
