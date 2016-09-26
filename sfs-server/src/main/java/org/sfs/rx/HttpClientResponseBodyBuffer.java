/*
 *
 * Copyright (C) 2009 The Simple File Server Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS-IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.sfs.rx;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import org.sfs.nodes.HttpClientResponseException;
import rx.Observable;
import rx.functions.Func1;

import static rx.Observable.create;

public class HttpClientResponseBodyBuffer implements Func1<HttpClientResponse, Observable<Buffer>> {

    private int[] expectedStatusCodes;

    public HttpClientResponseBodyBuffer(int... expectedStatusCodes) {
        this.expectedStatusCodes = expectedStatusCodes;
    }

    @Override
    public Observable<Buffer> call(HttpClientResponse httpClientResponse) {
        ResultMemoizeHandler<Buffer> handler = new ResultMemoizeHandler<>();
        httpClientResponse.exceptionHandler(handler::fail);
        httpClientResponse.bodyHandler(handler);
        httpClientResponse.resume();
        return create(handler.subscribe)
                .doOnNext(buffer -> {
                    int statusCode = httpClientResponse.statusCode();
                    boolean ok = false;
                    for (int expectedStatusCode : expectedStatusCodes) {
                        if (statusCode == expectedStatusCode) {
                            ok = true;
                            break;
                        }
                    }
                    if (!ok && expectedStatusCodes.length <= 0) {
                        ok = true;
                    }
                    if (!ok) {
                        throw new HttpClientResponseException(httpClientResponse, buffer);
                    }
                });
    }
}
