/*
 * Copyright 2025 Justin Marsh
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

package com.example.bytebuf.tracker;

import io.netty.buffer.ByteBuf;

/**
 * Default handler for tracking ByteBuf objects.
 *
 * This is the default implementation used when no custom handler is specified.
 * It tracks Netty ByteBuf objects and uses their reference count as the metric.
 */
public class ByteBufObjectHandler implements ObjectTrackerHandler {

    @Override
    public boolean shouldTrack(Object obj) {
        return obj instanceof ByteBuf;
    }

    @Override
    public int getMetric(Object obj) {
        if (obj instanceof ByteBuf) {
            return ((ByteBuf) obj).refCnt();
        }
        return 0;
    }

    @Override
    public String getObjectType() {
        return "ByteBuf";
    }
}
