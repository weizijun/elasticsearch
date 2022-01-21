/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.rollup.v2;

import java.io.IOException;
import java.util.Collections;
import java.util.Locale;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.alias.Alias;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.InvalidIndexNameException;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xpack.core.rollup.RollupActionConfig;
import org.elasticsearch.xpack.core.rollup.RollupActionDateHistogramGroupConfig;
import org.elasticsearch.xpack.core.rollup.RollupActionGroupConfig;
import org.elasticsearch.xpack.core.rollup.action.RollupAction;
import org.elasticsearch.xpack.core.rollup.action.RollupShardStatus;
import org.elasticsearch.xpack.core.rollup.job.HistogramGroupConfig;
import org.elasticsearch.xpack.core.rollup.job.MetricConfig;
import org.elasticsearch.xpack.core.rollup.job.TermsGroupConfig;
import org.elasticsearch.xpack.rollup.v2.indexer.UnSortedRollupShardIndexer;
import org.junit.Before;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class RollupActionIT extends RollupIntegTestCase {

    @Before
    public void init() {
        client().admin()
            .indices()
            .prepareCreate(index)
            .setSettings(Settings.builder().put("index.number_of_shards", randomIntBetween(1, 3)).build())
            .setMapping(
                "date_1",
                "type=date",
                "numeric_1",
                "type=double",
                "numeric_2",
                "type=float",
                "numeric_nonaggregatable",
                "type=double,doc_values=false",
                "categorical_1",
                "type=keyword"
            )
            .get();
    }

    public void testRollupShardIndexerCleansTempFiles() throws IOException {
        // create rollup config and index documents into source index
        RollupActionDateHistogramGroupConfig dateHistogramGroupConfig = randomRollupActionDateHistogramGroupConfig("date_1");
        SourceSupplier sourceSupplier = () -> XContentFactory.jsonBuilder()
            .startObject()
            .field("date_1", randomDateForInterval(dateHistogramGroupConfig.getInterval()))
            .field("categorical_1", randomAlphaOfLength(1))
            .field("numeric_1", randomDouble())
            .endObject();
        RollupActionConfig config = new RollupActionConfig(
            new RollupActionGroupConfig(dateHistogramGroupConfig, null, new TermsGroupConfig("categorical_1")),
            Collections.singletonList(new MetricConfig("numeric_1", Collections.singletonList("max")))
        );
        bulkIndex(sourceSupplier);

        IndicesService indexServices = getInstanceFromNode(IndicesService.class);
        Index srcIndex = resolveIndex(index);
        IndexService indexService = indexServices.indexServiceSafe(srcIndex);
        IndexShard shard = indexService.getShard(0);

        // re-use source index as temp index for test
        UnSortedRollupShardIndexer indexer = new UnSortedRollupShardIndexer(
            new RollupShardStatus(shard.shardId()),
            client(),
            indexService,
            shard.shardId(),
            config,
            index,
            2
        );
        indexer.execute();
        // assert that files are deleted
        assertThat(indexer.tmpFilesDeleted, equalTo(indexer.tmpFiles));
    }

    public void testCannotRollupToExistingIndex() throws Exception {
        RollupActionDateHistogramGroupConfig dateHistogramGroupConfig = randomRollupActionDateHistogramGroupConfig("date_1");
        SourceSupplier sourceSupplier = () -> XContentFactory.jsonBuilder()
            .startObject()
            .field("date_1", randomDateForInterval(dateHistogramGroupConfig.getInterval()))
            .field("categorical_1", randomAlphaOfLength(1))
            .field("numeric_1", randomDouble())
            .endObject();
        RollupActionConfig config = new RollupActionConfig(
            new RollupActionGroupConfig(dateHistogramGroupConfig, null, new TermsGroupConfig("categorical_1")),
            Collections.singletonList(new MetricConfig("numeric_1", Collections.singletonList("max")))
        );
        bulkIndex(sourceSupplier);
        rollup(index, rollupIndex, config);
        assertRollupIndex(config, index, rollupIndex);
        ElasticsearchException exception = expectThrows(ElasticsearchException.class, () -> rollup(index, rollupIndex, config));
        assertThat(exception.getMessage(), containsString("Invalid index name [" + rollupIndex + "], rollup index already exists"));
    }

    public void testCannotRollupToExistingAlias() {
        RollupActionDateHistogramGroupConfig dateHistogramGroupConfig = randomRollupActionDateHistogramGroupConfig("date_1");
        RollupActionConfig config = new RollupActionConfig(
            new RollupActionGroupConfig(dateHistogramGroupConfig, null, new TermsGroupConfig("categorical_1")),
            Collections.singletonList(new MetricConfig("numeric_1", Collections.singletonList("max")))
        );
        String aliasName = randomAlphaOfLength(6).toLowerCase(Locale.ROOT);
        client().admin()
            .indices()
            .prepareCreate(randomAlphaOfLength(6).toLowerCase(Locale.ROOT))
            .setSettings(Settings.builder().put("index.number_of_shards", 1).build())
            .addAlias(new Alias(aliasName))
            .get();

        InvalidIndexNameException exception = expectThrows(InvalidIndexNameException.class, () -> rollup(index, aliasName, config));
        assertThat(exception.getMessage(), equalTo("Invalid index name [" + aliasName + "], rollup index already exists as alias"));
    }

    public void testCannotRollupToExistingDataStream() throws Exception {
        RollupActionDateHistogramGroupConfig dateHistogramGroupConfig = randomRollupActionDateHistogramGroupConfig("date_1");
        RollupActionConfig config = new RollupActionConfig(
            new RollupActionGroupConfig(dateHistogramGroupConfig, null, new TermsGroupConfig("categorical_1")),
            Collections.singletonList(new MetricConfig("numeric_1", Collections.singletonList("max")))
        );
        String datsStreamName = createDataStream();
        InvalidIndexNameException exception = expectThrows(InvalidIndexNameException.class, () -> rollup(index, datsStreamName, config));
        assertThat(
            exception.getMessage(),
            equalTo("Invalid index name [" + datsStreamName + "], rollup index already exists as data stream")
        );
    }

    public void testTemporaryIndexCannotBeCreatedAlreadyExists() {
        RollupActionDateHistogramGroupConfig dateHistogramGroupConfig = randomRollupActionDateHistogramGroupConfig("date_1");
        RollupActionConfig config = new RollupActionConfig(
            new RollupActionGroupConfig(dateHistogramGroupConfig, null, new TermsGroupConfig("categorical_1")),
            Collections.singletonList(new MetricConfig("numeric_1", Collections.singletonList("max")))
        );
        assertTrue(client().admin().indices().prepareCreate(".rolluptmp-" + rollupIndex).get().isAcknowledged());
        Exception exception = expectThrows(ElasticsearchException.class, () -> rollup(index, rollupIndex, config));
        assertThat(exception.getMessage(), containsString("already exists"));
    }

    public void testCannotRollupWhileOtherRollupInProgress() throws Exception {
        RollupActionDateHistogramGroupConfig dateHistogramGroupConfig = randomRollupActionDateHistogramGroupConfig("date_1");
        SourceSupplier sourceSupplier = () -> XContentFactory.jsonBuilder()
            .startObject()
            .field("date_1", randomDateForInterval(dateHistogramGroupConfig.getInterval()))
            .field("categorical_1", randomAlphaOfLength(1))
            .field("numeric_1", randomDouble())
            .endObject();
        RollupActionConfig config = new RollupActionConfig(
            new RollupActionGroupConfig(dateHistogramGroupConfig, null, new TermsGroupConfig("categorical_1")),
            Collections.singletonList(new MetricConfig("numeric_1", Collections.singletonList("max")))
        );
        bulkIndex(sourceSupplier);
        client().execute(RollupAction.INSTANCE, new RollupAction.Request(index, rollupIndex, config), ActionListener.wrap(() -> {}));
        ResourceAlreadyExistsException exception = expectThrows(
            ResourceAlreadyExistsException.class,
            () -> rollup(index, rollupIndex, config)
        );
        assertThat(exception.getMessage(), containsString(".rolluptmp-" + rollupIndex));
    }

    public void testTermsGrouping() throws IOException {
        RollupActionDateHistogramGroupConfig dateHistogramGroupConfig = randomRollupActionDateHistogramGroupConfig("date_1");
        SourceSupplier sourceSupplier = () -> XContentFactory.jsonBuilder()
            .startObject()
            .field("date_1", randomDateForInterval(dateHistogramGroupConfig.getInterval()))
            .field("categorical_1", randomAlphaOfLength(1))
            .field("numeric_1", randomDouble())
            .endObject();
        RollupActionConfig config = new RollupActionConfig(
            new RollupActionGroupConfig(dateHistogramGroupConfig, null, new TermsGroupConfig("categorical_1")),
            Collections.singletonList(new MetricConfig("numeric_1", Collections.singletonList("max")))
        );
        bulkIndex(sourceSupplier);
        rollup(index, rollupIndex, config);
        assertRollupIndex(config, index, rollupIndex);
    }

    public void testHistogramGrouping() throws IOException {
        long interval = randomLongBetween(1, 1000);
        RollupActionDateHistogramGroupConfig dateHistogramGroupConfig = randomRollupActionDateHistogramGroupConfig("date_1");
        SourceSupplier sourceSupplier = () -> XContentFactory.jsonBuilder()
            .startObject()
            .field("date_1", randomDateForInterval(dateHistogramGroupConfig.getInterval()))
            .field("numeric_1", randomDoubleBetween(0.0, 10000.0, true))
            .field("numeric_2", randomDouble())
            .endObject();
        RollupActionConfig config = new RollupActionConfig(
            new RollupActionGroupConfig(dateHistogramGroupConfig, new HistogramGroupConfig(interval, "numeric_1"), null),
            Collections.singletonList(new MetricConfig("numeric_2", Collections.singletonList("max")))
        );
        bulkIndex(sourceSupplier);
        rollup(index, rollupIndex, config);
        assertRollupIndex(config, index, rollupIndex);
    }

    public void testMaxMetric() throws IOException {
        RollupActionDateHistogramGroupConfig dateHistogramGroupConfig = randomRollupActionDateHistogramGroupConfig("date_1");
        SourceSupplier sourceSupplier = () -> XContentFactory.jsonBuilder()
            .startObject()
            .field("date_1", randomDateForInterval(dateHistogramGroupConfig.getInterval()))
            .field("numeric_1", randomDouble())
            .endObject();
        RollupActionConfig config = new RollupActionConfig(
            new RollupActionGroupConfig(dateHistogramGroupConfig, null, null),
            Collections.singletonList(new MetricConfig("numeric_1", Collections.singletonList("max")))
        );
        bulkIndex(sourceSupplier);
        rollup(index, rollupIndex, config);
        assertRollupIndex(config, index, rollupIndex);
    }

    public void testMinMetric() throws IOException {
        RollupActionDateHistogramGroupConfig dateHistogramGroupConfig = randomRollupActionDateHistogramGroupConfig("date_1");
        SourceSupplier sourceSupplier = () -> XContentFactory.jsonBuilder()
            .startObject()
            .field("date_1", randomDateForInterval(dateHistogramGroupConfig.getInterval()))
            .field("numeric_1", randomDouble())
            .endObject();
        RollupActionConfig config = new RollupActionConfig(
            new RollupActionGroupConfig(dateHistogramGroupConfig, null, null),
            Collections.singletonList(new MetricConfig("numeric_1", Collections.singletonList("min")))
        );
        bulkIndex(sourceSupplier);
        rollup(index, rollupIndex, config);
        assertRollupIndex(config, index, rollupIndex);
    }

    public void testValueCountMetric() throws IOException {
        RollupActionDateHistogramGroupConfig dateHistogramGroupConfig = randomRollupActionDateHistogramGroupConfig("date_1");
        SourceSupplier sourceSupplier = () -> XContentFactory.jsonBuilder()
            .startObject()
            .field("date_1", randomDateForInterval(dateHistogramGroupConfig.getInterval()))
            .field("numeric_1", randomDouble())
            .endObject();
        RollupActionConfig config = new RollupActionConfig(
            new RollupActionGroupConfig(dateHistogramGroupConfig, null, null),
            Collections.singletonList(new MetricConfig("numeric_1", Collections.singletonList("value_count")))
        );
        bulkIndex(sourceSupplier);
        rollup(index, rollupIndex, config);
        assertRollupIndex(config, index, rollupIndex);
    }

    public void testAvgMetric() throws IOException {
        RollupActionDateHistogramGroupConfig dateHistogramGroupConfig = randomRollupActionDateHistogramGroupConfig("date_1");
        SourceSupplier sourceSupplier = () -> XContentFactory.jsonBuilder()
            .startObject()
            .field("date_1", randomDateForInterval(dateHistogramGroupConfig.getInterval()))
            // Use integers to ensure that avg is comparable between rollup and original
            .field("numeric_1", randomInt())
            .endObject();
        RollupActionConfig config = new RollupActionConfig(
            new RollupActionGroupConfig(dateHistogramGroupConfig, null, null),
            Collections.singletonList(new MetricConfig("numeric_1", Collections.singletonList("avg")))
        );
        bulkIndex(sourceSupplier);
        rollup(index, rollupIndex, config);
        assertRollupIndex(config, index, rollupIndex);
    }

    public void testValidationCheck() throws IOException {
        RollupActionDateHistogramGroupConfig dateHistogramGroupConfig = randomRollupActionDateHistogramGroupConfig("date_1");
        SourceSupplier sourceSupplier = () -> XContentFactory.jsonBuilder()
            .startObject()
            .field("date_1", randomDateForInterval(dateHistogramGroupConfig.getInterval()))
            // use integers to ensure that avg is comparable between rollup and original
            .field("numeric_nonaggregatable", randomInt())
            .endObject();
        RollupActionConfig config = new RollupActionConfig(
            new RollupActionGroupConfig(dateHistogramGroupConfig, null, null),
            Collections.singletonList(new MetricConfig("numeric_nonaggregatable", Collections.singletonList("avg")))
        );
        bulkIndex(sourceSupplier);
        Exception e = expectThrows(Exception.class, () -> rollup(index, rollupIndex, config));
        assertThat(e.getMessage(), containsString("The field [numeric_nonaggregatable] must be aggregatable"));
    }

    public void testRollupDatastream() throws Exception {
        RollupActionDateHistogramGroupConfig dateHistogramGroupConfig = randomRollupActionDateHistogramGroupConfig(timestampFieldName);
        String dataStreamName = createDataStream();

        SourceSupplier sourceSupplier = () -> XContentFactory.jsonBuilder()
            .startObject()
            .field(timestampFieldName, randomDateForInterval(dateHistogramGroupConfig.getInterval()))
            .field("numeric_1", randomDouble())
            .endObject();
        RollupActionConfig config = new RollupActionConfig(
            new RollupActionGroupConfig(dateHistogramGroupConfig, null, null),
            Collections.singletonList(new MetricConfig("numeric_1", Collections.singletonList("value_count")))
        );
        bulkIndex(dataStreamName, sourceSupplier);

        String oldIndexName = rollover(dataStreamName).getOldIndex();
        String rollupIndexName = ".rollup-" + oldIndexName;
        rollup(oldIndexName, rollupIndexName, config);
        assertRollupIndex(config, oldIndexName, rollupIndexName);
        rollup(oldIndexName, rollupIndexName + "-2", config);
        assertRollupIndex(config, oldIndexName, rollupIndexName + "-2");
    }

    public void testWildCardRollup() throws IOException {
        RollupActionDateHistogramGroupConfig dateHistogramGroupConfig = randomRollupActionDateHistogramGroupConfig("date_1");
        SourceSupplier sourceSupplier = () -> XContentFactory.jsonBuilder()
            .startObject()
            .field("date_1", randomDateForInterval(dateHistogramGroupConfig.getInterval()))
            .field("categorical_1", randomAlphaOfLength(1))
            .field("numeric_1", randomDouble())
            .endObject();
        RollupActionConfig config = new RollupActionConfig(
            new RollupActionGroupConfig(dateHistogramGroupConfig, null, new TermsGroupConfig("cate*")),
            Collections.singletonList(new MetricConfig("num*_1", Collections.singletonList("max")))
        );
        bulkIndex(sourceSupplier);
        rollup(index, rollupIndex, config);

        RollupActionConfig newConfig = new RollupActionConfig(
            new RollupActionGroupConfig(dateHistogramGroupConfig, null, new TermsGroupConfig("categorical_1")),
            Collections.singletonList(new MetricConfig("numeric_1", Collections.singletonList("max")))
        );
        assertRollupIndex(newConfig, index, rollupIndex);
    }

}
