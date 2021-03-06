/*
 * Copyright 2016 The Simple File Server Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.sfs.elasticsearch.nodes;

import io.vertx.core.logging.Logger;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.sfs.Server;
import org.sfs.VertxContext;
import org.sfs.elasticsearch.Elasticsearch;
import org.sfs.elasticsearch.Jsonify;
import org.sfs.vo.PersistentServiceDef;
import rx.Observable;
import rx.functions.Func1;

import static io.vertx.core.logging.LoggerFactory.getLogger;
import static java.lang.String.format;
import static org.elasticsearch.common.unit.TimeValue.timeValueMillis;
import static rx.Observable.from;

public class GetServiceDefs implements Func1<Void, Observable<PersistentServiceDef>> {

    private static final Logger LOGGER = getLogger(GetServiceDefs.class);
    private final VertxContext<Server> vertxContext;

    public GetServiceDefs(VertxContext<Server> vertxContext) {
        this.vertxContext = vertxContext;
    }

    @Override
    public Observable<PersistentServiceDef> call(Void aVoid) {

        final Elasticsearch elasticSearch = vertxContext.verticle().elasticsearch();


        SearchRequestBuilder request =
                elasticSearch.get()
                        .prepareSearch(elasticSearch.serviceDefTypeIndex())
                        .setTypes(elasticSearch.defaultType())
                        .setVersion(true)
                        .setTimeout(timeValueMillis(elasticSearch.getDefaultSearchTimeout() - 10));

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(format("Request {%s,%s}", elasticSearch.defaultType(), elasticSearch.serviceDefTypeIndex()));
        }

        return elasticSearch.execute(vertxContext, request, elasticSearch.getDefaultSearchTimeout())
                .map(oSearchResponse -> {
                    final SearchResponse searchResponse = oSearchResponse.get();
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(format("Response = %s", Jsonify.toString(searchResponse)));
                    }
                    return searchResponse;
                })
                .flatMap(searchResponse -> from(searchResponse.getHits()))
                .map(PersistentServiceDef::fromSearchHit);
    }
}