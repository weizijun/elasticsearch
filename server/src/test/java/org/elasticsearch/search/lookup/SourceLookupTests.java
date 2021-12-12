/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.lookup;

import java.io.IOException;

import junit.framework.TestCase;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xcontent.json.JsonXContent;

import static org.hamcrest.Matchers.equalTo;

public class SourceLookupTests extends ESTestCase {
    public void testBasicFilterBytes() throws IOException {
        SourceLookup sourceLookup = new SourceLookup();
        XContentBuilder builder = JsonXContent.contentBuilder()
            .startObject()
            .field("field1", "value1")
            .field("field2", "value2")
            .field("field3", "value3")
            .endObject();
        BytesReference source = BytesReference.bytes(builder);
        sourceLookup.setSource(source);
        FetchSourceContext fetchSourceContext = new FetchSourceContext(true, new String[] {"field1", "field2"}, new String[] {"field2"});
        BytesReference filterResult = sourceLookup.filterBytes(fetchSourceContext);
        assertThat(filterResult.utf8ToString(), equalTo("{\"field1\":\"value1\"}"));
    }

    public void testFilterEmptyResult() throws IOException {
        SourceLookup sourceLookup = new SourceLookup();
        XContentBuilder builder = JsonXContent.contentBuilder()
            .startObject()
            .field("field1", "value1")
            .endObject();
        BytesReference source = BytesReference.bytes(builder);
        sourceLookup.setSource(source);
        FetchSourceContext fetchSourceContext = new FetchSourceContext(true, null, new String[] {"field1"});
        BytesReference filterResult = sourceLookup.filterBytes(fetchSourceContext);
        assertThat(filterResult.utf8ToString(), equalTo("{}"));
    }

    public void testFilterEmptySource() {
        SourceLookup sourceLookup = new SourceLookup();
        FetchSourceContext fetchSourceContext = new FetchSourceContext(true, null, null);
        BytesReference filterResult = sourceLookup.filterBytes(fetchSourceContext);
        assertThat(filterResult.utf8ToString(), equalTo("{}"));
    }

    public void testFilterBadSource() {
        SourceLookup sourceLookup = new SourceLookup();
        sourceLookup.setSource(new BytesArray("bad_source"));
        sourceLookup.setSourceContentType(XContentType.JSON);
        FetchSourceContext fetchSourceContext = new FetchSourceContext(true, null, null);
        Exception e = expectThrows(ElasticsearchException.class, ()->sourceLookup.filterBytes(fetchSourceContext));
        assertThat(e.getMessage(), equalTo("Error filtering source"));
    }

}
