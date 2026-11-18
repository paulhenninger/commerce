package com.thirdstone.commerce.controller.experiments;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One run of one experiment: what was claimed, what happened, and the verdict. */
public class Run {

    public enum Verdict { RUNNING, PASSED, FAILED, NOT_RUN, NOT_APPLICABLE }

    public record Event(Instant at, String text) {
    }

    public record Check(String name, boolean passed, String detail) {
    }

    public String id;
    public String experiment;
    public String title;
    public String hypothesis;
    public Instant startedAt;
    public Instant endedAt;
    public Verdict verdict = Verdict.RUNNING;
    public List<Event> timeline = new ArrayList<>();
    public List<Check> checks = new ArrayList<>();

    public Run() {
    }

    Run(String id, Experiment e) {
        this.id = id;
        this.experiment = e.name();
        this.title = e.title();
        this.hypothesis = e.hypothesis();
        this.startedAt = Instant.now();
    }

    synchronized void log(String text) {
        timeline.add(new Event(Instant.now(), text));
    }

    synchronized void check(String name, boolean passed, String detail) {
        checks.add(new Check(name, passed, detail));
        log((passed ? "PASS " : "FAIL ") + name + (detail == null || detail.isEmpty() ? "" : ": " + detail));
    }

    public String markdown() {
        StringBuilder md = new StringBuilder();
        md.append("## ").append(title).append(" — ").append(verdict).append("\n\n");
        md.append("*").append(hypothesis).append("*\n\n");
        md.append("Run ").append(id).append(", ").append(startedAt).append(" to ").append(endedAt).append("\n\n");
        md.append("| Check | Result | Detail |\n|---|---|---|\n");
        for (Check c : checks) {
            md.append("| ").append(c.name()).append(" | ").append(c.passed() ? "pass" : "**FAIL**").append(" | ")
                    .append(c.detail() == null ? "" : c.detail().replace("|", "/").replace("\n", " ")).append(" |\n");
        }
        md.append("\nTimeline:\n\n");
        for (Event e : timeline) {
            md.append("- ").append(e.at()).append(" ").append(e.text()).append("\n");
        }
        return md.toString();
    }
}
