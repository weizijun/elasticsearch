/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.core.rollup.action;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

public class RollupShardStatus implements Task.Status {
    public static final String NAME = "rollup-index-shard";
    private static final ParseField SHARD_FIELD = new ParseField("shard");
    private static final ParseField STATUS_FIELD = new ParseField("status");
    private static final ParseField RUNNING_TIME_FIELD = new ParseField("running_time");
    private static final ParseField IN_NUM_DOCS_RECEIVED_FIELD = new ParseField("in_num_docs_received");
    private static final ParseField IN_NUM_DOCS_SKIPPED_FIELD = new ParseField("in_num_docs_skipped");
    private static final ParseField OUT_NUM_DOCS_SENT_FIELD = new ParseField("out_num_docs_sent");
    private static final ParseField OUT_NUM_DOCS_INDEXED_FIELD = new ParseField("out_num_docs_indexed");
    private static final ParseField OUT_NUM_DOCS_FAILED_FIELD = new ParseField("out_num_docs_failed");

    private final ShardId shardId;
    private final long rollupStart;
    private Status status;
    private AtomicLong numReceived;
    private AtomicLong numSkip;
    private AtomicLong numSent;
    private AtomicLong numIndexed;
    private AtomicLong numFailed;

    public RollupShardStatus(StreamInput in) throws IOException {
        shardId = new ShardId(in);
        status = in.readEnum(Status.class);
        rollupStart = in.readLong();
        numReceived = new AtomicLong(in.readLong());
        numSkip = new AtomicLong(in.readLong());
        numSent = new AtomicLong(in.readLong());
        numIndexed = new AtomicLong(in.readLong());
        numFailed = new AtomicLong(in.readLong());
    }

    public RollupShardStatus(ShardId shardId) {
        status = Status.INIT;
        this.shardId = shardId;
        this.rollupStart = System.currentTimeMillis();
    }

    public void init(
        AtomicLong numReceived,
        AtomicLong numSkip,
        AtomicLong numSent,
        AtomicLong numIndexed,
        AtomicLong numFailed
    ) {
        this.numReceived = numReceived;
        this.numSkip = numSkip;
        this.numSent = numSent;
        this.numIndexed = numIndexed;
        this.numFailed = numFailed;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder,
        Params params) throws IOException {
        builder.startObject();
        builder.field(SHARD_FIELD.getPreferredName(), shardId);
        builder.field(STATUS_FIELD.getPreferredName(), status);
        builder.field(RUNNING_TIME_FIELD.getPreferredName(), TimeValue.timeValueMillis(System.currentTimeMillis()-rollupStart));
        builder.field(IN_NUM_DOCS_RECEIVED_FIELD.getPreferredName(), numReceived.get());
        builder.field(IN_NUM_DOCS_SKIPPED_FIELD.getPreferredName(), numSkip.get());
        builder.field(OUT_NUM_DOCS_SENT_FIELD.getPreferredName(), numSent.get());
        builder.field(OUT_NUM_DOCS_INDEXED_FIELD.getPreferredName(), numIndexed.get());
        builder.field(OUT_NUM_DOCS_FAILED_FIELD.getPreferredName(), numFailed.get());
        builder.endObject();
        return builder;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        shardId.writeTo(out);
        out.writeEnum(status);
        out.writeLong(rollupStart);
        out.writeLong(numReceived.get());
        out.writeLong(numSkip.get());
        out.writeLong(numSent.get());
        out.writeLong(numIndexed.get());
        out.writeLong(numFailed.get());
    }

    public enum Status {
        INIT,
        ROLLING,
        STOP,
        ABORT
    }

    public void setNumSent(AtomicLong numSent) {
        this.numSent = numSent;
    }

    public void setNumIndexed(AtomicLong numIndexed) {
        this.numIndexed = numIndexed;
    }

    public void setNumFailed(AtomicLong numFailed) {
        this.numFailed = numFailed;
    }
}
