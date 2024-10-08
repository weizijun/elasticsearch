/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.threadpool;

import org.elasticsearch.test.ESTestCase;

import java.util.Map;

public abstract class ESThreadPoolTestCase extends ESTestCase {

    protected final ThreadPool.Info info(final ThreadPool threadPool, final String name) {
        for (final ThreadPool.Info info : threadPool.info()) {
            if (info.getName().equals(name)) {
                return info;
            }
        }
        return fail(null, "unknown threadpool name: " + name);
    }

    protected final ThreadPoolStats.Stats stats(final ThreadPool threadPool, final String name) {
        for (final ThreadPoolStats.Stats stats : threadPool.stats()) {
            if (name.equals(stats.name())) {
                return stats;
            }
        }
        return fail(null, "unknown threadpool name: " + name);
    }

    protected final void terminateThreadPoolIfNeeded(final ThreadPool threadPool) {
        if (threadPool != null) {
            terminate(threadPool);
        }
    }

    static String randomThreadPool(final ThreadPool.ThreadPoolType type) {
        return randomFrom(
            ThreadPool.THREAD_POOL_TYPES.entrySet().stream().filter(t -> t.getValue().equals(type)).map(Map.Entry::getKey).sorted().toList()
        );
    }

}
