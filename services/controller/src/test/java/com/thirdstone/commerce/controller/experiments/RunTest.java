package com.thirdstone.commerce.controller.experiments;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class RunTest {

    @Test
    void aStoredRunReadsBackWithItsEvidenceAndTimeline() {
        JsonMapper json = JsonMapper.builder().findAndAddModules().build();
        Run run = new Run();
        run.id = "r1";
        run.verdict = Run.Verdict.FAILED;
        run.check("ledger audit", false, "double charge");
        Run back = json.readValue(json.writeValueAsBytes(new Run[]{run}), Run[].class)[0];
        assertThat(back.checks).singleElement().satisfies(c -> assertThat(c.detail()).isEqualTo("double charge"));
        assertThat(back.timeline).hasSize(1);
        assertThat(back.verdict).isEqualTo(Run.Verdict.FAILED);
    }
}
