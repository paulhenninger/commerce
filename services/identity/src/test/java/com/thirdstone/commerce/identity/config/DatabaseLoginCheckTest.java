package com.thirdstone.commerce.identity.config;

import com.thirdstone.commerce.identity.config.DatabaseLoginCheck.Login;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A deployment must not run as a database login that can change the schema. The SQL
 * that reads the login's rights runs against real MySQL in MySqlMigrationTest.
 */
class DatabaseLoginCheckTest {

    @Test
    void aLoginThatOnlyReadsAndWritesRowsPasses() {
        assertThat(DatabaseLoginCheck.problems(new Login("commerce_app@%", false, false, 0))).isEmpty();
    }

    @Test
    void theOwnerRootOrARoleIsRefusedWithEveryProblemListed() {
        assertThat(DatabaseLoginCheck.problems(new Login("commerce_owner@%", false, true, 0)))
                .singleElement().asString().contains("commerce_owner@%", "change this database's tables");
        assertThat(DatabaseLoginCheck.problems(new Login("root@%", true, true, 0))).hasSize(2)
                .anySatisfy(p -> assertThat(p).contains("whole server"));
        assertThat(DatabaseLoginCheck.problems(new Login("commerce_app@%", false, false, 1)))
                .singleElement().asString().contains("active role");
    }
}
