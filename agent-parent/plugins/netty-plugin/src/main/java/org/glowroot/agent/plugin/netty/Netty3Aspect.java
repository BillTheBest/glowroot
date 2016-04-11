/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.agent.plugin.netty;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.AuxThreadContext;
import org.glowroot.agent.plugin.api.OptionalThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.IsEnabled;
import org.glowroot.agent.plugin.api.weaving.Mixin;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;

public class Netty3Aspect {

    // the field and method names are verbose to avoid conflict since they will become fields
    // and methods in all classes that extend org.jboss.netty.channel.Channel
    @Mixin({"org.jboss.netty.channel.Channel"})
    public abstract static class ChannelImpl implements ChannelMixin {

        private volatile boolean glowroot$completeAsyncTransaction;

        @Override
        public boolean glowroot$getCompleteAsyncTransaction() {
            return glowroot$completeAsyncTransaction;
        }

        @Override
        public void glowroot$setCompleteAsyncTransaction(boolean completeAsyncTransaction) {
            this.glowroot$completeAsyncTransaction = completeAsyncTransaction;
        }
    }

    // the method names are verbose to avoid conflict since they will become methods in all classes
    // that extend org.jboss.netty.channel.Channel
    public interface ChannelMixin {

        boolean glowroot$getCompleteAsyncTransaction();

        void glowroot$setCompleteAsyncTransaction(boolean completeAsyncTransaction);
    }

    // the field and method names are verbose to avoid conflict since they will become fields
    // and methods in all classes that extend org.jboss.netty.channel.ChannelFutureListener
    @Mixin({"org.jboss.netty.channel.ChannelFutureListener"})
    public abstract static class ListenerImpl implements ListenerMixin {

        private volatile @Nullable AuxThreadContext glowroot$auxContext;

        @Override
        public @Nullable AuxThreadContext glowroot$getAuxContext() {
            return glowroot$auxContext;
        }

        @Override
        public void glowroot$setAuxContext(@Nullable AuxThreadContext auxContext) {
            this.glowroot$auxContext = auxContext;
        }
    }

    // the method names are verbose to avoid conflict since they will become methods in all classes
    // that extend org.jboss.netty.channel.ChannelFutureListener
    public interface ListenerMixin {

        @Nullable
        AuxThreadContext glowroot$getAuxContext();

        void glowroot$setAuxContext(@Nullable AuxThreadContext auxContext);
    }

    @Shim("org.jboss.netty.channel.ChannelHandlerContext")
    public interface ChannelHandlerContext {

        @Shim("org.jboss.netty.channel.Channel getChannel()")
        @Nullable
        ChannelMixin glowroot$getChannel();
    }

    @Shim("org.jboss.netty.handler.codec.http.HttpRequest")
    public interface HttpRequest {

        @Shim("org.jboss.netty.handler.codec.http.HttpMethod getMethod()")
        HttpMethod glowroot$getMethod();

        @Nullable
        String getUri();
    }

    @Shim("org.jboss.netty.handler.codec.http.HttpMethod")
    public interface HttpMethod {
        @Nullable
        String getName();
    }

    @Shim("org.jboss.netty.channel.MessageEvent")
    public interface MessageEvent {
        @Nullable
        Object getMessage();
    }

    @Shim("org.jboss.netty.handler.codec.http.HttpMessage")
    public interface HttpMessage {
        boolean isChunked();
    }

    @Shim("org.jboss.netty.handler.codec.http.HttpChunk")
    public interface HttpChunk {
        boolean isLast();
    }

    @Pointcut(className = "org.jboss.netty.channel.ChannelHandlerContext",
            methodName = "sendUpstream",
            methodParameterTypes = {"org.jboss.netty.channel.ChannelEvent"},
            nestingGroup = "netty-inbound", timerName = "http request")
    public static class InboundAdvice {

        private static final TimerName timerName = Agent.getTimerName(InboundAdvice.class);

        @OnBefore
        public static @Nullable TraceEntry onBefore(OptionalThreadContext context,
                @BindReceiver ChannelHandlerContext channelHandlerContext,
                @BindParameter @Nullable Object channelEvent) {
            ChannelMixin channel = channelHandlerContext.glowroot$getChannel();
            if (channel == null) {
                return null;
            }
            if (channelEvent == null) {
                return null;
            }
            if (!(channelEvent instanceof MessageEvent)) {
                return null;
            }
            Object msg = ((MessageEvent) channelEvent).getMessage();
            if (!(msg instanceof HttpRequest)) {
                return null;
            }
            HttpRequest request = (HttpRequest) msg;
            HttpMethod method = request.glowroot$getMethod();
            String methodName = method == null ? null : method.getName();
            channel.glowroot$setCompleteAsyncTransaction(true);
            return NettyAspect.startAsyncTransaction(context, methodName, request.getUri(),
                    timerName);
        }

        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.end();
            }
        }

        @OnThrow
        public static void onThrow(@BindThrowable Throwable throwable,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.endWithError(throwable);
            }
        }
    }

    @Shim("com.typesafe.netty.http.pipelining.OrderedDownstreamChannelEvent")
    public interface OrderedDownstreamChannelEvent {
        boolean isLast();
    }

    @Pointcut(className = "org.jboss.netty.channel.ChannelDownstreamHandler",
            methodName = "handleDownstream",
            methodParameterTypes = {"org.jboss.netty.channel.ChannelHandlerContext",
                    "org.jboss.netty.channel.ChannelEvent"})
    public static class OutboundAdvice {

        @IsEnabled
        public static boolean isEnabled(
                @BindParameter @Nullable ChannelHandlerContext channelHandlerContext) {
            if (channelHandlerContext == null) {
                return false;
            }
            ChannelMixin channel = channelHandlerContext.glowroot$getChannel();
            return channel != null && channel.glowroot$getCompleteAsyncTransaction();
        }

        @OnBefore
        public static void onBefore(ThreadContext context,
                @BindParameter @Nullable ChannelHandlerContext channelHandlerContext,
                @BindParameter @Nullable Object channelEvent) {
            if (channelHandlerContext == null) {
                return;
            }
            if (channelEvent instanceof OrderedDownstreamChannelEvent) {
                // play 2.2.x and later implements its own chunked transfer, not using netty's
                // MessageEvent/HttpMessage/HttpChunk
                if (((OrderedDownstreamChannelEvent) channelEvent).isLast()) {
                    completeAsyncTransaction(context, channelHandlerContext);
                }
                return;
            }
            if (!(channelEvent instanceof MessageEvent)) {
                return;
            }
            Object messageEvent = ((MessageEvent) channelEvent).getMessage();
            if (messageEvent instanceof HttpMessage) {
                if (!((HttpMessage) messageEvent).isChunked()) {
                    completeAsyncTransaction(context, channelHandlerContext);
                }
                return;
            }
            if (messageEvent instanceof HttpChunk) {
                if (((HttpChunk) messageEvent).isLast()) {
                    completeAsyncTransaction(context, channelHandlerContext);
                }
                return;
            }
        }

        private static void completeAsyncTransaction(ThreadContext context,
                ChannelHandlerContext channelHandlerContext) {
            context.completeAsyncTransaction();
            ChannelMixin channel = channelHandlerContext.glowroot$getChannel();
            if (channel != null) {
                channel.glowroot$setCompleteAsyncTransaction(false);
            }
        }
    }

    @Pointcut(className = "org.jboss.netty.channel.ChannelFuture", methodName = "addListener",
            methodParameterTypes = {"org.jboss.netty.channel.ChannelFutureListener"})
    public static class AddListenerAdvice {
        @OnBefore
        public static void onBefore(ThreadContext context, @BindParameter ListenerMixin listener) {
            AuxThreadContext auxContext = context.createAuxThreadContext();
            listener.glowroot$setAuxContext(auxContext);
        }
    }

    @Pointcut(className = "org.jboss.netty.channel.ChannelFutureListener",
            methodName = "operationComplete",
            methodParameterTypes = {"org.jboss.netty.channel.ChannelFuture"})
    public static class OperationCompleteAdvice {
        @OnBefore
        public static @Nullable TraceEntry onBefore(@BindReceiver ListenerMixin listener) {
            AuxThreadContext auxContext = listener.glowroot$getAuxContext();
            if (auxContext != null) {
                listener.glowroot$setAuxContext(null);
                return auxContext.start();
            }
            return null;
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.end();
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.endWithError(t);
            }
        }
    }
}
