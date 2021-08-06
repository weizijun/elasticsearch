/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.indices;

import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.test.ESIntegTestCase;

import java.util.Collections;
import java.util.Map;

public class TimeSeriesModeIT extends ESIntegTestCase {
    public void testDynamicDimensionField() {
        String index = "index";
        Map<String, String> indexSettings = Collections.singletonMap("mode", "time_series");
        String mappings = "{\n" +
            "    \"dynamic_templates\": [\n" +
            "      {\n" +
            "        \"integers\": {\n" +
            "          \"match_mapping_type\": \"string\",\n" +
            "          \"match\": \"new_field\",\n" +
            "          \"mapping\": {\n" +
            "            \"type\": \"keyword\",\n" +
            "            \"doc_values\" : true,\n" +
            "            \"dimension\": true\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "    ],\n" +
            "    \"properties\": {\n" +
            "      \"field\": {\n" +
            "        \"type\": \"keyword\",\n" +
            "        \"doc_values\" : true,\n" +
            "        \"dimension\": true\n" +
            "      },\n" +
            "      \"@timestamp\" : {\n" +
            "        \"type\": \"date\"\n" +
            "      }\n" +
            "    }\n" +
            "  }";
        client().admin().indices().prepareCreate(index).setSettings(indexSettings).setMapping(mappings).get();

        String source = "{\n" +
            "  \"field\" : \"d1\",\n" +
            "  \"new_field\" : \"d2\"\n" +
            "}";

        IndexResponse indexResponse = client().prepareIndex(index).setSource(source, XContentType.JSON).get();
        assertEquals(RestStatus.CREATED, indexResponse.status());
        IndexResponse indexResponse2 = client().prepareIndex(index).setSource(source, XContentType.JSON).get();
        assertEquals(RestStatus.CREATED, indexResponse2.status());

        client().admin().indices().prepareRefresh(index).get();
        SearchResponse searchResponse = client().prepareSearch(index).addDocValueField("_tsid").addDocValueField("field").get();
        assertEquals(searchResponse.getHits().getTotalHits().value, 2);
        Map<String, Object> tsid1 = searchResponse.getHits().getHits()[0].field("_tsid").getValue();
        Map<String, Object> tsid2 = searchResponse.getHits().getHits()[1].field("_tsid").getValue();
        assertEquals(tsid1.size(), tsid2.size());
        assertEquals(tsid1.get("field"), tsid2.get("field"));
        assertEquals(tsid1.get("new_field"), tsid2.get("new_field"));
    }
}
