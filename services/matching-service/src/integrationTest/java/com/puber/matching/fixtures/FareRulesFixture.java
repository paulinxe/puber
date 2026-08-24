package com.puber.matching.fixtures;

import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

public final class FareRulesFixture {

    private static final Resource BASELINE = new ClassPathResource("fixtures/fare_rules.sql");

    private FareRulesFixture() {}

    public static void reseed(DataSource dataSource) {
        new ResourceDatabasePopulator(BASELINE).execute(dataSource);
    }
}
