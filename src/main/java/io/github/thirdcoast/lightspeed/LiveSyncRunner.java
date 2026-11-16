package io.github.thirdcoast.lightspeed;

import io.github.thirdcoast.qbo.JsonHttp;
import io.github.thirdcoast.qbo.LiveSyncJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;

import static io.github.thirdcoast.qbo.LiveSyncJob.required;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "sync.mock-enabled", havingValue = "false")
class LiveSyncRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LiveSyncRunner.class);
    private final Environment environment;

    LiveSyncRunner(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        var dates = args.getOptionValues("sync-date");
        if (args.containsOption("sync-date") && dates.size() != 1) {
            throw new IllegalArgumentException("Use --sync-date=YYYY-MM-DD");
        }
        var oneShot = "-".equals(environment.getRequiredProperty("sync.cron"));
        if (dates != null && !oneShot) throw new IllegalArgumentException("Disable SYNC_CRON for a one-date run");
        if (oneShot) execute(dates == null ? null : LocalDate.parse(dates.get(0)));
    }

    @Scheduled(cron = "${sync.cron}", zone = "${sync.timezone}")
    public void scheduledRun() {
        execute(null);
    }

    private void execute(LocalDate date) {
        java.util.function.Function<String, String> config = environment::getProperty;
        var zone = ZoneId.of(environment.getRequiredProperty("sync.timezone"));
        var provider = new LightspeedSalesClient(new JsonHttp(), required(config, "LIGHTSPEED_SHOP"),
                required(config, "LIGHTSPEED_ACCESS_TOKEN"), required(config, "LIGHTSPEED_OUTLET_ID"), zone);
        new LiveSyncJob(config, provider, zone,
                Path.of(environment.getRequiredProperty("sync.data-directory")), log::info).run(date);
    }
}
