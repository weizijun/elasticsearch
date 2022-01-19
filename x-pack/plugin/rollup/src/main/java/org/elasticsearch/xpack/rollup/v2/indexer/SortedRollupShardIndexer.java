/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.rollup.v2.indexer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.util.RoaringDocIdSet;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.common.util.CancellableThreads.ExecutionCancelledException;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.fielddata.FormattedDocValues;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.search.aggregations.bucket.DocCountProvider;
import org.elasticsearch.xpack.core.rollup.RollupActionConfig;
import org.elasticsearch.xpack.core.rollup.action.RollupShardStatus;
import org.elasticsearch.xpack.core.rollup.action.RollupShardStatus.Status;
import org.elasticsearch.xpack.rollup.v2.indexer.metrics.LeafMetricField;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;
import static org.elasticsearch.xpack.rollup.v2.indexer.RollupShardIndexer.BucketKey.compareGroup;

/**
 * rollup data in sorted mode
 * the sorted mode iterator data sequential to calculate group metrics
 * because the index is a sorted index, and the sorted config is the same with rollup group, so the groups are sequential
 */
public class SortedRollupShardIndexer extends RollupShardIndexer {
    private static final Logger logger = LogManager.getLogger(SortedRollupShardIndexer.class);

    public SortedRollupShardIndexer(
        RollupShardStatus rollupShardStatus,
        Client client,
        IndexService indexService,
        ShardId shardId,
        RollupActionConfig config,
        String tmpIndex
    ) {
        super(rollupShardStatus, client, indexService, shardId, config, tmpIndex);
    }

    @Override
    public void execute() throws IOException {
        long start = System.currentTimeMillis();
        try (searcher; bulkProcessor) {
            Map<Integer, RoaringDocIdSet.Builder> docIds = new HashMap<>();
            searcher.search(new MatchAllDocsQuery(), new DocIdsCollector(docIds));
            long searchTime = System.currentTimeMillis();
            logger.debug("[{}] sorting rollup search cost[{}]", indexShard.shardId(), (searchTime - start));

            List<SegmentRollupContext> segmentRollupContexts = new ArrayList<>(docIds.size());
            for (LeafReaderContext context : searcher.getIndexReader().leaves()) {
                RoaringDocIdSet.Builder builder = docIds.get(context.ord);
                RoaringDocIdSet docIdSet = builder.build();
                DocIdSetIterator iterator = docIdSet.iterator();

                FormattedDocValues timestampField = timestampFetcher.getGroupLeaf(context);
                final FormattedDocValues[] groupFieldLeaves = leafGroupFetchers(context);
                final LeafMetricField[] metricsFields = leafMetricFields(context);

                DocCountProvider docCountProvider = new DocCountProvider();
                docCountProvider.setLeafReaderContext(context);

                segmentRollupContexts.add(
                    new SegmentRollupContext(
                        context.ord,
                        iterator,
                        timestampField,
                        groupFieldLeaves,
                        metricsFields,
                        docCountProvider,
                        iterator.nextDoc()
                    )
                );
            }

            if (segmentRollupContexts.size() == 1) {
                computeBucketSingeSegment(segmentRollupContexts.get(0));
            } else {
                computeBucketMultiSegments(segmentRollupContexts.toArray(new SegmentRollupContext[0]));
            }
            bulkProcessor.flush();
        }

        if (status.getStatus() == Status.ABORT) {
            logger.warn(
                "[{}] rolling abort, sent [{}], indexed [{}], failed[{}]",
                indexShard.shardId(),
                numIndexed.get(),
                numIndexed.get(),
                numFailed.get()
            );
            throw new ExecutionCancelledException("[" + indexShard.shardId() + "] rollup cancelled");
        }

        logger.info(
            "sorted rollup execute [{}], cost [{}], Successfully sent [{}], indexed [{}], failed[{}]",
            indexShard.shardId(),
            (System.currentTimeMillis() - start),
            numSent.get(),
            numIndexed.get(),
            numFailed.get()
        );
        status.setStatus(Status.STOP);
        return;
    }

    private void computeBucketMultiSegments(SegmentRollupContext[] segmentRollupContexts) throws IOException {
        long startTime = System.currentTimeMillis();
        final AtomicReference<BucketKey> currentKey = new AtomicReference<>();
        final AtomicInteger docCount = new AtomicInteger(0);
        final AtomicInteger keyCount = new AtomicInteger(0);
        Long nextBucket = Long.MIN_VALUE;

        while (true) {
            if (isCanceled()) {
                return;
            }

            SegmentRollupContext[] currentGroupSegments = getCurrentGroupSegments(segmentRollupContexts);
            if (currentGroupSegments.length == 0) {
                break;
            }

            while (true) {
                for (SegmentRollupContext segmentRollupContext : currentGroupSegments) {
                    for (; segmentRollupContext.currentDocId != NO_MORE_DOCS; segmentRollupContext.currentDocId =
                        segmentRollupContext.iterator.nextDoc(), segmentRollupContext.currentGroupFields = null) {
                        numReceived.incrementAndGet();

                        Long timestamp = getTimeStampValue(segmentRollupContext);
                        if (timestamp == null) {
                            continue;
                        }

                        if (segmentRollupContext.currentGroupFields == null) {
                            segmentRollupContext.currentGroupFields = getGroupFields(segmentRollupContext);
                        }

                        if (currentKey.get() == null) {
                            Long currentBucket = rounding.round(timestamp);
                            nextBucket = rounding.nextRoundingValue(currentBucket);
                            currentKey.set(new BucketKey(currentBucket, segmentRollupContext.currentGroupFields));
                        } else {
                            if (false == Objects.equals(currentKey.get().groupFields, segmentRollupContext.currentGroupFields)
                                || timestamp >= nextBucket
                                || timestamp < currentKey.get().timestamp) {
                                break;
                            }
                        }

                        collectMetrics(segmentRollupContext, (count) -> docCount.addAndGet(count));
                    }
                }

                boolean index = indexCurrentBucket(currentKey, docCount, keyCount);
                if (index == false) {
                    break;
                }
            }
        }

        logger.debug(
            "[{}] rollup build total bucket cost[{}], keyCount [{}]",
            indexShard.shardId(),
            (System.currentTimeMillis() - startTime),
            keyCount
        );
    }


    private void computeBucketMultiSegmentsOld(SegmentRollupContext[] segmentRollupContexts) throws IOException {
        long startTime = System.currentTimeMillis();
        BucketKey currentKey = null;
        final AtomicInteger docCount = new AtomicInteger(0);
        final AtomicInteger keyCount = new AtomicInteger(0);
        Long nextBucket = Long.MIN_VALUE;

        while (true) {
            if (isCanceled()) {
                return;
            }

            List<SegmentRollupContext> currentGroupSegments = getCurrentGroupSegments(segmentRollupContexts);
            if (currentGroupSegments.size() == 0) {
                break;
            }

            while (true) {
                for (SegmentRollupContext segmentRollupContext : currentGroupSegments) {
                    for (; segmentRollupContext.currentDocId != NO_MORE_DOCS; segmentRollupContext.currentDocId = segmentRollupContext.iterator.nextDoc()) {
                        numReceived.incrementAndGet();

                        Long timestamp = getTimeStampValue(segmentRollupContext);
                        if (timestamp == null) {
                            continue;
                        }

                        if (segmentRollupContext.currentGroupFields == null) {
                            segmentRollupContext.currentGroupFields = getGroupFields(segmentRollupContext);
                        }

                        List<Object> groupFields;
                        if (segmentRollupContext.currentGroupFields != null) {
                            groupFields = segmentRollupContext.currentGroupFields;
                            segmentRollupContext.currentGroupFields = null;
                        } else {
                            groupFields = getGroupFields(segmentRollupContext);
                        }

                        if (currentKey == null) {
                            Long currentBucket = rounding.round(timestamp);
                            nextBucket = rounding.nextRoundingValue(currentBucket);
                            currentKey = new BucketKey(currentBucket, groupFields);
                        } else {
                            if (false == Objects.equals(currentKey.groupFields, groupFields)) {
                                // store group fields
                                segmentRollupContext.currentGroupFields = groupFields;
                                break;
                            } else if (timestamp >= nextBucket || timestamp < currentKey.timestamp) {
                                break;
                            }
                        }

                        collectMetrics(segmentRollupContext, (count) -> docCount.addAndGet(count));
                    }
                }

                boolean index = indexCurrentBucket(currentKey, docCount, keyCount);
                if (index) {
                    currentKey = null;
                } else {
                    break;
                }
            }
        }

        logger.debug(
            "[{}] rollup build total bucket cost[{}], keyCount [{}]",
            indexShard.shardId(),
            (System.currentTimeMillis() - startTime),
            keyCount
        );
    }

    private void computeBucketSingeSegment(SegmentRollupContext segmentRollupContext) throws IOException {
        long startTime = System.currentTimeMillis();
        BucketKey currentKey = null;
        final AtomicInteger docCount = new AtomicInteger(0);
        final AtomicInteger keyCount = new AtomicInteger(0);
        Long nextBucket = Long.MIN_VALUE;

        while (true) {
            if (isCanceled()) {
                return;
            }

            for (; segmentRollupContext.currentDocId != NO_MORE_DOCS; segmentRollupContext.currentDocId = segmentRollupContext.iterator.nextDoc()) {
                numReceived.incrementAndGet();
                Long timestamp = getTimeStampValue(segmentRollupContext);
                if (timestamp == null) {
                    continue;
                }

                List<Object> groupFields = getGroupFields(segmentRollupContext);

                if (currentKey == null) {
                    Long currentBucket = rounding.round(timestamp);
                    nextBucket = rounding.nextRoundingValue(currentBucket);
                    currentKey = new BucketKey(currentBucket, groupFields);
                } else {
                    if (false == Objects.equals(currentKey.groupFields, groupFields)
                        || timestamp >= nextBucket
                        || timestamp < currentKey.timestamp) {
                        break;
                    }
                }

                collectMetrics(segmentRollupContext, (count) -> docCount.addAndGet(count));
            }

            boolean index = indexCurrentBucket(currentKey, docCount, keyCount);
            if (index) {
                currentKey = null;
            } else {
                break;
            }
        }

        logger.debug(
            "[{}] rollup build total bucket cost[{}], keyCount [{}]",
            indexShard.shardId(),
            (System.currentTimeMillis() - startTime),
            keyCount
        );
    }

    private boolean indexCurrentBucket(AtomicReference<BucketKey> currentKey, AtomicInteger docCount, AtomicInteger keyCount) throws IOException {
        if (currentKey != null) {
            indexBucket(currentKey.get(), docCount.get());
            currentKey.set(null);
            docCount.set(0);
            keyCount.incrementAndGet();
            resetMetricCollectors();
            return true;
        } else {
            return false;
        }
    }

    private Long getTimeStampValue(SegmentRollupContext segmentRollupContext) throws IOException {
        Long timestamp = null;
        if (segmentRollupContext.timestampField.advanceExact(segmentRollupContext.currentDocId)) {
            Object obj = segmentRollupContext.timestampField.nextValue();
            if (obj instanceof Number == false) {
                throw new IllegalArgumentException("Expected [Number], got [" + obj.getClass() + "]");
            }
            timestamp = ((Number) obj).longValue();
        }

        if (timestamp == null) {
            logger.debug(
                "[{}] timestamp missing, segment ord [{}], docId [{}]",
                indexShard.shardId(),
                segmentRollupContext.segmentOrd,
                segmentRollupContext.currentDocId
            );
            numSkip.incrementAndGet();
        }

        return timestamp;
    }

    private List<Object> getGroupFields(SegmentRollupContext segmentRollupContext) throws IOException {
        List<Object> groupFields = new ArrayList<>();
        for (FormattedDocValues leafField : segmentRollupContext.leafGroupFields) {
            if (leafField.advanceExact(segmentRollupContext.currentDocId)) {
                groupFields.add(leafField.nextValue());
            }
        }
        return groupFields;
    }

    /**
     * get the segment list of top group value
     */
    private SegmentRollupContext[]  getCurrentGroupSegments(SegmentRollupContext[] segmentRollupContexts) throws IOException {
        List<SegmentRollupContext> currentGroupSegments = new ArrayList<>();
        List<Object> topGroup = null;
        for (SegmentRollupContext segmentRollupContext : segmentRollupContexts) {
            if (segmentRollupContext.currentDocId >= NO_MORE_DOCS) {
                continue;
            }

            if (segmentRollupContext.currentGroupFields == null) {
                segmentRollupContext.currentGroupFields = getGroupFields(segmentRollupContext);
            }

            if (topGroup == null) {
                topGroup = segmentRollupContext.currentGroupFields;
                currentGroupSegments.add(segmentRollupContext);
            } else {
                int compare = compareGroup(topGroup, segmentRollupContext.currentGroupFields);
                if (compare < 0) {
                    topGroup = segmentRollupContext.currentGroupFields;
                    currentGroupSegments.clear();
                    currentGroupSegments.add(segmentRollupContext);
                } else if (compare == 0) {
                    currentGroupSegments.add(segmentRollupContext);
                }
            }
        }

        return currentGroupSegments.toArray(new SegmentRollupContext[0]);
    }

    private void collectMetrics(SegmentRollupContext segmentRollupContext, Consumer<Integer> docCountConsumer) throws IOException {
        int docCount = segmentRollupContext.docCountProvider.getDocCount(segmentRollupContext.currentDocId);
        docCountConsumer.accept(docCount);
        for (LeafMetricField metricField : segmentRollupContext.leafMetricFields) {
            metricField.collectMetric(segmentRollupContext.currentDocId);
        }
    }

    private class DocIdsCollector implements Collector {

        private final Map<Integer, RoaringDocIdSet.Builder> docIds;

        DocIdsCollector(Map<Integer, RoaringDocIdSet.Builder> docIds) {
            this.docIds = docIds;
        }

        @Override
        public LeafCollector getLeafCollector(LeafReaderContext context) {
            RoaringDocIdSet.Builder builder = new RoaringDocIdSet.Builder(context.reader().maxDoc());
            docIds.put(context.ord, builder);
            return new LeafCollector() {

                @Override
                public void setScorer(Scorable scorer) {}

                @Override
                public void collect(int doc) {
                    builder.add(doc);
                }
            };
        }

        @Override
        public ScoreMode scoreMode() {
            return ScoreMode.COMPLETE_NO_SCORES;
        }
    }

    static class SegmentRollupContext {
        final int segmentOrd;
        int currentDocId;
        List<Object> currentGroupFields = null;  // current iterator group
        final DocIdSetIterator iterator;
        boolean iteratorFlag = false;   // iterator flag, to control if needed continue iteration
        final FormattedDocValues timestampField;
        final FormattedDocValues[] leafGroupFields;
        final LeafMetricField[] leafMetricFields;
        final DocCountProvider docCountProvider;  // to get doc count value of current doc id

        SegmentRollupContext(
            int segmentOrd,
            DocIdSetIterator iterator,
            FormattedDocValues timestampField,
            FormattedDocValues[] leafGroupFields,
            LeafMetricField[] leafMetricFields,
            DocCountProvider docCountProvider,
            int currentDocId
        ) {
            this.segmentOrd = segmentOrd;
            this.iterator = iterator;
            this.timestampField = timestampField;
            this.leafGroupFields = leafGroupFields;
            this.leafMetricFields = leafMetricFields;
            this.docCountProvider = docCountProvider;
            this.currentDocId = currentDocId;
        }
    }
}
