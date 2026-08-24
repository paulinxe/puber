package com.puber.matching.rules.fixtures.model;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * A domain type annotated by the framework.
 *
 * <p>The sibling fixture proves the rule catches a framework type in a method signature. This one
 * covers the form the leak actually arrives in -- {@code @Entity}, {@code @Table},
 * {@code @JsonProperty} -- where the domain type's own shape is unchanged and only an annotation
 * betrays it. {@code @Autowired} stands in because it is on the classpath and retained in the class
 * file; the rule does not care which framework it is.
 */
public final class ModelTypeCarryingAFrameworkAnnotation {

    @Autowired public String injectedByTheFramework;
}
