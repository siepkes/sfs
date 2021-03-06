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

package org.sfs.integration.java.func;

import io.vertx.core.logging.Logger;
import rx.functions.Func1;

import static io.vertx.core.logging.LoggerFactory.getLogger;

public class PrintMessage<A> implements Func1<A, A> {

    private static final Logger LOGGER = getLogger(PrintMessage.class);
    private final String message;

    public PrintMessage(String message) {
        this.message = message;
    }

    @Override
    public A call(A o) {
        LOGGER.debug(message);
        return o;
    }
}
