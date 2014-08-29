package com.signalfuse.codahale.reporter;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.TimeUnit;
import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.ScheduledReporter;
import com.codahale.metrics.Timer;
import com.signalfuse.metrics.SourceNameHelper;
import com.signalfuse.metrics.auth.AuthToken;
import com.signalfuse.metrics.auth.StaticAuthToken;
import com.signalfuse.metrics.connection.DataPointReceiverFactory;
import com.signalfuse.metrics.connection.HttpDataPointProtobufReceiverFactory;
import com.signalfuse.metrics.endpoint.DataPointEndpoint;
import com.signalfuse.metrics.endpoint.DataPointReceiverEndpoint;
import com.signalfuse.metrics.errorhandler.OnSendErrorHandler;
import com.signalfuse.metrics.flush.AggregateMetricSender;
import com.signalfuse.metrics.protobuf.SignalFuseProtocolBuffers;

/**
 * Reporter object for codahale metrics that reports values to com.signalfuse.signalfuse at some
 * interval.
 */
public class SignalFuseReporter extends ScheduledReporter {
    private final AggregateMetricSender aggregateMetricSender;
    private final Set<MetricDetails> detailsToAdd;
    private final MetricMetadata metricMetadata;
    private final Map<Metric, Long> hardCounterValueCache;

    protected SignalFuseReporter(MetricRegistry registry, String name, MetricFilter filter,
                                 TimeUnit rateUnit, TimeUnit durationUnit,
                                 AggregateMetricSender aggregateMetricSender,
                                 Set<MetricDetails> detailsToAdd,
                                 MetricMetadata metricMetadata) {
        super(registry, name, filter, rateUnit, durationUnit);
        this.aggregateMetricSender = aggregateMetricSender;
        this.detailsToAdd = detailsToAdd;
        this.metricMetadata = metricMetadata;
        this.hardCounterValueCache = new HashMap<Metric, Long>();
    }

    @Override
    public void report(SortedMap<String, Gauge> gauges, SortedMap<String, Counter> counters,
                       SortedMap<String, Histogram> histograms, SortedMap<String, Meter> meters,
                       SortedMap<String, Timer> timers) {
        AggregateMetricSenderSessionWrapper session = new AggregateMetricSenderSessionWrapper(
                aggregateMetricSender.createSession(), Collections.unmodifiableSet(detailsToAdd), metricMetadata,
                aggregateMetricSender.getDefaultSourceName(), hardCounterValueCache);
        try {
            for (Map.Entry<String, Gauge> entry : gauges.entrySet()) {
                session.addMetric(entry.getValue(), entry.getKey(),
                        SignalFuseProtocolBuffers.MetricType.GAUGE, entry.getValue().getValue());
            }
            for (Map.Entry<String, Counter> entry : counters.entrySet()) {
                if (entry.getValue() instanceof IncrementalCounter) {
                    session.addMetric(entry.getValue(), entry.getKey(),
                            SignalFuseProtocolBuffers.MetricType.COUNTER,
                            ((IncrementalCounter)entry.getValue()).getCountChange());
                } else {
                    session.addMetric(entry.getValue(), entry.getKey(),
                            SignalFuseProtocolBuffers.MetricType.CUMULATIVE_COUNTER,
                            entry.getValue().getCount());
                }
            }
            for (Map.Entry<String, Histogram> entry : histograms.entrySet()) {
                session.addHistogram(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, Meter> entry : meters.entrySet()) {
                session.addMetered(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, Timer> entry : timers.entrySet()) {
                session.addTimer(entry.getKey(), entry.getValue());
            }
        } finally {
            try {
                session.close();
            } catch (Exception e) {
                // Unable to register... these exceptions handled by AggregateMetricSender
            }
        }
    }

    public MetricMetadata getMetricMetadata() {
        return metricMetadata;
    }

    public enum MetricDetails {
        // For {@link com.codahale.metrics.Sampling}
        MEDIAN("median"),
        PERCENT_75("75th"),
        PERCENT_95("95th"),
        PERCENT_98("98th"),
        PERCENT_99("99th"),
        PERCENT_999("999th"),
        MAX("max"),
        MIN("min"),
        STD_DEV("stddev"),
        MEAN("mean"),

        // For {@link com.codahale.metrics.Counting}
        COUNT("count"),

        // For {@link com.codahale.metrics.Metered}
        RATE_MEAN("rate.mean"),
        RATE_1_MIN("rate.1min"),
        RATE_5_MIN("rate.5min"),
        RATE_15_MIN("rate.15min");
        public static final Set<MetricDetails> ALL = Collections.unmodifiableSet(EnumSet.allOf(MetricDetails.class));
        private final String description;

        MetricDetails(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public static final class Builder {
        private final MetricRegistry registry;
        private String defaultSourceName;
        private AuthToken authToken;
        private DataPointReceiverEndpoint dataPointEndpoint = new DataPointEndpoint();
        private String name = "signalfuse-reporter";
        private int timeoutMs = HttpDataPointProtobufReceiverFactory.DEFAULT_TIMEOUT_MS;
        private DataPointReceiverFactory dataPointReceiverFactory = new
                HttpDataPointProtobufReceiverFactory(dataPointEndpoint);
        private MetricFilter filter = MetricFilter.ALL;
        private TimeUnit rateUnit = TimeUnit.SECONDS;
        private TimeUnit durationUnit = TimeUnit.MILLISECONDS; // Maybe nano eventually?
        private Set<MetricDetails> detailsToAdd = MetricDetails.ALL;
        private Collection<OnSendErrorHandler> onSendErrorHandlerCollection = Collections.emptyList();
        private MetricMetadata metricMetadata = new MetricMetadataImpl();

        public Builder(MetricRegistry registry, String authToken) {
            this(registry, new StaticAuthToken(authToken));
        }

        public Builder(MetricRegistry registry, AuthToken authToken) {
            this(registry, authToken, SourceNameHelper.getDefaultSourceName());
        }

        public Builder(MetricRegistry registry, AuthToken authToken, String defaultSourceName) {
            this.registry = registry;
            this.authToken = authToken;
            this.defaultSourceName = defaultSourceName;
        }

        public Builder setDefaultSourceName(String defaultSourceName) {
            this.defaultSourceName = defaultSourceName;
            return this;
        }

        public Builder setAuthToken(AuthToken authToken) {
            this.authToken = authToken;
            return this;
        }

        public Builder setDataPointEndpoint(DataPointReceiverEndpoint dataPointEndpoint) {
            this.dataPointEndpoint = dataPointEndpoint;
            this.dataPointReceiverFactory =
                    new HttpDataPointProtobufReceiverFactory(dataPointEndpoint)
                            .setTimeoutMs(this.timeoutMs);
            return this;
        }

        public Builder setName(String name) {
            this.name = name;
            return this;
        }

        public Builder setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
            this.dataPointReceiverFactory =
                    new HttpDataPointProtobufReceiverFactory(dataPointEndpoint)
                            .setTimeoutMs(this.timeoutMs);
            return this;
        }

        public Builder setDataPointReceiverFactory(
                DataPointReceiverFactory dataPointReceiverFactory) {
            this.dataPointReceiverFactory = dataPointReceiverFactory;
            return this;
        }

        public Builder setFilter(MetricFilter filter) {
            this.filter = filter;
            return this;
        }

        public Builder setRateUnit(TimeUnit rateUnit) {
            this.rateUnit = rateUnit;
            return this;
        }

        public Builder setDurationUnit(TimeUnit durationUnit) {
            this.durationUnit = durationUnit;
            return this;
        }

        public Builder setDetailsToAdd(Set<MetricDetails> detailsToAdd) {
            this.detailsToAdd = detailsToAdd;
            return this;
        }

        public Builder setOnSendErrorHandlerCollection(
                Collection<OnSendErrorHandler> onSendErrorHandlerCollection) {
            this.onSendErrorHandlerCollection = onSendErrorHandlerCollection;
            return this;
        }

        public Builder setMetricMetadata(MetricMetadata metricMetadata) {
            this.metricMetadata = metricMetadata;
            return this;
        }

        public SignalFuseReporter build() {
            AggregateMetricSender aggregateMetricSender = new AggregateMetricSender(
                    defaultSourceName, dataPointReceiverFactory, authToken, onSendErrorHandlerCollection);
            return new SignalFuseReporter(registry, name, filter, rateUnit, durationUnit,
                    aggregateMetricSender, detailsToAdd, metricMetadata);
        }
    }
}
