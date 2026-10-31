package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.ScheduledTaskHolder;

class JobsDisabledIT extends AbstractIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void noJobIsRegisteredWhenJobsAreOff() {
        assertThat(context.getBeansOfType(ScheduledTaskHolder.class).values())
                .allSatisfy(holder -> assertThat(holder.getScheduledTasks()).isEmpty());
    }
}
