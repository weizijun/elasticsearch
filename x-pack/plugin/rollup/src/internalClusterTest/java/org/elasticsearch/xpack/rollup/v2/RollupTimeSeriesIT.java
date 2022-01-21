/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.rollup.v2;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.time.DateUtils;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xpack.core.rollup.RollupActionConfig;
import org.elasticsearch.xpack.core.rollup.RollupActionDateHistogramGroupConfig;
import org.elasticsearch.xpack.core.rollup.RollupActionGroupConfig;
import org.elasticsearch.xpack.core.rollup.job.HistogramGroupConfig;
import org.elasticsearch.xpack.core.rollup.job.MetricConfig;
import org.elasticsearch.xpack.core.rollup.job.TermsGroupConfig;
import org.junit.Before;

public class RollupTimeSeriesIT extends RollupIntegTestCase {
    @Before
    public void init() {
        client().admin()
            .indices()
            .prepareCreate(index)
            .setSettings(
                Settings.builder()
                    .put("index.number_of_shards", randomIntBetween(1, 3))
                    .put(IndexSettings.MODE.getKey(), "time_series")
                    .put(IndexMetadata.INDEX_ROUTING_PATH.getKey(), "categorical_1")
                    .put(IndexSettings.TIME_SERIES_START_TIME.getKey(), 1L)
                    .put(IndexSettings.TIME_SERIES_END_TIME.getKey(), DateUtils.MAX_MILLIS_BEFORE_9999-1)
                    .build()
            )
            .setMapping(
                "{\n"
                    + "  \"properties\": {\n"
                    + "    \"@timestamp\": {\n"
                    + "      \"type\": \"date\"\n"
                    + "    },\n"
                    + "    \"numeric_1\": {\n"
                    + "      \"type\": \"double\"\n"
                    + "    },\n"
                    + "    \"numeric_2\": {\n"
                    + "      \"type\": \"float\"\n"
                    + "    },\n"
                    + "    \"categorical_1\": {\n"
                    + "      \"type\": \"keyword\",\n"
                    + "      \"time_series_dimension\": true\n"
                    + "    },\n"
                    + "    \"categorical_2\": {\n"
                    + "      \"type\": \"keyword\",\n"
                    + "      \"time_series_dimension\": true\n"
                    + "    }\n"
                    + "  }\n"
                    + "}"
            )
            .get();
    }

    public void testNormalTermsGrouping() throws IOException {
        RollupActionDateHistogramGroupConfig dateHistogramGroupConfig = randomRollupActionDateHistogramGroupConfig("@timestamp");
        SourceSupplier sourceSupplier = () -> XContentFactory.jsonBuilder()
            .startObject()
            .field("@timestamp", randomDateForInterval(dateHistogramGroupConfig.getInterval()))
            .field("categorical_1", randomAlphaOfLength(1))
            .field("categorical_2", randomAlphaOfLength(1))
            .field("numeric_1", randomDouble())
            .endObject();
        RollupActionConfig config = new RollupActionConfig(
            new RollupActionGroupConfig(dateHistogramGroupConfig, null, new TermsGroupConfig("categorical_1")),
            Collections.singletonList(new MetricConfig("numeric_1", Collections.singletonList("max")))
        );
        bulkIndex(sourceSupplier);
        rollup(index, rollupIndex, config);

        RollupActionConfig newConfig = new RollupActionConfig(
            new RollupActionGroupConfig(dateHistogramGroupConfig, null, new TermsGroupConfig("categorical_1")),
            Collections.singletonList(new MetricConfig("numeric_1", Collections.singletonList("max")))
        );
        assertRollupIndex(newConfig, index, rollupIndex);
    }

    public void testTsidTermsGrouping() throws IOException {
        RollupActionDateHistogramGroupConfig dateHistogramGroupConfig = randomRollupActionDateHistogramGroupConfig("@timestamp");
        SourceSupplier sourceSupplier = () -> XContentFactory.jsonBuilder()
            .startObject()
            .field("@timestamp", randomDateForInterval(dateHistogramGroupConfig.getInterval()))
            .field("categorical_1", randomAlphaOfLength(1))
            .field("categorical_2", randomAlphaOfLength(1))
            .field("numeric_1", randomDouble())
            .endObject();
        RollupActionConfig config = new RollupActionConfig(
            new RollupActionGroupConfig(dateHistogramGroupConfig, null, new TermsGroupConfig("_tsid")),
            Collections.singletonList(new MetricConfig("numeric_1", List.of("max", "min", "value_count", "avg")))
        );
        bulkIndex(sourceSupplier);
        rollup(index, rollupIndex, config);

        RollupActionConfig newConfig = new RollupActionConfig(
            new RollupActionGroupConfig(dateHistogramGroupConfig, null, new TermsGroupConfig("categorical_1", "categorical_2")),
            Collections.singletonList(new MetricConfig("numeric_1", List.of("max", "min", "value_count", "avg")))
        );
        assertRollupIndex(newConfig, index, rollupIndex);
    }

    public void testTsidAndOtherTerms() throws IOException {
        RollupActionDateHistogramGroupConfig dateHistogramGroupConfig = randomRollupActionDateHistogramGroupConfig("@timestamp");
        SourceSupplier sourceSupplier = () -> XContentFactory.jsonBuilder()
            .startObject()
            .field("@timestamp", randomDateForInterval(dateHistogramGroupConfig.getInterval()))
            .field("categorical_1", randomAlphaOfLength(1))
            .field("categorical_2", randomAlphaOfLength(1))
            .field("numeric_1", randomDouble())
            .endObject();
        RollupActionConfig config = new RollupActionConfig(
            new RollupActionGroupConfig(dateHistogramGroupConfig, null, new TermsGroupConfig("_tsid", "categorical_1")),
            Collections.singletonList(new MetricConfig("numeric_1", Collections.singletonList("max")))
        );
        bulkIndex(sourceSupplier);
        rollup(index, rollupIndex, config);

        RollupActionConfig newConfig = new RollupActionConfig(
            new RollupActionGroupConfig(dateHistogramGroupConfig, null, new TermsGroupConfig("categorical_1", "categorical_2")),
            Collections.singletonList(new MetricConfig("numeric_1", Collections.singletonList("max")))
        );
        assertRollupIndex(newConfig, index, rollupIndex);
    }
}
