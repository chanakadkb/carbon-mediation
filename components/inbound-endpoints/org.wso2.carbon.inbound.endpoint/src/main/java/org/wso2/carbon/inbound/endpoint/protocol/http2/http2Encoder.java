/*
 *   Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *   WSO2 Inc. licenses this file to you under the Apache License,
 *   Version 2.0 (the "License"); you may not use this file except
 *   in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.wso2.carbon.inbound.endpoint.protocol.http2;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import org.apache.http.nio.ContentEncoder;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Read bytes from Pass-through-pipe and write down on wire
 */
public class http2Encoder implements ContentEncoder {
	ChannelHandlerContext chContext;
	int streamId;
	Http2ConnectionEncoder encoder;
	ChannelPromise promise;
	boolean isComplete = false;

	public http2Encoder(ChannelHandlerContext chContext, int streamId,
	                    Http2ConnectionEncoder encoder, ChannelPromise promise) {
		this.chContext = chContext;
		this.streamId = streamId;
		this.encoder = encoder;
		this.promise = promise;

	}

	@Override
	public int write(ByteBuffer src) throws IOException {
		while (src.hasRemaining()) {
			byte[] b;
			b = new byte[src.remaining()];
			src.get(b);

			if (src.hasRemaining())
				encoder.writeData(chContext, streamId, Unpooled.wrappedBuffer(b), 0, false,
				                  promise);
			else {
				encoder.writeData(chContext, streamId, Unpooled.wrappedBuffer(b), 0, true, promise);
				isComplete = true;
			}

		}

		return src.position();
	}

	@Override
	public void complete() throws IOException {

	}

	@Override
	public boolean isCompleted() {
		return isComplete;
	}
}
